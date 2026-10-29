package com.dsmobile.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.util.Log
import com.dsmobile.app.net.MediaDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** 手机端 B 站视频抽帧：下载 durl MP4，用 MediaMetadataRetriever 拼 3x3 网格。 */
object BiliFrames {
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun loadSingleFrame(
        storyboardUrl: String,
        cols: Int,
        rows: Int,
        cookie: String,
        cacheDir: File?,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (storyboardUrl.isBlank() || cols <= 0 || rows <= 0) return@withContext null
        val bytes = downloadBytes(storyboardUrl, cookie, cacheDir) ?: return@withContext null
        runCatching {
            val decoder = BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), false)
                ?: return@withContext null
            try {
                val cellW = decoder.width / cols
                val cellH = decoder.height / rows
                if (cellW <= 0 || cellH <= 0) return@withContext null
                val col = cols / 2
                val row = rows / 2
                decoder.decodeRegion(
                    Rect(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH),
                    BitmapFactory.Options(),
                )
            } finally {
                decoder.recycle()
            }
        }.getOrNull()
    }

    /** 把时间点格式化成 m:ss，画在格子里，让模型能把"第 5 格"对回时间轴。 */
    private fun formatClock(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return if (m >= 60) {
            val h = m / 60
            "%d:%02d:%02d".format(h, m % 60, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }

    /** 多张雪碧图 + 时间点 → 3×3 编号宫格 + 2 张高亮关键帧。 */
    suspend fun loadAiFrames(
        storyboardUrl: String,
        cols: Int,
        rows: Int,
        cookie: String,
        cacheDir: File?,
        /** videoshot 的 index[]：每帧秒数。给了就按它裁掉末行空白格并标注时间。 */
        timestamps: List<Double> = emptyList(),
        /** videoshot 的 image[]：长视频被切成多张雪碧图，只取第一张会丢掉后半段。 */
        spriteUrls: List<String> = emptyList(),
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val urls = spriteUrls.filter { it.isNotBlank() }.ifEmpty { listOf(storyboardUrl) }
        if (urls.all { it.isBlank() } || cols <= 0 || rows <= 0) return@withContext emptyList()
        val perSheet = cols * rows
        val decoders = HashMap<Int, BitmapRegionDecoder>()
        val cellSizes = HashMap<Int, IntArray>()

        fun cellDecoder(sheet: Int): BitmapRegionDecoder? {
            decoders[sheet]?.let { return it }
            val url = urls.getOrNull(sheet) ?: return null
            val bytes = downloadBytes(url, cookie, cacheDir) ?: return null
            val dec = BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), false) ?: return null
            decoders[sheet] = dec
            cellSizes[sheet] = intArrayOf(dec.width / cols, dec.height / rows)
            return dec
        }

        fun decodeAt(index: Int): Bitmap? {
            val sheet = index / perSheet
            val tile = index % perSheet
            val dec = cellDecoder(sheet) ?: return null
            val size = cellSizes[sheet] ?: return null
            val cw = size[0]
            val ch = size[1]
            if (cw <= 0 || ch <= 0) return null
            val col = tile % cols
            val row = tile / cols
            return runCatching {
                dec.decodeRegion(
                    Rect(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch),
                    BitmapFactory.Options(),
                )
            }.getOrNull()
        }

        try {
            // index[] 只列真实帧；末行可能用空白格补齐，按它裁掉
            val totalCells = perSheet * urls.size
            val valid = if (timestamps.isNotEmpty()) min(timestamps.size, totalCells) else totalCells
            if (valid <= 0) return@withContext emptyList()
            val picks = min(9, valid)
            val positions = (0 until picks).map {
                if (picks == 1) 0 else it * (valid - 1) / (picks - 1)
            }
            val grid = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(grid)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 18f
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            // 时间戳用黄色小字，和编号区分开，避免模型把时间当成编号
            val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFEB3B")
                textSize = 14f
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            val candidates = mutableListOf<Bitmap>()
            positions.forEachIndexed { i, base ->
                // 纯黑转场帧对模型没用；就近找一个亮一点的帧替代（最多挪 3 格）
                var frame: Bitmap? = null
                var usedIndex = base
                val offsets = intArrayOf(0, 1, -1, 2, -2, 3, -3)
                for ((k, off) in offsets.withIndex()) {
                    val cand = base + off
                    if (cand < 0 || cand >= valid) continue
                    val bmp = decodeAt(cand) ?: continue
                    val last = k == offsets.size - 1
                    frame = bmp
                    usedIndex = cand
                    if (meanBrightness(bmp) >= 15 || last) break
                    bmp.recycle()
                    frame = null
                }
                val picked = frame ?: return@forEachIndexed
                val left = (i % 3) * 240f
                val top = (i / 3) * 135f
                canvas.drawBitmap(
                    picked,
                    null,
                    android.graphics.RectF(left, top, left + 240f, top + 135f),
                    paint,
                )
                canvas.drawText("${i + 1}", left + 6f, top + 22f, labelPaint)
                timestamps.getOrNull(usedIndex)?.let { t ->
                    canvas.drawText(formatClock(t), left + 6f, top + 40f, timePaint)
                }
                candidates.add(picked)
            }
            val output = mutableListOf(grid)
            // 关键帧不取固定位置，选亮度最高/信息最多的两张，避开纯黑转场。
            val keyFrames = candidates
                .map { it to brightnessScore(it) }
                .sortedByDescending { it.second }
                .take(2)
                .map { it.first }
            output.addAll(keyFrames)
            candidates.forEach { if (it !in keyFrames) it.recycle() }
            output
        } catch (_: Exception) {
            emptyList()
        } finally {
            decoders.values.forEach { runCatching { it.recycle() } }
        }
    }

    /** 采样平均亮度（0-255），用来跳过纯黑转场帧。 */
    private fun meanBrightness(bitmap: Bitmap): Int {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 14).coerceAtLeast(1)
        var sum = 0L
        var n = 0L
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                sum += ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0L) 0 else (sum / n).toInt()
    }

    private fun brightnessScore(bitmap: Bitmap): Long {
        var score = 0L
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 14).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                score += (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
                x += stepX
            }
            y += stepY
        }
        return score
    }

    suspend fun load(
        cacheKey: String,
        storyboardUrl: String,
        storyboardCols: Int,
        storyboardRows: Int,
        playUrl: String,
        cookie: String,
        cacheDir: File? = null,
    ): Bitmap? = withContext(Dispatchers.IO) {
        MediaCaches.biliFrame(cacheKey)?.let { return@withContext it }
        if (storyboardUrl.isNotBlank() && storyboardCols > 0 && storyboardRows > 0) {
            downloadBytes(storyboardUrl, cookie, cacheDir)?.let { bytes ->
                extractStoryboard(bytes, storyboardCols, storyboardRows)?.let {
                    MediaCaches.putBiliFrame(cacheKey, it)
                    Log.i("BiliFrames", "storyboard ok ${it.width}x${it.height}")
                    return@withContext it
                }
            }
        }
        if (playUrl.isBlank()) return@withContext null
        Log.i("BiliFrames", "start ${cacheKey.take(40)} url=${playUrl.take(60)} cookie=${cookie.isNotBlank()}")
        val tmp = File.createTempFile("bili-frame-", ".mp4")
        try {
            val request = Request.Builder()
                .url(playUrl)
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("BiliFrames", "download HTTP ${response.code}")
                    return@withContext null
                }
                val length = response.body?.contentLength() ?: -1
                if (length > MAX_BYTES) return@withContext null
                response.body?.byteStream()?.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_BYTES) return@withContext null
                            out.write(buf, 0, n)
                        }
                    }
                } ?: return@withContext null
            }
            val bitmap = extract(tmp)
            Log.i("BiliFrames", "extract result=${bitmap != null}")
            if (bitmap != null) MediaCaches.putBiliFrame(cacheKey, bitmap)
            bitmap
        } catch (e: Exception) {
            Log.w("BiliFrames", "failed ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            tmp.delete()
        }
    }

    private fun extractStoryboard(bytes: ByteArray, cols: Int, rows: Int): Bitmap? {
        // B站 image[] 本身就是 cols×rows 的拼图，直接显示整张，不再裁成 3×3。
        for (sample in intArrayOf(4, 2, 1)) {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun downloadBytes(url: String, cookie: String, cacheDir: File?): ByteArray? {
        val cache = cacheDir?.let { MediaDiskCache(File(it, "bili-cache")) }
        val key = "storyboard:$url"
        cache?.get(key)?.let { return it }
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                if (bytes.size > 4 * 1024 * 1024) null else {
                    cache?.put(key, bytes)
                    bytes
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extract(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0) return null
            val cellW = 320
            val cellH = 180
            val sheet = Bitmap.createBitmap(cellW * 3, cellH * 3, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var drawn = 0
            for (i in 0 until 9) {
                val atUs = (durationMs * 1000L * (i + 0.5) / 9.0).toLong()
                val frame = retriever.getFrameAtTime(
                    atUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: continue
                val left = (i % 3) * cellW
                val top = (i / 3) * cellH
                val scale = max(cellW.toFloat() / frame.width, cellH.toFloat() / frame.height)
                val dw = frame.width * scale
                val dh = frame.height * scale
                val dx = left + (cellW - dw) / 2f
                val dy = top + (cellH - dh) / 2f
                canvas.save()
                canvas.clipRect(left, top, left + cellW, top + cellH)
                canvas.drawBitmap(frame, null, android.graphics.RectF(dx, dy, dx + dw, dy + dh), paint)
                canvas.restore()
                frame.recycle()
                drawn++
            }
            if (drawn == 0) {
                sheet.recycle()
                null
            } else {
                sheet
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
