package com.dsmobile.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * 从视频里抽音轨，给服务端做语音识别。
 *
 * 不重新编码：直接把原音轨 remux 成 m4a——省电、不掉质量，
 * 一分钟大约 1 MB，whisper 那边能直接吃。
 *
 * 抽不出来（无声视频、编码怪）就返回 null，调用方降级成「没有台词」。
 */
object VideoAudio {

    /** 最多取前 10 分钟：再长也不是「他随手发的一段」。 */
    private const val MAX_MS = 10 * 60 * 1000L
    /** 上限 7 MB：服务端 8 MB 才拒，这里先留点余量。 */
    private const val MAX_BYTES = 7L * 1024 * 1024

    fun extract(context: Context, uri: Uri): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var out: File? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)

            out = File.createTempFile("voice-", ".m4a", context.cacheDir)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dst = muxer.addTrack(format)
            muxer.start()

            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            var written = 0L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > MAX_MS * 1000) break
                info.set(0, size, time, extractor.sampleFlags)
                muxer.writeSampleData(dst, buffer, info)
                written += size
                if (written > MAX_BYTES) break
                if (!extractor.advance()) break
            }
            muxer.stop()
            muxer.release()
            muxer = null
            if (out.length() < 1024L) {
                out.delete()
                return null
            }
            return out
        } catch (e: Exception) {
            android.util.Log.w("VideoAudio", "抽音轨失败: " + e.message)
            out?.delete()
            return null
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
