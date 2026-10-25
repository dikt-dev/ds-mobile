package com.dsmobile.app.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dsmobile.app.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class EventPage(val messages: List<MessageEntity>, val latestId: Long, val hasMore: Boolean)

data class BiliPreview(
    val title: String,
    val owner: String,
    val duration: Int,
    val desc: String = "",
)

class ApiException(message: String) : Exception(message)

class ApiClient(
    private val baseUrl: String,
    private val token: String? = null,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun pair(pairCode: String, deviceName: String, deviceId: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("pair_code", pairCode)
                .put("device_name", deviceName)
                .put("device_id", deviceId)
                .toString()
            val request = Request.Builder()
                .url("$baseUrl/api/app/pair")
                .post(body.toRequestBody(jsonType))
                .build()
            execute(request).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(errorText(response.code, text))
                JSONObject(text).optString("token")
                    .ifBlank { throw ApiException("配对响应缺少 token") }
            }
        }

    suspend fun events(afterId: Long, limit: Int = 100, latest: Boolean = false): EventPage =
        withContext(Dispatchers.IO) {
            val suffix = if (latest) "&latest=1" else ""
            val request = Request.Builder()
                .url("$baseUrl/api/app/events?after_id=$afterId&limit=$limit$suffix")
                .header("Authorization", "Bearer ${requireToken()}")
                .get()
                .build()
            execute(request).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(errorText(response.code, text))
                val root = JSONObject(text)
                val rows = root.optJSONArray("messages") ?: JSONArray()
                val messages = buildList {
                    for (i in 0 until rows.length()) {
                        val m = rows.getJSONObject(i)
                        add(
                            MessageEntity(
                                id = m.optLong("id"),
                                role = m.optString("role"),
                                content = m.optString("content"),
                                channel = m.optString("channel"),
                                createdAt = m.optString("created_at"),
                                turnId = if (m.isNull("turn_id")) null else m.optLong("turn_id"),
                                proactiveKind = if (m.isNull("proactive_kind")) null else m.optString("proactive_kind"),
                            )
                        )
                    }
                }
                EventPage(messages, root.optLong("latest_id", afterId), root.optBoolean("has_more"))
            }
        }

    suspend fun chat(text: String, clientMessageId: String? = null): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", text))
        val body = JSONObject()
            .put("messages", messages)
            .put("stream", false)
        if (!clientMessageId.isNullOrBlank()) body.put("client_message_id", clientMessageId)
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
            JSONObject(textBody)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiException("回复为空")
        }
    }

    /** SSE 流式回复；每段 delta 回调一次，返回完整文本。 */
    suspend fun chatStream(
        text: String,
        clientMessageId: String? = null,
        onDelta: suspend (String) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", text))
            val body = JSONObject()
                .put("messages", messages)
                .put("stream", true)
            if (!clientMessageId.isNullOrBlank()) body.put("client_message_id", clientMessageId)
            val bodyText = body.toString()
            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer ${requireToken()}")
                .header("X-Client-Channel", "mobile")
            .header("X-Client-Voice", "1")
                .post(bodyText.toRequestBody(jsonType))
                .build()
            execute(request).use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(errorText(response.code, response.body?.string().orEmpty()))
                }
                val source = response.body?.source() ?: throw ApiException("回复为空")
                val builder = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue
                    val delta = runCatching {
                        JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                    }.getOrDefault("")
                    if (delta.isNotEmpty()) {
                        builder.append(delta)
                        onDelta(delta)
                    }
                }
                builder.toString().ifBlank { throw ApiException("回复为空") }
            }
        }

    suspend fun image(key: String): ByteArray = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(key, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/app/image?key=$encoded")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) throw ApiException(errorText(response.code, ""))
            bytes
        }
    }

    suspend fun chatWithImage(
        text: String,
        dataUrl: String,
        clientMessageId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val parts = JSONArray()
        if (text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", text))
        parts.put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", dataUrl)),
        )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", parts))
        val body = JSONObject().put("messages", messages).put("stream", false)
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
            JSONObject(textBody)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiException("回复为空")
        }
    }

    suspend fun biliPreview(url: String): BiliPreview? = withContext(Dispatchers.IO) {
        directBiliPreview(url) ?: workerBiliPreview(url)
    }

    /** 手机直连 B 站通常比 Worker 出口更稳；失败再走 Worker。 */
    private fun directBiliPreview(url: String): BiliPreview? {
        return try {
            val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"
            var finalUrl = url
            if (url.contains("b23.tv")) {
                val redirect = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Referer", "https://www.bilibili.com/")
                    .get()
                    .build()
                execute(redirect).use { response ->
                    finalUrl = response.request.url.toString()
                }
            }
            val bvid = Regex("""BV[0-9A-Za-z]{10}""").find(finalUrl)?.value ?: return null
            val request = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                .header("User-Agent", ua)
                .header("Referer", "https://www.bilibili.com/")
                .header("Accept", "application/json")
                .get()
                .build()
            execute(request).use { response ->
                if (!response.isSuccessful) return null
                val root = JSONObject(response.body?.string().orEmpty())
                if (root.optInt("code") != 0) return null
                val data = root.optJSONObject("data") ?: return null
                BiliPreview(
                    title = data.optString("title"),
                    owner = data.optJSONObject("owner")?.optString("name").orEmpty(),
                    duration = data.optInt("duration"),
                    desc = data.optString("desc").replace(Regex("\\s+"), " ").take(500),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun workerBiliPreview(url: String): BiliPreview? = runCatching {
        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/app/link-preview?url=$encoded")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) return null
            val root = JSONObject(response.body?.string().orEmpty())
            BiliPreview(
                title = root.optString("title"),
                owner = root.optString("owner"),
                duration = root.optInt("duration"),
                desc = root.optString("desc"),
            )
        }
    }.getOrNull()

    suspend fun uploadMedia(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
        if (size > 60L * 1024 * 1024) throw ApiException("文件超过 60MB")
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input ->
                    input.source().use { source -> sink.writeAll(source) }
                } ?: throw ApiException("无法读取文件")
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/api/app/upload/video")
            .header("Authorization", "Bearer ${requireToken()}")
            .post(body)
            .build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(errorText(response.code, text))
            JSONObject(text).optString("key").ifBlank { throw ApiException("上传响应缺少 key") }
        }
    }

    fun mediaUrl(key: String): String =
        "$baseUrl/api/app/media?key=${java.net.URLEncoder.encode(key, "UTF-8")}"

    suspend fun mediaBytes(key: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(mediaUrl(key))
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw ApiException("HTTP ${response.code}")
            response.body?.bytes() ?: throw ApiException("媒体为空")
        }
    }

    fun mediaHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${requireToken()}")

    private fun execute(request: Request) = client.newCall(request).execute()

    private fun requireToken(): String = token ?: throw ApiException("设备未配对")

    private fun errorText(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return "HTTP $code" + if (msg.isBlank()) "" else "：$msg"
    }
}
