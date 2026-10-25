package com.dsmobile.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** 手机端 B 站视频抽帧：下载 durl MP4，用 MediaMetadataRetriever 拼 3x3 网格。 */
object BiliFrames {
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

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
