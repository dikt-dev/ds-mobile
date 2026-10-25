package com.dsmobile.app.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** 16 kHz / mono / PCM16 recording; stop() returns WAV for Azure STT. */
class VoiceRecorder {
    private val sampleRate = 16_000
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private val pcm = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return false
        val bufferSize = maxOf(min * 2, sampleRate * 2)
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull() ?: return false
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        pcm.reset()
        record = recorder
        running = true
        runCatching { recorder.startRecording() }.onFailure {
            running = false
            recorder.release()
            record = null
            return false
        }
        worker = thread(name = "ds-voice-record", isDaemon = true) {
            val buf = ByteArray(bufferSize)
            while (running) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) pcm.write(buf, 0, n)
            }
        }
        return true
    }

    fun stop(): ByteArray? {
        if (!running) return null
        running = false
        val recorder = record
        record = null
        runCatching { recorder?.stop() }
        recorder?.release()
        worker?.join(500)
        worker = null
        val raw = pcm.toByteArray()
        if (raw.size < sampleRate) return null
        return wav(raw)
    }

    fun cancel() {
        running = false
        runCatching { record?.stop() }
        record?.release()
        record = null
        worker = null
        pcm.reset()
    }

    private fun wav(pcmBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcmBytes.size + 44)
        val dataLen = pcmBytes.size
        val byteRate = sampleRate * 2
        out.write("RIFF".toByteArray())
        out.write(intLe(36 + dataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intLe(16))
        out.write(shortLe(1)) // PCM
        out.write(shortLe(1)) // mono
        out.write(intLe(sampleRate))
        out.write(intLe(byteRate))
        out.write(shortLe(2))
        out.write(shortLe(16))
        out.write("data".toByteArray())
        out.write(intLe(dataLen))
        out.write(pcmBytes)
        return out.toByteArray()
    }

    private fun intLe(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortLe(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}
