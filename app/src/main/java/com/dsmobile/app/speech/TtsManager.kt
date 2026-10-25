package com.dsmobile.app.speech

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.dsmobile.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale

/** Azure TTS 优先，失败/未配置时回退 Android 本地 TTS。 */
object TtsManager {
    private const val TAG = "TtsManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var player: MediaPlayer? = null
    private var local: TextToSpeech? = null

    fun speak(context: Context, settings: SettingsStore, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val key = settings.azureKey
        if (key.isBlank()) {
            speakLocal(context, clean)
            return
        }
        scope.launch {
            try {
                val bytes = azureTts(key, settings.azureRegion, settings.azureVoice, clean)
                Log.i(TAG, "azure bytes=${bytes.size}")
                val file = File(context.cacheDir, "tts-${System.currentTimeMillis()}.wav")
                file.writeBytes(bytes)
                withContext(Dispatchers.Main) { play(context, file, clean) }
            } catch (e: Exception) {
                Log.w(TAG, "azure tts failed: ${e.message}")
                withContext(Dispatchers.Main) { speakLocal(context, clean) }
            }
        }
    }

    private fun azureTts(key: String, region: String, voice: String, text: String): ByteArray {
        val ssml = """
            <speak version='1.0' xml:lang='zh-CN'>
              <voice xml:lang='zh-CN' name='${escape(voice)}'>
                <prosody rate='+5%' pitch='+3Hz'>${escape(text)}</prosody>
              </voice>
            </speak>
        """.trimIndent()
        val request = Request.Builder()
            .url("https://${region.trim()}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key)
            .header("Content-Type", "application/ssml+xml")
            .header("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm")
            .header("User-Agent", "DS-Mobile")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Azure TTS HTTP ${response.code}")
            }
            return response.body?.bytes() ?: throw IllegalStateException("TTS 返回空")
        }
    }

    private fun play(context: Context, file: File, text: String) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    file.delete()
                    player = null
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "play failed: ${e.message}")
            file.delete()
            speakLocal(context, text)
        }
    }

    private fun speakLocal(context: Context, text: String) {
        if (text.isBlank()) return
        try {
            if (local == null) {
                local = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        local?.language = Locale.SIMPLIFIED_CHINESE
                    }
                }
            }
            local?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ds-tts")
        } catch (e: Exception) {
            Log.w(TAG, "local tts failed: ${e.message}")
        }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
        .replace("\"", "&quot;")
}
