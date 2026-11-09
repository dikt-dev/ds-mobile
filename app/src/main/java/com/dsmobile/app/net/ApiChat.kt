package com.dsmobile.app.net

import android.content.Context
import android.net.Uri
import android.util.Log
import android.provider.OpenableColumns
import com.dsmobile.app.AsrLine
import com.dsmobile.app.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal suspend fun ApiClient.chat(
    text: String,
    clientMessageId: String? = null,
    /** false = 只问不记（规划轮之类，不写 D1、不进记忆）。 */
    record: Boolean = true,
): String = withContext(Dispatchers.IO) {
    val messages = JSONArray().put(JSONObject().put("role", "user").put("content", text))
    val body = JSONObject()
        .put("messages", messages)
        .put("stream", false)
        .put("inject_history", true)
    if (!clientMessageId.isNullOrBlank()) body.put("client_message_id", clientMessageId)
    if (!record) body.put("record", false)
    val bodyText = body.toString()
    val request = Request.Builder()
        .url("$baseUrl/v1/chat/completions")
        .header("Authorization", "Bearer ${requireToken()}")
        .header("X-Client-Channel", "mobile")
        .header("X-Client-Voice", "1")
        .post(bodyText.toRequestBody(jsonType))
        .build()
    execute(request).use { response ->
        val textBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw ApiException(errorText(response.code, textBody))
        val obj = JSONObject(textBody)
        // 被闸门压住：她这会儿在睡/在忙，消息已经落库，到点会回。
        // 这不是失败，必须和「回复为空」分开，否则界面弹"发送失败"，人就会反复重发。
        if (obj.optBoolean("held", false)) {
            throw HeldException(obj.optString("until"), obj.optString("reason"), obj.optLong("until_ms", 0L))
        }
        if (obj.optBoolean("no_reply", false)) {
            return@withContext NO_REPLY
        }
        readCompletion(obj)
    }
}

internal suspend fun ApiClient.image(key: String): ByteArray = withContext(Dispatchers.IO) {
    val cacheKey = "image:$key"
    diskCache?.get(cacheKey)?.let { return@withContext it }
    val encoded = java.net.URLEncoder.encode(key, "UTF-8")
    val request = Request.Builder()
        .url("$baseUrl/api/app/image?key=$encoded")
        .header("Authorization", "Bearer ${requireToken()}")
        .get()
        .build()
    execute(request).use { response ->
        val bytes = response.body?.bytes() ?: ByteArray(0)
        if (!response.isSuccessful) throw ApiException(errorText(response.code, ""))
        diskCache?.put(cacheKey, bytes)
        bytes
    }
}

/** 日记配图：key 形如 diary-images/2026-10-31/cover.jpg。 */

internal suspend fun ApiClient.diaryImage(key: String): ByteArray = withContext(Dispatchers.IO) {
    val cacheKey = "diary-image:$key"
    diskCache?.get(cacheKey)?.let { return@withContext it }
    val request = Request.Builder()
        .url("$baseUrl/api/app/diary-image/" + key.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") })
        .header("Authorization", "Bearer ${requireToken()}")
        .get()
        .build()
    execute(request).use { response ->
        val bytes = response.body?.bytes() ?: ByteArray(0)
        if (!response.isSuccessful) throw ApiException(errorText(response.code, ""))
        diskCache?.put(cacheKey, bytes)
        bytes
    }
}

internal suspend fun ApiClient.stt(wav: ByteArray, azureKey: String, region: String): String = withContext(Dispatchers.IO) {
    if (azureKey.isBlank()) throw ApiException("未配置 Azure Speech Key")
    if (wav.size < 1024) throw ApiException("录音太短")
    val safeRegion = region.trim().ifBlank { "eastus" }
    val request = Request.Builder()
        .url(
            "https://$safeRegion.stt.speech.microsoft.com/speech/recognition/" +
                "conversation/cognitiveservices/v1?language=zh-CN",
        )
        .header("Ocp-Apim-Subscription-Key", azureKey)
        .header("Content-Type", "audio/wav; codecs=audio/pcm; samplerate=16000")
        .header("Accept", "application/json")
        .post(wav.toRequestBody("audio/wav".toMediaType()))
        .build()
    execute(request).use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw ApiException("STT HTTP ${response.code}: ${body.take(200)}")
        val json = JSONObject(body)
        val text = json.optString("DisplayText").ifBlank {
            json.optString("Text").ifBlank { json.optString("text") }
        }.trim()
        if (text.isBlank()) throw ApiException("没有识别到文字")
        text
    }
}

