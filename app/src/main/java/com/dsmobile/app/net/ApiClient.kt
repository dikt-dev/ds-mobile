package com.dsmobile.app.net

import com.dsmobile.app.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class EventPage(val messages: List<MessageEntity>, val latestId: Long, val hasMore: Boolean)

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

    suspend fun chat(text: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", text))
        val body = JSONObject()
            .put("messages", messages)
            .put("stream", false)
            .toString()
        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer ${requireToken()}")
            .header("X-Client-Channel", "mobile")
            .post(body.toRequestBody(jsonType))
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
    suspend fun chatStream(text: String, onDelta: suspend (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", text))
            val body = JSONObject()
                .put("messages", messages)
                .put("stream", true)
                .toString()
            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer ${requireToken()}")
                .header("X-Client-Channel", "mobile")
                .post(body.toRequestBody(jsonType))
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

    private fun execute(request: Request) = client.newCall(request).execute()

    private fun requireToken(): String = token ?: throw ApiException("设备未配对")

    private fun errorText(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return "HTTP $code" + if (msg.isBlank()) "" else "：$msg"
    }
}
