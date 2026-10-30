package com.dsmobile.app.net

import android.content.Context
import android.net.Uri
import android.util.Log
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
import java.io.File
import java.util.concurrent.TimeUnit

/** 服务端允许“这轮不回复”时返回的哨兵，不是要显示的文本。 */
const val NO_REPLY = "[END]"

data class EventPage(val messages: List<MessageEntity>, val latestId: Long, val hasMore: Boolean)

data class ProactivePush(
    val queueId: Int,
    val content: String,
    val messageId: Long?,
    val createdAt: String,
)

data class BiliPreview(
    val title: String,
    val owner: String,
    val duration: Int,
    val desc: String = "",
    val summary: String = "",
    val outline: List<String> = emptyList(),
    val subtitle: String = "",
    val subtitleCount: Int = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val playUrl: String = "",
    val storyboardUrl: String = "",
    val storyboardCols: Int = 0,
    val storyboardRows: Int = 0,
    /** videoshot 返回的每帧时间点（秒），比 cols×rows 可靠：末行常有空白格 */
    val storyboardIndex: List<Double> = emptyList(),
    /** videoshot 的 image[]：长视频会被切成多张雪碧图 */
    val storyboardUrls: List<String> = emptyList(),
    val cover: String = "",
)

data class WebPreview(
    val title: String,
    val description: String,
    val site: String,
    val douyin: Boolean,
)

private val BILI_MIXIN_TAB = intArrayOf(
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
)

class ApiException(message: String) : Exception(message)

/** 字幕时间戳：m:ss，超过一小时用 h:mm:ss。 */
private fun biliClock(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}

