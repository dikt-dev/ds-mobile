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
 * 排版跟 B 站那套抽帧一致（见 BiliFrames）：**固定 3 列**，横屏 3×3 九格、
 * 竖屏 3×2 六格，时长越长给越多张。以前这里是 12 帧 + 一张 4×3 宫格——
 * 一是列数和 B 站那边对不上，同一个视频在两处画面比例不一样；
 * 二是 12 张单帧 + 1 张宫格一共 13 张图，token 花得比宫格方案多一个量级，
 * 而单帧能提供的"细节"其实宫格放大后也够读。
 *
 * 每帧缩到 720px 宽再压 JPEG——原分辨率对识别没帮助，只涨体积。
 */
object VideoFrames {

    /** 宫格排版：[cols] 列 × [rows] 行，一共 [sheets] 张。 */
    data class FrameGrid(val cols: Int, val rows: Int, val sheets: Int) {
        val perSheet: Int get() = cols * rows
        val frames: Int get() = perSheet * sheets
    }

    /** 抽好的帧和它们的排版（时长/比例留给观察卡用）。 */
    data class Planned(
        val plan: FrameGrid,
        val frames: List<Bitmap>,
        val durationMs: Long,
        val aspect: Float,
    )

    /**
     * 时长 + 宽高比 → 排版。纯函数，不碰 Android API，方便单测。
     *
     * 分档与 BiliFrames 完全一致：≤90s 1 张、≤180s 2 张、≤300s 3 张、
     * ≤450s 4 张、更长 5 张。竖屏（宽高比 < 0.8）用 3×2，
     * 免得 3×3 套竖屏得到一张窄长画布、每格只剩一条竖条。
     */
    fun planOf(durationMs: Long, aspect: Float): FrameGrid {
        val sec = durationMs / 1000.0
        val rows = if (aspect < 0.8f) 2 else 3
        val sheets = when {
            sec <= 90.0 -> 1
            sec <= 180.0 -> 2
            sec <= 300.0 -> 3
            sec <= 450.0 -> 4
            else -> 5
        }
        return FrameGrid(cols = 3, rows = rows, sheets = sheets)
    }

    /** 均匀抽 [count] 帧；解不出来就返回空列表，由调用方退回纯文字。 */
    fun extract(context: Context, uri: Uri, count: Int = 9, maxWidth: Int = 720): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return emptyList()
            grab(retriever, durationMs, count, maxWidth)
        } catch (e: Exception) {
            android.util.Log.w("VideoFrames", "抽帧失败: ${e.message}")
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 按 B 站排版抽帧：先读时长和分辨率定排版，再抽够 plan.frames 张。
     * 读不到时长就返回 null，调用方退回纯文字。
     */
    fun extractPlanned(context: Context, uri: Uri, maxWidth: Int = 720): Planned? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return null
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val aspect = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 16f / 9f
            val plan = planOf(durationMs, aspect)
            val frames = grab(retriever, durationMs, plan.frames, maxWidth)
            if (frames.isEmpty()) null else Planned(plan, frames, durationMs, aspect)
        } catch (e: Exception) {
            android.util.Log.w("VideoFrames", "按排版抽帧失败: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 每一段取中点，避开片头黑场和片尾字幕。 */
    private fun grab(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        count: Int,
        maxWidth: Int,
    ): List<Bitmap> = (0 until count).mapNotNull { i ->
        val at = durationMs * (2L * i + 1) / (2L * count) * 1000L
        retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST)?.let {
            scaleDown(it, maxWidth)
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
