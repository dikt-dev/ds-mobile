package com.dsmobile.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.max

/**
 * 手机端视频抽帧：给模型看的「她到底看了什么」。
 *
 * 只发一条 [视频:key] 等于什么都没发——她只知道"你发了个视频"，
 * 然后开始编内容（线上真的编过：说雨夜、说窗边、说她抱着膝盖）。
 *
 * 帧数：12 帧按时间均匀取。9 帧能看出动作，但看不清脸和字幕；
 * 24 帧开始重复（同一秒里两帧几乎一样），收益不抵传输时间。
 * 每帧缩到 720px 宽再压 JPEG——原分辨率对识别没帮助，只涨体积。
 */
object VideoFrames {

    /** 均匀抽 [count] 帧；解不出来就返回空列表，由调用方退回纯文字。 */
    fun extract(context: Context, uri: Uri, count: Int = 12, maxWidth: Int = 720): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return emptyList()

            // 每一段取中点，避开片头黑场和片尾字幕
            (0 until count).mapNotNull { i ->
                val at = durationMs * (2L * i + 1) / (2L * count) * 1000L
                retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST)?.let {
                    scaleDown(it, maxWidth)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoFrames", "抽帧失败: ${e.message}")
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val h = max(1, (src.height.toLong() * maxWidth / src.width).toInt())
        val scaled = Bitmap.createScaledBitmap(src, maxWidth, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }
}
