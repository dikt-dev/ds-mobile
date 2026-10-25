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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Azure TTS 优先，失败/未配置时回退 Android 本地 TTS。 */
object TtsManager {
    private const val TAG = "TtsManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val cacheLock = Mutex()
    private var player: MediaPlayer? = null
    private var local: TextToSpeech? = null

    /** 消息到达时先合成，用户点语音条时尽量直接命中缓存。 */
    fun prefetch(context: Context, settings: SettingsStore, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || settings.azureKey.isBlank()) return
        scope.launch {
            try {
                ensureAudio(context, settings, clean)
            } catch (e: Exception) {
                Log.w(TAG, "prefetch failed: ${e.message}")
            }
        }
    }

    fun speak(context: Context, settings: SettingsStore, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val key = settings.azureKey
        if (key.isBlank()) {
            speakLocal(context, clean)
            return
        }
        val cached = cacheFile(context, settings, clean)
        if (cached.exists() && cached.length() > 0) {
            Log.i(TAG, "cache hit ${cached.name}")
            play(context, cached)
            return
        }
        scope.launch {
            try {
                val file = ensureAudio(context, settings, clean)
                withContext(Dispatchers.Main) { play(context, file) }
            } catch (e: Exception) {
                Log.w(TAG, "azure tts failed: ${e.message}")
                withContext(Dispatchers.Main) { speakLocal(context, clean) }
            }
        }
    }

    /** 先把语音合成好，慢一点没关系，等分段输出前用。 */
    suspend fun prepare(context: Context, settings: SettingsStore, text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || settings.azureKey.isBlank()) return true
        return runCatching {
            ensureAudio(context, settings, clean)
            true
        }.getOrDefault(false)
    }

    private suspend fun ensureAudio(
        context: Context,
        settings: SettingsStore,
        text: String,
    ): File = cacheLock.withLock {
        val target = cacheFile(context, settings, text)
        if (target.exists() && target.length() > 0) return@withLock target

        val start = System.currentTimeMillis()
        val style = styleFor(text)
        val token = settings.token
        val bytes = if (!token.isNullOrBlank()) {
            try {
                workerTts(settings, token, text, style)
            } catch (e: Exception) {
                Log.w(TAG, "worker tts failed, fallback direct: ${e.message}")
                if (settings.azureKey.isBlank()) throw e
                azureTts(
                    key = settings.azureKey,
                    region = settings.azureRegion,
                    voice = settings.azureVoice,
                    rate = settings.azureRate,
                    pitch = settings.azurePitch,
                    styleDegree = settings.azureStyleDegree,
                    style = style,
                    text = text,
                )
            }
        } else {
            azureTts(
                key = settings.azureKey,
                region = settings.azureRegion,
                voice = settings.azureVoice,
                rate = settings.azureRate,
                pitch = settings.azurePitch,
                styleDegree = settings.azureStyleDegree,
                style = style,
                text = text,
            )
        }
        val fetched = System.currentTimeMillis()
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
        Log.i(
            TAG,
            "tts ${bytes.size}B, network ${fetched - start}ms, total ${System.currentTimeMillis() - start}ms",
        )
        trimCache(context)
        target
    }

    private fun cacheFile(context: Context, settings: SettingsStore, text: String): File {
        val raw = listOf(
            settings.azureVoice,
            settings.azureRate,
            settings.azurePitch,
            settings.azureStyleDegree,
            text,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }.take(32) + ".mp3"
        return File(File(context.cacheDir, "tts-cache"), name)
    }

    private fun trimCache(context: Context) {
        val dir = File(context.cacheDir, "tts-cache")
        val files = dir.listFiles()?.filter { it.extension == "mp3" } ?: return
        if (files.size <= 30) return
        files.sortedBy { it.lastModified() }.take(files.size - 30).forEach { it.delete() }
    }

    private fun workerTts(
        settings: SettingsStore,
        token: String,
        text: String,
        style: String,
    ): ByteArray {
        val body = org.json.JSONObject().apply {
            put("text", text)
            put("voice", settings.azureVoice)
            put("rate", settings.azureRate)
            put("pitch", settings.azurePitch)
            put("style", style)
            put("style_degree", settings.azureStyleDegree)
        }.toString()
        val request = Request.Builder()
            .url("${settings.baseUrl.trimEnd('/')}/api/app/tts")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Worker TTS HTTP ${response.code}")
            }
            return response.body?.bytes() ?: throw IllegalStateException("TTS 返回空")
        }
    }

    private fun azureTts(
        key: String,
        region: String,
        voice: String,
        rate: String,
        pitch: String,
        styleDegree: String,
        style: String,
        text: String,
    ): ByteArray {
        val inner =
            "<prosody rate='${escape(rate)}' pitch='${escape(pitch)}'>${escape(text)}</prosody>"
        val body = if (style.isBlank()) {
            inner
        } else {
            "<mstts:express-as style='${escape(style)}' styledegree='${escape(styleDegree)}'>" +
                inner + "</mstts:express-as>"
        }
        val ssml = """
            <speak version='1.0' xml:lang='zh-CN' xmlns:mstts='https://www.w3.org/2001/mstts'>
              <voice xml:lang='zh-CN' name='${escape(voice)}'>
                $body
              </voice>
            </speak>
        """.trimIndent()
        val request = Request.Builder()
            .url("https://${region.trim()}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key)
            .header("Content-Type", "application/ssml+xml")
            // MP3 比 24k PCM WAV 小一个数量级，跨境链路下能明显缩短等待。
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
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

    /** 从 tts_router.py 迁移的情绪 style 映射。 */
    private fun styleFor(text: String): String = when {
        listOf("傲娇", "毒舌", "嘴硬", "别扭", "嫌弃", "不屑", "冷哼").any { text.contains(it) } ->
            "disgruntled"
        listOf("炸毛", "生气", "凶", "恼火").any { text.contains(it) } -> "angry"
        listOf("心软", "撒娇", "温柔", "喜欢").any { text.contains(it) } -> "affectionate"
        listOf("委屈", "难过", "伤心", "哭").any { text.contains(it) } -> "sad"
        listOf("得意", "开心", "高兴", "兴奋", "活泼", "笑").any { text.contains(it) } -> "cheerful"
        else -> ""
    }

    private fun play(context: Context, file: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    player = null
                }
                prepare()
                start()
            }
            Log.i(TAG, "playing ${file.name} (${file.length()}B)")
        } catch (e: Exception) {
            Log.w(TAG, "play failed: ${e.message}")
            speakLocal(context, "")
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
