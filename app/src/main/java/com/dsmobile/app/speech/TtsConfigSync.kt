package com.dsmobile.app.speech

import com.dsmobile.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 把手机上的语音参数同步到 Cloudflare，网页端用同一份配置合成。
 *
 * 为什么需要：网页原本靠「手机合成 → 传 R2」，手机不在线就掉到 MeloTTS，
 * 声音和 App 完全不同。参数上传后两端都走 Azure，音色才对得上。
 */
object TtsConfigSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 尽力而为：失败只记日志，绝不阻塞设置保存或聊天。 */
    fun push(settings: SettingsStore) {
        val token = settings.token ?: return
        if (token.isBlank()) return
        val body = JSONObject().apply {
            put("voice", settings.azureVoice)
            put("rate", settings.azureRate)
            put("pitch", settings.azurePitch)
            put("style_degree", settings.azureStyleDegree)
            put("style_mode", "auto")
        }.toString()
        val url = "${settings.baseUrl.trimEnd('/')}/api/app/tts-config"
        scope.launch {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.w("TtsConfigSync", "push failed HTTP ${response.code}")
                    }
                }
            }.onFailure { android.util.Log.w("TtsConfigSync", "push error: ${it.message}") }
        }
    }
}