/** 回复正文 + 服务端这次实际落库的图片 key（用来把本地图按 key 绑定） */
data class ChatReply(val text: String, val imageKeys: List<String>)

internal suspend fun ApiClient.chatWithImageDetailed(
    text: String,
    dataUrl: String,
    clientMessageId: String? = null,
): ChatReply = chatWithImagesDetailed(text, listOf(dataUrl), clientMessageId)

/** 把本地图字节直接写进图片磁盘缓存（key 用服务端下发的 images/…）。 */

internal fun ApiClient.cacheImageBytes(key: String, bytes: ByteArray) {
    diskCache?.put("image:$key", bytes)
}

internal suspend fun ApiClient.chatWithImage(
    text: String,
    dataUrl: String,
    clientMessageId: String? = null,
): String = chatWithImages(text, listOf(dataUrl), clientMessageId, transient = false)

internal suspend fun ApiClient.chatWithImagesDetailed(
    text: String,
    dataUrls: List<String>,
    clientMessageId: String? = null,
): ChatReply = withContext(Dispatchers.IO) {
    val parts = JSONArray()
    if (text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", text))
    dataUrls.filter { it.startsWith("data:image/") }.forEach { url ->
        parts.put(
            JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", url)),
        )
    }
    val messages = JSONArray().put(JSONObject().put("role", "user").put("content", parts))
    val body = JSONObject()
        .put("messages", messages)
        .put("stream", false)
        .put("inject_history", true)
    if (!clientMessageId.isNullOrBlank()) body.put("client_message_id", clientMessageId)
    val request = Request.Builder()
        .url("$baseUrl/v1/chat/completions")
        .header("Authorization", "Bearer ${requireToken()}")
        .header("X-Client-Channel", "mobile")
        .header("X-Client-Voice", "1")
        .post(body.toString().toRequestBody(jsonType))
        .build()
    execute(request).use { response ->
        val textBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw ApiException(errorText(response.code, textBody))
        val obj = JSONObject(textBody)
        if (obj.optBoolean("held", false)) {
            throw HeldException(obj.optString("until"), obj.optString("reason"), obj.optLong("until_ms", 0L))
        }
        if (obj.optBoolean("no_reply", false)) return@withContext ChatReply(NO_REPLY, emptyList())
        val keys = obj.optJSONArray("user_image_keys")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.startsWith("images/") }
        } ?: emptyList()
        ChatReply(readCompletion(obj), keys)
    }
}

internal suspend fun ApiClient.chatWithImages(
    text: String,
    dataUrls: List<String>,
    clientMessageId: String? = null,
    transient: Boolean = false,
    /** false = 只问不记（规划轮之类，避免污染 D1 与记忆）。 */
    record: Boolean = true,
): String = withContext(Dispatchers.IO) {
    val parts = JSONArray()
    if (text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", text))
    dataUrls.filter { it.startsWith("data:image/") }.forEach { url ->
        parts.put(
            JSONObject()
                .put("type", "image_url")
                .put(
                    "image_url",
                    JSONObject().put(
                        "url",
                        if (transient) "$url#__transient" else url,
                    ),
                ),
        )
    }
    val messages = JSONArray().put(JSONObject().put("role", "user").put("content", parts))
    val body = JSONObject()
        .put("messages", messages)
        .put("stream", false)
        .put("inject_history", true)
    if (!clientMessageId.isNullOrBlank()) body.put("client_message_id", clientMessageId)
    if (!record) body.put("record", false)
    val request = Request.Builder()
        .url("$baseUrl/v1/chat/completions")
        .header("Authorization", "Bearer ${requireToken()}")
        .header("X-Client-Channel", "mobile")
        .header("X-Client-Voice", "1")
        .post(body.toString().toRequestBody(jsonType))
        .build()
    execute(request).use { response ->
        val textBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw ApiException(errorText(response.code, textBody))
        val obj = JSONObject(textBody)
        // 被闸门压住：她这会儿在睡/在忙，消息已经落库，到点会回。
        // 这不是失败，必须和「回复为空」分开，否则界面弹"发送失败"，人就会反复重发。
        if (obj.optBoolean("held", false)) {
            throw HeldException(obj.optString("until"), obj.optString("reason"), obj.optLong("until_ms", 0L))
        }
        if (obj.optBoolean("no_reply", false)) {
            return@withContext NO_REPLY
        }
        readCompletion(obj)
    }
}

/**
 * 现取一条可用的 MP4 直链。缓存里的 playUrl 带 deadline，放久了会 403，
 * 发视频前重新要一次（一次 API，几百毫秒），比下载失败再回退雪碧图划算。
 */
