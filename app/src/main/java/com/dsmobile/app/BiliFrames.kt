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
        /** MP4 直链：给了就用原始帧，比雪碧图（单格 160×90）清楚一个量级。 */
        playUrl: String = "",
        /** MP4 缓存键：bvid 之类稳定值。直链带签名（deadline/sign），拿它当键永远命中不了。 */
        cacheKey: String = "",
        /** debug 包把成品宫格存到 cache 目录，方便 adb 拉出来核对。 */
        dumpDebug: Boolean = false,
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        // 原始帧优先：雪碧图单格往往只有 160×90，放大到宫格里就是糊的
        if (playUrl.isNotBlank()) {
            val mp4 = runCatching {
                loadMp4Grids(playUrl, cookie, cacheDir, dumpDebug, cacheKey)
            }.getOrNull()
            if (!mp4.isNullOrEmpty()) return@withContext mp4
        }
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
            // 单格宽高比要先知道：竖屏（9:16）用 3 列 × 2 行六格，横屏用 3×3 九格。
            // 3×3 套竖屏会得到 405×720 的窄长画布，每格只有 135×240，模型看不清表情和字幕。
            cellDecoder(0)?.let { }
            val firstCell = cellSizes[0]
            if (firstCell == null || firstCell[0] <= 0 || firstCell[1] <= 0) return@withContext emptyList()
            val cellAspect = firstCell[0].toFloat() / firstCell[1].toFloat()
            val outCols = 3
            val outRows = if (cellAspect < 0.8f) 2 else 3

            // index[] 只列真实帧；末行可能用空白格补齐，按它裁掉
            val totalCells = perSheet * urls.size
            val valid = if (timestamps.isNotEmpty()) min(timestamps.size, totalCells) else totalCells
            if (valid <= 0) return@withContext emptyList()
            // 4:40 的视频只给 6-9 帧，等于 30-47 秒才看一眼，中间全靠猜。
            // 按时长给 1-3 张宫格：≤2 分钟 1 张，≤4 分钟 2 张，更长 3 张。
            val durationSec = timestamps.lastOrNull() ?: 0.0
            val sheetCount = when {
                durationSec <= 120.0 -> 1
                durationSec <= 240.0 -> 2
                else -> 3
            }
            val cellsPer = outCols * outRows
            val picks = min(cellsPer * sheetCount, valid)
            val even = (0 until picks).map {
                if (picks == 1) 0 else it * (valid - 1) / (picks - 1)
            }
            // 静止画面会让连续两帧时间点相同 → 两格长得一样，白占位置
            val positions = if (timestamps.isEmpty()) {
                even
            } else {
                val chosen = mutableListOf<Int>()
                val seenSeconds = HashSet<Int>()
                for (p in even) {
                    val sec = timestamps.getOrNull(p)?.toInt()
                    if (sec != null && !seenSeconds.add(sec)) continue
                    chosen.add(p)
                }
                if (chosen.size < picks) {
                    for (p in 0 until valid) {
                        if (chosen.size >= picks) break
                        if (p in chosen) continue
                        val sec = timestamps.getOrNull(p)?.toInt() ?: continue
                        if (!seenSeconds.add(sec)) continue
                        chosen.add(p)
                    }
                }
                chosen.sorted()
            }
            // 画布长边给到 1024：720 时每格只有 240×135，模型读不清字幕和小物件。
            // 比例跟着「格子布局 × 单格宽高比」走，竖屏不再被拉变形。
            val maxSide = 1024f
            val canvasAspect = (outCols.toFloat() / outRows.toFloat()) * cellAspect
            val outW: Int
            val outH: Int
            if (canvasAspect >= 1f) {
                outW = maxSide.toInt()
                outH = (maxSide / canvasAspect).toInt().coerceAtLeast(1)
            } else {
                outH = maxSide.toInt()
                outW = (maxSide * canvasAspect).toInt().coerceAtLeast(1)
            }
            val cellWOut = outW / outCols.toFloat()
            val cellHOut = outH / outRows.toFloat()
            // 字号按画布长边走，不再乘 outW/720（竖屏时那会缩到 10px，等于没有）
            val scale = maxSide / 720f

            val grid = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val cellsPerGrid = outCols * outRows
            val groupCount = ((positions.size + cellsPerGrid - 1) / cellsPerGrid).coerceAtLeast(1)
            val grids = ArrayList<Bitmap>(groupCount)
            val candidates = mutableListOf<Bitmap>()
            for (g in 0 until groupCount) {
            val grid = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(grid)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 18f * scale
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            // 时间戳用黄色小字，和编号区分开，避免模型把时间当成编号
            val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFEB3B")
                textSize = 14f * scale
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            val from = g * cellsPerGrid
            val to = min(from + cellsPerGrid, positions.size)
            val slice = positions.subList(from, to)
            slice.forEachIndexed { i, base ->
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
                val left = (i % outCols) * cellWOut
                val top = (i / outCols) * cellHOut
                canvas.drawBitmap(
                    picked,
                    null,
                    android.graphics.RectF(left, top, left + cellWOut, top + cellHOut),
                    paint,
                )
                canvas.drawText("${i + 1}", left + 6f * scale, top + 22f * scale, labelPaint)
                timestamps.getOrNull(usedIndex)?.let { t ->
                    canvas.drawText(
                        formatClock(t),
                        left + 6f * scale,
                        top + 40f * scale,
                        timePaint,
                    )
                }
                candidates.add(picked)
            }
            grids.add(grid)
            }
            // debug 包把成品宫格落一份，方便 adb 拉出来核对（release 不写）
            if (dumpDebug) {
                runCatching {
                    cacheDir?.let { dir ->
                        grids.forEachIndexed { idx, bmp ->
                            File(dir, "bili-grid-debug-$idx.jpg").outputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            }
                        }
                        Log.i("BiliFrames", "grid debug saved valid=$valid grids=${grids.size}")
                    }
                }
            }
            // 关键帧就是宫格里的同一张位图、同样分辨率，单独再发一遍只会让模型
            // 把"同一张图"当成多张照片，还白花一份图片 token（每张 384）。
            candidates.forEach { runCatching { it.recycle() } }
            grids
            // 关键帧就是宫格里的同一张位图、同样分辨率，单独再发一遍只会让模型
            // 把"同一张图"当成多张照片，还白花一份图片 token（每张 384）。
            candidates.forEach { runCatching { it.recycle() } }
            listOf(grid)
        } catch (_: Exception) {
            emptyList()
        } finally {
            decoders.values.forEach { runCatching { it.recycle() } }
        }
    }

    /**
     * 从 durl MP4 直接抽帧。用户明确要求「准确性优先」，所以不再受 30MB 限制：
     * 360p 的 4 分钟视频约 40MB，换来的是一帧 640×360 而不是 160×90。
     * 下载过的文件留在 cache，同一条视频重看不重复下载。
     */
    private fun loadMp4Grids(
        playUrl: String,
        cookie: String,
        cacheDir: File?,
        dumpDebug: Boolean,
        cacheKey: String,
    ): List<Bitmap>? {
        val file = mp4CacheFile(cacheKey.ifBlank { playUrl }, cacheDir) ?: return null
        if (!file.exists() || file.length() < 1_000_000L) {
            pruneMp4Cache(cacheDir)
            file.parentFile?.mkdirs()
            val request = Request.Builder()
                .url(playUrl)
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("BiliFrames", "mp4 download HTTP ${resp.code}")
                    return null
                }
                val body = resp.body ?: return null
                body.byteStream().use { input ->
                    file.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                }
                Log.i("BiliFrames", "mp4 downloaded ${file.length() / 1024 / 1024}MB")
            }
        }
        if (!file.exists() || file.length() < 1_000_000L) return null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            if (durationMs < 1000) return null
            val durationSec = durationMs / 1000.0
            // 先探一帧拿宽高比：竖屏 3×2，横屏 3×3
            val probe = retriever.getFrameAtTime(
                durationMs * 500L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: return null
            if (probe.width <= 0 || probe.height <= 0) return null
            val cellAspect = probe.width.toFloat() / probe.height.toFloat()
            probe.recycle()
            val outCols = 3
            val outRows = if (cellAspect < 0.8f) 2 else 3
            val cellsPerGrid = outCols * outRows
            // 时长越长给越多张：≤1.5min 1 张 … >7.5min 5 张（4:40 → 3 张 27 帧）
            val gridCount = when {
                durationSec <= 90.0 -> 1
                durationSec <= 180.0 -> 2
                durationSec <= 300.0 -> 3
                durationSec <= 450.0 -> 4
                else -> 5
            }
            val total = cellsPerGrid * gridCount
            val maxSide = 1024f
            val canvasAspect = (outCols.toFloat() / outRows) * cellAspect
            val outW = if (canvasAspect >= 1f) maxSide.toInt()
            else (maxSide * canvasAspect).toInt().coerceAtLeast(1)
            val outH = if (canvasAspect >= 1f)
            (maxSide / canvasAspect).toInt().coerceAtLeast(1) else maxSide.toInt()
            val cellWOut = outW / outCols.toFloat()
            val cellHOut = outH / outRows.toFloat()
            val scale = maxSide / 720f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 18f * scale
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFEB3B")
                textSize = 14f * scale
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            val grids = ArrayList<Bitmap>(gridCount)
            for (g in 0 until gridCount) {
                val grid = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(grid)
                canvas.drawColor(Color.BLACK)
                for (i in 0 until cellsPerGrid) {
                    val idx = g * cellsPerGrid + i
                    val tSec = (idx + 0.5) / total * durationSec
                    val frame = retriever.getFrameAtTime(
                        (tSec * 1_000_000).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ) ?: continue
                    val left = (i % outCols) * cellWOut
                    val top = (i / outCols) * cellHOut
                    // 等比铺满 + 居中裁剪，避免拉伸变形
                    val fit = max(cellWOut / frame.width, cellHOut / frame.height)
                    val dw = frame.width * fit
                    val dh = frame.height * fit
                    val dx = left + (cellWOut - dw) / 2f
                    val dy = top + (cellHOut - dh) / 2f
                    canvas.drawBitmap(
                        frame,
                        null,
                        android.graphics.RectF(dx, dy, dx + dw, dy + dh),
                        paint,
                    )
                    frame.recycle()
                    canvas.drawText("${i + 1}", left + 6f * scale, top + 22f * scale, labelPaint)
                    canvas.drawText(
                        formatClock(tSec),
                        left + 6f * scale,
                        top + 40f * scale,
                        timePaint,
                    )
                }
                grids.add(grid)
            }
            if (dumpDebug) {
                runCatching {
                    cacheDir?.let { dir ->
                        grids.forEachIndexed { idx, bmp ->
                            File(dir, "bili-grid-mp4-$idx.jpg").outputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                            }
                        }
                    }
                }
            }
            Log.i("BiliFrames", "mp4 grids=${grids.size} cell=${outW / outCols}x${outH / outRows} dur=${durationSec.toInt()}s")
            return grids
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 二次抽帧：模型点名要看清的秒数 → 直接 seek 缓存的 MP4，取原始分辨率帧。
     * 每处取 3 帧（前后各 2 秒），左上角标时间，让模型能把它们和宫格对上。
     */
    suspend fun detailFrames(
        playUrl: String,
        cacheDir: File?,
        seconds: List<Double>,
        cacheKey: String = "",
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        if (seconds.isEmpty() || playUrl.isBlank()) return@withContext emptyList()
        val file = mp4CacheFile(cacheKey.ifBlank { playUrl }, cacheDir) ?: return@withContext emptyList()
        if (!file.exists() || file.length() < 1_000_000L) return@withContext emptyList()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return@withContext emptyList()
            val out = ArrayList<Bitmap>()
            for (t in seconds.take(2)) {
                for (off in doubleArrayOf(-2.0, 0.0, 2.0)) {
                    val at = (t + off).coerceIn(0.0, durationMs / 1000.0)
                    val frame = retriever.getFrameAtTime(
                        (at * 1_000_000).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    ) ?: continue
                    val marked = frame.copy(Bitmap.Config.ARGB_8888, true) ?: frame
                    if (marked !== frame) frame.recycle()
                    val canvas = Canvas(marked)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#FFEB3B")
                        textSize = marked.width / 18f
                        setShadowLayer(6f, 2f, 2f, Color.BLACK)
                    }
                    canvas.drawText(
                        formatClock(at),
                        marked.width * 0.02f,
                        marked.height * 0.08f,
                        paint,
                    )
                    out.add(marked)
                }
            }
            Log.i("BiliFrames", "detail frames=${out.size} for ${seconds.size} points")
            out
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }
    /** MP4 缓存文件：按直链哈希命名，避免同一视频重复下载几十 MB。 */
    private fun mp4CacheFile(key: String, cacheDir: File?): File? {
        val dir = cacheDir ?: return null
        val name = "bili-src-" + Integer.toHexString(key.hashCode()) + ".mp4"
        return File(dir, name)
    }

    /** 缓存别无限长：超过 8 个文件或 7 天没动过的删掉。 */
    private fun pruneMp4Cache(cacheDir: File?) {
        val dir = cacheDir ?: return
        runCatching {
            val files = dir.listFiles { it -> it.name.startsWith("bili-src-") && it.name.endsWith(".mp4") }
                ?: return
            val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
            files.sortedByDescending { it.lastModified() }.drop(8).forEach { it.delete() }
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