class ApiClient(
    private val baseUrl: String,
    private val token: String? = null,
    private val biliSessdata: String = "",
    private val biliCookie: String = "",
    cacheDir: File? = null,
) {
    private val diskCache = cacheDir?.let { MediaDiskCache(it) }
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

    /** App 心跳：让 Worker 知道移动端在线，避免误报通道掉线。 */
    suspend fun heartbeat(version: String = "ds-mobile") = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("connected", true)
            .put("version", version)
        val request = Request.Builder()
            .url("$baseUrl/api/app/heartbeat")
            .header("Authorization", "Bearer ${requireToken()}")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw ApiException(errorText(response.code, response.body?.string().orEmpty()))
            }
        }
    }

    /** 显式纠正长期记忆：用户发送 /更正 ... 时调用。 */
    suspend fun correctMemory(text: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text)
        val request = Request.Builder()
            .url("$baseUrl/api/app/memory/correct")
            .header("Authorization", "Bearer ${requireToken()}")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw ApiException(errorText(response.code, response.body?.string().orEmpty()))
            }
        }
    }

    /** 用户确认后更新当前位置；服务端写入记忆并记录原因。 */
    suspend fun updateLocation(latitude: Double, longitude: Double, accuracy: Float, reason: String) =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("latitude", latitude)
                .put("longitude", longitude)
                .put("accuracy", accuracy.toDouble())
                .put("reason", reason.take(120))
            val request = Request.Builder()
                .url("$baseUrl/api/app/location")
                .header("Authorization", "Bearer ${requireToken()}")
                .post(body.toString().toRequestBody(jsonType))
                .build()
            execute(request).use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(errorText(response.code, response.body?.string().orEmpty()))
                }
            }
        }

    /** App 拉主动消息队列：先本地通知，再 ACK 清队列。 */
    suspend fun proactivePending(limit: Int = 20): List<ProactivePush> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/proactive/pending?limit=$limit")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(errorText(response.code, text))
            val rows = JSONObject(text).optJSONArray("messages") ?: JSONArray()
            buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optLong("id", 0L)
                    if (id <= 0L) continue
                    add(
                        ProactivePush(
                            queueId = id.toInt(),
                            content = row.optString("content"),
                            messageId = if (row.isNull("message_id")) null else row.optLong("message_id"),
                            createdAt = row.optString("created_at"),
                        )
                    )
                }
            }
        }
    }

    suspend fun proactiveAck(ids: List<Int>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val body = JSONObject().put("ids", JSONArray(ids))
        val request = Request.Builder()
            .url("$baseUrl/api/proactive/ack")
            .header("Authorization", "Bearer ${requireToken()}")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw ApiException(errorText(response.code, response.body?.string().orEmpty()))
        }
    }

    suspend fun events(
        afterId: Long,
        limit: Int = 100,
        latest: Boolean = false,
        beforeId: Long? = null,
    ): EventPage =
        withContext(Dispatchers.IO) {
            val suffix = when {
                latest -> "&latest=1"
                beforeId != null && beforeId > 0 -> "&before_id=$beforeId"
                else -> ""
            }
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
                                clientMessageId = m.optString("client_message_id")
                                    .takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
                EventPage(messages, root.optLong("latest_id", afterId), root.optBoolean("has_more"))
            }
        }

    /** 按 client_message_id 对账：服务端已落库的 pending 不再重复显示/发送。 */
    suspend fun clientMessages(ids: List<String>): List<MessageEntity> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(ids.distinct().take(20).joinToString(","), "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/app/client-messages?ids=$encoded")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(errorText(response.code, text))
            val rows = JSONObject(text).optJSONArray("messages") ?: JSONArray()
            buildList {
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
                            clientMessageId = m.optString("client_message_id")
                                .takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }
    }

    suspend fun chat(
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
            if (obj.optBoolean("no_reply", false)) {
                return@withContext NO_REPLY
            }
            val content = obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiException("回复为空")
            val cleaned = content.replace(NO_REPLY, "").trim()
            if (cleaned.isBlank()) NO_REPLY else cleaned
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
                .put("inject_history", true)
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
                val result = builder.toString().replace(NO_REPLY, "").trim()
                if (result.isBlank()) NO_REPLY else result
            }
        }

    suspend fun image(key: String): ByteArray = withContext(Dispatchers.IO) {
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

    suspend fun stt(wav: ByteArray, azureKey: String, region: String): String = withContext(Dispatchers.IO) {
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

    suspend fun chatWithImage(
        text: String,
        dataUrl: String,
        clientMessageId: String? = null,
    ): String = chatWithImages(text, listOf(dataUrl), clientMessageId, transient = false)

    suspend fun chatWithImages(
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
            if (obj.optBoolean("no_reply", false)) {
                return@withContext NO_REPLY
            }
            val content = obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiException("回复为空")
            val cleaned = content.replace(NO_REPLY, "").trim()
            if (cleaned.isBlank()) NO_REPLY else cleaned
        }
    }

    /**
     * 现取一条可用的 MP4 直链。缓存里的 playUrl 带 deadline，放久了会 403，
     * 发视频前重新要一次（一次 API，几百毫秒），比下载失败再回退雪碧图划算。
     */
    suspend fun freshPlayUrl(bvid: String, cid: Long, cookie: String): String = withContext(Dispatchers.IO) {
        if (bvid.isBlank() || cid <= 0) return@withContext ""
        runCatching {
            val url = "https://api.bilibili.com/x/player/playurl" +
                "?bvid=$bvid&cid=$cid&qn=64&fnval=1&fnver=0&fourk=0"
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                .header("Referer", "https://www.bilibili.com/")
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .get().build()
            execute(req).use { resp ->
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONObject("data")
                val quality = data?.optInt("quality") ?: 0
                val link = data?.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty()
                android.util.Log.i("BiliApi", "fresh playurl quality=$quality ok=" + link.isNotBlank())
                link
            }
        }.getOrDefault("")
    }

    suspend fun biliImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val normalized = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            else -> url
        }
        val key = "bili-cover:$normalized"
        diskCache?.get(key)?.let { return@withContext it }
        val request = Request.Builder()
            .url(normalized)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .apply {
                val cookie = biliCookie.ifBlank {
                    if (biliSessdata.isNotBlank()) "SESSDATA=$biliSessdata" else ""
                }
                if (cookie.isNotBlank()) header("Cookie", cookie)
            }
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) return@withContext null
            val bytes = response.body?.bytes() ?: return@withContext null
            diskCache?.put(key, bytes)
            bytes
        }
    }

    suspend fun biliPreview(url: String): BiliPreview? = withContext(Dispatchers.IO) {
        cachedBiliPreview(url)?.let { return@withContext it }
        val direct = directBiliPreview(url)
        val worker = if (direct?.summary.isNullOrBlank()) workerBiliPreview(url) else null
        val result = when {
            direct == null -> worker
            worker == null -> direct
            else -> direct.copy(
                summary = worker.summary,
                outline = worker.outline,
                subtitle = worker.subtitle,
                subtitleCount = worker.subtitleCount,
            )
        }
        result?.let { saveBiliPreview(url, it) }
        result
    }

    /** 手机直连 B 站通常比 Worker 出口更稳；失败再走 Worker。 */
    private fun directBiliPreview(url: String): BiliPreview? {
        return try {
            val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"
            val headers = mutableMapOf(
                "User-Agent" to ua,
                "Referer" to "https://www.bilibili.com/",
                "Accept" to "application/json",
            )
            val cookie = biliCookie.ifBlank {
                if (biliSessdata.isNotBlank()) "SESSDATA=$biliSessdata" else ""
            }
            if (cookie.isNotBlank()) headers["Cookie"] = cookie
            Log.i(
                "BiliPreview",
                "direct preview cookie=" + cookie.isNotBlank() +
                    " sess=" + biliSessdata.isNotBlank() +
                    " fullLen=" + cookie.length,
            )
            var finalUrl = url
            if (url.contains("b23.tv")) {
                val redirect = Request.Builder()
                    .url(url)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .get()
                    .build()
                execute(redirect).use { response ->
                    finalUrl = response.request.url.toString()
                }
            }
            val bvid = Regex("""BV[0-9A-Za-z]{10}""").find(finalUrl)?.value ?: return null
            val request = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            execute(request).use { response ->
                if (!response.isSuccessful) return null
                val root = JSONObject(response.body?.string().orEmpty())
                if (root.optInt("code") != 0) return null
                val data = root.optJSONObject("data") ?: return null
                val aid = data.optLong("aid")
                val cid = data.optLong("cid").takeIf { it > 0 }
                    ?: data.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid")
                    ?: 0L
                val upMid = data.optJSONObject("owner")?.optLong("mid") ?: 0L
                var summary = ""
                var outline = emptyList<String>()
                var subtitle = ""
                var subtitleCount = 0
                var playUrl = ""
                var storyboardUrl = ""
                var storyboardCols = 0
                var storyboardRows = 0
                var storyboardIndex: List<Double> = emptyList()
                var storyboardUrls: List<String> = emptyList()
                if (cid > 0) {
                    runCatching {
                        // qn=64 是 720p：只要登录（SESSDATA）就能拿，不需要大会员；1080p 才要大会员。
                        // 服务器给不到就自动降级，拿到的 quality 记日志方便核对。
                        val u = "https://api.bilibili.com/x/player/playurl" +
                            "?bvid=$bvid&cid=$cid&qn=64&fnval=1&fnver=0&fourk=0"
                        val playReq = Request.Builder().url(u)
                            .apply { headers.forEach { (k, v) -> header(k, v) } }
                            .get().build()
                        execute(playReq).use { pr ->
                            val data = JSONObject(pr.body?.string().orEmpty()).optJSONObject("data")
                            val quality = data?.optInt("quality") ?: 0
                            playUrl = data
                                ?.optJSONArray("durl")
                                ?.optJSONObject(0)
                                ?.optString("url")
                                .orEmpty()
                            android.util.Log.i(
                                "BiliApi",
                                "playurl quality=$quality url=" + (if (playUrl.isBlank()) "none" else "ok"),
                            )
                        }
                    }
                    runCatching {
                        val u = "https://api.bilibili.com/x/player/videoshot" +
                            "?bvid=$bvid&cid=$cid&index=1"
                        val shotReq = Request.Builder().url(u)
                            .apply { headers.forEach { (k, v) -> header(k, v) } }
                            .get().build()
                        execute(shotReq).use { sr ->
                            val data = JSONObject(sr.body?.string().orEmpty()).optJSONObject("data")
                            storyboardUrls = data?.optJSONArray("image")?.let { arr ->
                                (0 until arr.length()).mapNotNull { i ->
                                    arr.optString(i).takeIf { it.isNotBlank() }
                                        ?.let { if (it.startsWith("//")) "https:$it" else it }
                                }
                            } ?: emptyList()
                            storyboardUrl = storyboardUrls.firstOrNull().orEmpty()
                            storyboardCols = data?.optInt("img_x_len") ?: 0
                            storyboardRows = data?.optInt("img_y_len") ?: 0
                            storyboardIndex = data?.optJSONArray("index")?.let { arr ->
                                (0 until arr.length()).map { arr.optDouble(it, 0.0) }
                            } ?: emptyList()
                        }
                    }
                }
                if (biliSessdata.isNotBlank() && cid > 0) {
                    val mixin = biliMixinKey(headers)
                    if (mixin != null) {
                        runCatching {
                            val query = biliSign(
                                mapOf("aid" to aid.toString(), "cid" to cid.toString()),
                                mixin,
                            )
                            val subReq = Request.Builder()
                                .url("https://api.bilibili.com/x/player/wbi/v2?$query")
                                .apply { headers.forEach { (k, v) -> header(k, v) } }
                                .get()
                                .build()
                            execute(subReq).use { sr ->
                                val subs = JSONObject(sr.body?.string().orEmpty())
                                    .optJSONObject("data")
                                    ?.optJSONObject("subtitle")
                                    ?.optJSONArray("subtitles")
                                val candidates = (0 until (subs?.length() ?: 0))
                                    .map { subs!!.optJSONObject(it) }
                                val chosen = candidates.firstOrNull {
                                    it.optString("lan").startsWith("zh") || it.optString("lan").startsWith("ai")
                                } ?: candidates.firstOrNull()
                                val subUrl = chosen?.optString("subtitle_url").orEmpty()
                                if (subUrl.isNotBlank()) {
                                    val full = if (subUrl.startsWith("//")) "https:$subUrl" else subUrl
                                    val bodyReq = Request.Builder().url(full)
                                        .apply { headers.forEach { (k, v) -> header(k, v) } }
                                        .get()
                                        .build()
                                    execute(bodyReq).use { br ->
                                        val body = JSONObject(br.body?.string().orEmpty())
                                            .optJSONArray("body")
                                        // 带上时间戳：宫格上有每格的秒数，字幕没有时间就永远对不上，
                                        // 模型只能整体感觉「大概是讲这个」，于是细节容易张冠李戴。
                                        val lines = (0 until (body?.length() ?: 0))
                                            .mapNotNull { idx ->
                                                val item = body!!.optJSONObject(idx)
                                                val text = item.optString("content").trim()
                                                if (text.isEmpty()) null
                                                else biliClock(item.optDouble("from", 0.0)) + " " + text
                                            }
                                        subtitleCount = lines.size
                                        subtitle = lines.joinToString("\n")
                                    }
                                }
                            }
                        }
                        runCatching {
                            val query = biliSign(
                                mapOf(
                                    "bvid" to bvid,
                                    "cid" to cid.toString(),
                                    "up_mid" to upMid.toString(),
                                ),
                                mixin,
                            )
                            val sumReq = Request.Builder()
                                .url("https://api.bilibili.com/x/web-interface/view/conclusion/get?$query")
                                .apply { headers.forEach { (k, v) -> header(k, v) } }
                                .get()
                                .build()
                            execute(sumReq).use { sr ->
                                val raw = sr.body?.string().orEmpty()
                                val root = runCatching { JSONObject(raw) }.getOrNull()
                                // 只记 code/message，不记 cookie、不记正文
                                Log.i(
                                    "BiliPreview",
                                    "conclusion http=" + sr.code +
                                        " code=" + (root?.optInt("code") ?: -999) +
                                        " msg=" + root?.optString("message").orEmpty(),
                                )
                                val dataObj = root?.optJSONObject("data")
                                Log.i(
                                    "BiliPreview",
                                    "conclusion data=" + (dataObj != null) +
                                        " result_type=" + (dataObj?.optInt("result_type") ?: -1) +
                                        " hasModel=" + (dataObj?.optJSONObject("model_result") != null) +
                                        " sumLen=" + (dataObj?.optJSONObject("model_result")
                                            ?.optString("summary")?.length ?: -1) +
                                        " outlineLen=" + (dataObj?.optJSONObject("model_result")
                                            ?.optJSONArray("outline")?.length() ?: -1),
                                )
                                val model = dataObj?.optJSONObject("model_result")
                                summary = model?.optString("summary").orEmpty().trim()
                                val arr = model?.optJSONArray("outline")
                                val points = mutableListOf<String>()
                                for (i in 0 until (arr?.length() ?: 0)) {
                                    val item = arr!!.optJSONObject(i)
                                    val t = item.optString("title").trim()
                                    if (t.isNotEmpty()) points.add(t)
                                    val parts = item.optJSONArray("part_outline")
                                    for (j in 0 until (parts?.length() ?: 0)) {
                                        // B 站这里有时返回 {"timestamp":3,"content":"…"} 对象，
                                        // 直接 optString 会把整段 JSON 塞进提示词。
                                        val rawPart = parts!!.opt(j)
                                        val p = when (rawPart) {
                                            is JSONObject -> rawPart.optString("content").trim()
                                            else -> rawPart?.toString()?.trim().orEmpty()
                                        }
                                        if (p.isNotEmpty() && !p.startsWith("{")) points.add("  · $p")
                                    }
                                    if (points.size >= 12) break
                                }
                                outline = points.take(12)
                            }
                        }
                    }
                }
                BiliPreview(
                    title = data.optString("title"),
                    owner = data.optJSONObject("owner")?.optString("name").orEmpty(),
                    duration = data.optInt("duration"),
                    desc = data.optString("desc").replace(Regex("\\s+"), " ").take(500),
                    summary = summary,
                    outline = outline,
                    subtitle = subtitle,
                    subtitleCount = subtitleCount,
                    bvid = bvid,
                    cid = cid,
                    playUrl = playUrl,
                    storyboardUrl = storyboardUrl,
                    storyboardCols = storyboardCols,
                    storyboardRows = storyboardRows,
                    storyboardIndex = storyboardIndex,
                    storyboardUrls = storyboardUrls,
                    cover = data.optString("pic").let {
                        if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://")
                    },
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun biliMixinKey(headers: Map<String, String>): String? {
        return runCatching {
            val req = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/nav")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            execute(req).use { response ->
                val wbi = JSONObject(response.body?.string().orEmpty())
                    .optJSONObject("data")
                    ?.optJSONObject("wbi_img") ?: return null
                val img = wbi.optString("img_url").substringAfterLast('/').substringBefore('.')
                val sub = wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
                val orig = img + sub
                BILI_MIXIN_TAB.map { orig.getOrNull(it)?.toString() ?: "" }.joinToString("").take(32)
            }
        }.getOrNull()
    }

    private fun biliSign(params: Map<String, String>, mixinKey: String): String {
        val wts = (System.currentTimeMillis() / 1000).toString()
        val cleaned = (params + ("wts" to wts))
            .mapValues { it.value.replace(Regex("[!'()*]"), "") }
            .toSortedMap()
        val query = cleaned.entries.joinToString("&") {
            "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "$query&w_rid=${md5(query + mixinKey)}"
    }

    private fun md5(value: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
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
                summary = root.optString("summary"),
                outline = root.optJSONArray("outline")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
                subtitle = root.optString("subtitle"),
                subtitleCount = root.optInt("subtitle_count"),
                cover = root.optString("cover"),
            )
        }
    }.getOrNull()

    suspend fun webPreview(url: String): WebPreview? = withContext(Dispatchers.IO) {
        cachedWebPreview(url)?.let { return@withContext it }
        val result = runCatching {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/app/web-preview?url=$encoded")
                .header("Authorization", "Bearer ${requireToken()}")
                .get()
                .build()
            execute(request).use { response ->
                if (!response.isSuccessful) return@runCatching null
                val root = JSONObject(response.body?.string().orEmpty())
                WebPreview(
                    title = root.optString("title"),
                    description = root.optString("description"),
                    site = root.optString("site"),
                    douyin = root.optBoolean("douyin"),
                )
            }
        }.getOrNull()
        result?.let { saveWebPreview(url, it) }
        result
    }

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
        val cacheKey = "media:$key"
        diskCache?.get(cacheKey)?.let { return@withContext it }
        val request = Request.Builder()
            .url(mediaUrl(key))
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw ApiException("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw ApiException("媒体为空")
            diskCache?.put(cacheKey, bytes)
            bytes
        }
    }

    fun mediaHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${requireToken()}")

    /** 缓存键带版本：字段结构变了就自然失效，不需要清用户数据。 */
    private fun biliKey(url: String): String = "v2:" +
        "bili:${biliSessdata.hashCode()}:$url"

    private fun cachedBiliPreview(url: String): BiliPreview? {
        val bytes = diskCache?.get(biliKey(url)) ?: return null
        return runCatching {
            val o = JSONObject(String(bytes))
            if (o.optString("cover").isBlank()) return null
            val outline = o.optJSONArray("outline")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()
            BiliPreview(
                title = o.optString("title"),
                owner = o.optString("owner"),
                duration = o.optInt("duration"),
                desc = o.optString("desc"),
                summary = o.optString("summary"),
                outline = outline,
                subtitle = o.optString("subtitle"),
                subtitleCount = o.optInt("subtitle_count"),
                bvid = o.optString("bvid"),
                cid = o.optLong("cid"),
                playUrl = o.optString("play_url"),
                storyboardUrl = o.optString("storyboard_url"),
                storyboardCols = o.optInt("storyboard_cols"),
                storyboardRows = o.optInt("storyboard_rows"),
                storyboardIndex = o.optJSONArray("storyboard_index")?.let { arr ->
                    (0 until arr.length()).map { arr.optDouble(it, 0.0) }
                } ?: emptyList(),
                storyboardUrls = o.optJSONArray("storyboard_urls")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                } ?: emptyList(),
                cover = o.optString("cover"),
            )
        }.getOrNull()
    }

    private fun saveBiliPreview(url: String, p: BiliPreview) {
        val outline = JSONArray().apply { p.outline.forEach { put(it) } }
        val o = JSONObject()
            .put("title", p.title)
            .put("owner", p.owner)
            .put("duration", p.duration)
            .put("desc", p.desc)
            .put("summary", p.summary)
            .put("outline", outline)
            .put("subtitle", p.subtitle)
            .put("subtitle_count", p.subtitleCount)
            .put("bvid", p.bvid)
            .put("cid", p.cid)
            .put("play_url", p.playUrl)
            .put("storyboard_url", p.storyboardUrl)
            .put("storyboard_cols", p.storyboardCols)
            .put("storyboard_rows", p.storyboardRows)
            .put("storyboard_index", JSONArray().apply { p.storyboardIndex.forEach { put(it) } })
            .put("storyboard_urls", JSONArray().apply { p.storyboardUrls.forEach { put(it) } })
            .put("cover", p.cover)
        diskCache?.put(biliKey(url), o.toString().toByteArray())
    }

    private fun webKey(url: String): String = "web:$url"

    private fun cachedWebPreview(url: String): WebPreview? {
        val bytes = diskCache?.get(webKey(url)) ?: return null
        return runCatching {
            val o = JSONObject(String(bytes))
            WebPreview(
                title = o.optString("title"),
                description = o.optString("description"),
                site = o.optString("site"),
                douyin = o.optBoolean("douyin"),
            )
        }.getOrNull()
    }

    private fun saveWebPreview(url: String, p: WebPreview) {
        val o = JSONObject()
            .put("title", p.title)
            .put("description", p.description)
            .put("site", p.site)
            .put("douyin", p.douyin)
        diskCache?.put(webKey(url), o.toString().toByteArray())
    }

    /** 状态页：心情 / 关系 / 记忆 / 今天 / 余额，服务端只回模糊的一句话。 */
    suspend fun status(): StatusSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/app/status")
                .header("Authorization", "Bearer ${requireToken()}")
                .get()
                .build()
            execute(request).use { response ->
                if (!response.isSuccessful) return@runCatching null
                val root = JSONObject(response.body?.string().orEmpty())
                fun line(name: String): Pair<String, String> {
                    val o = root.optJSONObject(name) ?: return "" to ""
                    return o.optString("label") to o.optString("hint")
                }
                val mood = line("mood")
                val relation = line("relation")
                val memory = line("memory")
                val today = line("today")
                val bal = root.optJSONObject("balance")
                StatusSnapshot(
                    mood = mood.first, moodHint = mood.second,
                    relation = relation.first, relationHint = relation.second,
                    memory = memory.first, memoryHint = memory.second,
                    today = today.first, todayHint = today.second,
                    balance = bal?.optString("label").orEmpty(),
                    balanceHint = bal?.optString("hint").orEmpty(),
                    balanceTotal = bal?.optDouble("total", 0.0) ?: 0.0,
                    balanceCurrency = bal?.optString("currency").orEmpty(),
                )
            }
        }.getOrNull()
    }

    private fun execute(request: Request) = client.newCall(request).execute()

    private fun requireToken(): String = token ?: throw ApiException("设备未配对")

    private fun errorText(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return "HTTP $code" + if (msg.isBlank()) "" else "：$msg"
    }
}

/** 状态快照：全部是给人看的一句话，没有 0-100 的数值。 */
data class StatusSnapshot(
    val mood: String = "",
    val moodHint: String = "",
    val relation: String = "",
    val relationHint: String = "",
    val memory: String = "",
    val memoryHint: String = "",
    val today: String = "",
    val todayHint: String = "",
    val balance: String = "",
    val balanceHint: String = "",
    val balanceTotal: Double = 0.0,
    val balanceCurrency: String = "",
)
