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

/** 服务端允许“这轮不回复”时返回的哨兵，不是要显示的文本。 */
const val NO_REPLY = "[END]"

/** 她说到一半被 max_tokens 截断时的收尾，避免把半句话当成完整回复。 */
internal const val TRUNCATED_HINT = "……（说到一半，断了）"

/** 从 OpenAI 兼容响应里取正文；finish_reason=length 时补一句截断提示。 */
internal fun readCompletion(obj: JSONObject): String {
    val choice = obj.optJSONArray("choices")?.optJSONObject(0)
    val content = choice
        ?.optJSONObject("message")
        ?.optString("content")
        ?.takeIf { it.isNotBlank() }
        ?: throw ApiException("回复为空")
    val cleaned = content.replace(NO_REPLY, "").trim()
    if (cleaned.isBlank()) return NO_REPLY
    return if (choice?.optString("finish_reason") == "length") cleaned + TRUNCATED_HINT else cleaned
}

class ApiClient(
    internal val baseUrl: String,
    internal val token: String? = null,
    private val biliSessdata: String = "",
    private val biliCookie: String = "",
    cacheDir: File? = null,
) {
    companion object {
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 非流式请求在生成期间服务端一个字节都不发，读超时 = 总生成时间上限。
            // 服务端上限 180s，这里留 20s 余量，避免「手机报失败、服务端还在写」。
            .readTimeout(200, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 构造参数不是 val，方法体里访问不到，提一个属性出来给视频文件缓存用。 */
    internal val cacheRoot: File? = cacheDir
    internal val diskCache = cacheDir?.let { MediaDiskCache(it) }
    internal val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * 全 App 共用一个 OkHttpClient。
     *
     * 以前每个 ApiClient 实例都 new 一个：连接池是挂在 client 上的，
     * 所以 5 秒一次的轮询永远用不上 keep-alive，每次都要完整 TCP + TLS 握手，
     * 一天两万次。client 本身无状态，token/baseUrl 仍按实例传，共享是安全的。
     */
    internal val client = sharedClient

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
    /**
     * 今日主动消息计划。
     *
     * 时间由服务端按日期种子算好，客户端不复刻随机算法；拿到 slots 后
     * 在 `at - tolerance_min` 设一个 setAndAllowWhileIdle 闹钟（不需要精确闹钟权限）。
     */
    suspend fun proactivePlan(date: String? = null): ProactivePlan = withContext(Dispatchers.IO) {
        val suffix = if (date.isNullOrBlank()) "" else "?date=$date"
        val request = Request.Builder()
            .url("$baseUrl/api/proactive/plan$suffix")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(errorText(response.code, text))
            val root = JSONObject(text)
            fun parse(arr: JSONArray?): List<ProactivePlanSlot> = buildList {
                if (arr == null) return@buildList
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key")
                    if (key.isBlank()) continue
                    add(
                        ProactivePlanSlot(
                            key = key,
                            kind = o.optString("kind"),
                            at = o.optString("at"),
                            atMinutes = o.optInt("at_minutes", -1),
                            generated = o.optBoolean("generated"),
                        )
                    )
                }
            }
            ProactivePlan(
                date = root.optString("date"),
                paused = root.optBoolean("paused"),
                level = root.optString("level", "normal"),
                toleranceMin = root.optInt("tolerance_min", 15),
                notifyDeadlineMin = root.optInt("notify_deadline_min", 30),
                slots = parse(root.optJSONArray("slots")),
                fallback = parse(root.optJSONArray("fallback")),
            )
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
                                replyToId = if (m.isNull("reply_to_id")) null else m.optLong("reply_to_id"),

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
                            replyToId = if (m.isNull("reply_to_id")) null else m.optLong("reply_to_id"),
                        )
                    )
                }
            }
        }
    }

    suspend fun freshPlayUrl(bvid: String, cid: Long, cookie: String): String = withContext(Dispatchers.IO) {
        if (bvid.isBlank() || cid <= 0) return@withContext ""
        // ★ 先要 html5 的 mp4 直链：qn=64 会返回 PCDN edge 节点（*.edge.mountaintoys.cn），
        //   千问 omni 拉不动，800ms 就报 Failed to download multimodal content（2026-11-06）。
        val attempts = listOf(
            "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=32&fnval=1&fnver=0&fourk=0&platform=html5",
            "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=64&fnval=1&fnver=0&fourk=0",
        )
        var pcdnFallback = ""
        for (url in attempts) {
            val link = runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                    .get().build()
                execute(req).use { resp ->
                    val data = JSONObject(resp.body?.string().orEmpty()).optJSONObject("data")
                    val quality = data?.optInt("quality") ?: 0
                    val l = data?.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty()
                    android.util.Log.i("BiliApi", "fresh playurl quality=$quality host=" + runCatching { java.net.URI(l).host }.getOrDefault(""))
                    l
                }
            }.getOrDefault("")
            if (link.isBlank()) continue
            if (link.contains(".bilivideo.com") || link.contains(".hdslb.com")) return@withContext link
            if (pcdnFallback.isBlank()) pcdnFallback = link
        }
        pcdnFallback
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

    /**
     * 把手机解析到的 B 站资料回填给服务端（网页端发的链接 Worker 取不到）。
     * 一条消息回填一次就够；服务端会原地替换已有的 [BILI_META] 块。
     */
    suspend fun postBiliMeta(messageId: Long, url: String, p: BiliPreview): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("message_id", messageId)
                .put("url", url)
                .put("title", p.title)
                .put("owner", p.owner)
                .put("duration", p.duration)
                .put("cover", p.cover)
                .put("desc", p.summary ?: "")
            val request = Request.Builder()
                .url("$baseUrl/api/app/bili-meta")
                .header("Authorization", "Bearer ${requireToken()}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            execute(request).use { it.isSuccessful }
        }

    /** 服务端删除墓碑：本地缓存跟着删。 */
    suspend fun tombstones(): List<Long> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/app/tombstones")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) return@withContext emptyList<Long>()
            val root = JSONObject(response.body?.string() ?: "{}")
            val arr = root.optJSONArray("ids") ?: return@withContext emptyList<Long>()
            (0 until arr.length()).map { arr.optLong(it) }.filter { it > 0 }
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
                biliMixinKeyFrom(wbi)
            }
        }.getOrNull()
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

    /**
     * 视频台词识别：只传抽出来的音轨，服务端转成带时间点的台词。
     *
     * 失败不抛错——视频该发还得发，只是卡里少一段台词。
     */

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
                // 千问是另一本账（阿里云），服务端单独给一块；没配就整块为空
                val qw = root.optJSONObject("qwen")
                StatusSnapshot(
                    mood = mood.first, moodHint = mood.second,
                    relation = relation.first, relationHint = relation.second,
                    memory = memory.first, memoryHint = memory.second,
                    today = today.first, todayHint = today.second,
                    balance = bal?.optString("label").orEmpty(),
                    balanceHint = bal?.optString("hint").orEmpty(),
                    balanceTotal = bal?.optDouble("total", 0.0) ?: 0.0,
                    balanceCurrency = bal?.optString("currency").orEmpty(),
                    qwenTotal = qw?.optDouble("total", -1.0) ?: -1.0,
                    qwenSpent = qw?.optDouble("spent30d", 0.0) ?: 0.0,
                    qwenCalls = qw?.optInt("calls30d", 0) ?: 0,
                    qwenConfigured = qw?.optBoolean("configured", false) ?: false,
                )
            }
        }.getOrNull()
    }

    /** 日记列表：锁着的只有日期/天气/身体，正文压根不在这个响应里。 */
    suspend fun flushHeld(): Int = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/app/flush").post("{}".toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext 0
            val body = res.body?.string() ?: return@withContext 0
            JSONObject(body).optInt("flushed", 0)
        }
    }

    suspend fun aiStatus(): LifeStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/app/ai-status")
                .header("Authorization", "Bearer ${requireToken()}")
                .get()
                .build()
            execute(request).use { response ->
                if (!response.isSuccessful) return@runCatching null
                val root = JSONObject(response.body?.string().orEmpty())
                val cur = root.optJSONObject("current")
                val next = root.optJSONObject("next")
                LifeStatus(
                    status = root.optString("status").ifBlank { root.optString("state", "free") },
                    currentTitle = cur?.optString("title").orEmpty(),
                    currentEnd = cur?.optString("end").orEmpty(),
                    nextTitle = next?.optString("title").orEmpty(),
                    nextStart = next?.optString("start").orEmpty(),
                    health = root.optInt("health", 100),
                    dayType = root.optJSONObject("day_type")?.optString("label").orEmpty(),
                )
            }
        }.getOrNull()
    }

    internal fun execute(request: Request) = client.newCall(request).execute()

    internal fun requireToken(): String = token ?: throw ApiException("设备未配对")

    internal fun errorText(code: Int, body: String): String {
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
    /** 千问余额；-1 表示查不到（没配 AccessKey） */
    val qwenTotal: Double = -1.0,
    val qwenSpent: Double = 0.0,
    val qwenCalls: Int = 0,
    val qwenConfigured: Boolean = false,
)

/** 日记条目：锁着也给日期/天气/身体，正文不在列表里。 */
data class DiaryItem(
    val date: String = "",
    val weekday: String = "",
    val weather: String = "",
    val tempC: Int? = null,
    val location: String = "",
    val health: Int? = null,
    val visible: Boolean = false,
    val asked: Boolean = false,
    val expired: Boolean = false,
    /** 明确拒绝过：显示「她说不给」，重新点「问问她」还能再问 */
    val declined: Boolean = false,
)

/** 单篇日记。visible=false 时 text 一定为 null（服务端就不给）。 */
data class DiaryDetail(
    val date: String = "",
    val visible: Boolean = false,
    val text: String? = null,
    val asked: Boolean = false,
    val expired: Boolean = false,
    val declined: Boolean = false,
    val peeked: Boolean = false,
    val images: List<String> = emptyList(),
    val reason: String = "",
    val weekday: String = "",
    val weather: String = "",
    val tempC: Int? = null,
    val location: String = "",
)

/** 她的生活状态：睡着 / 在忙（在做某一件事）/ 有空。 */
data class LifeStatus(
    val status: String = "free",
    val currentTitle: String = "",
    val currentEnd: String = "",
    val nextTitle: String = "",
    val nextStart: String = "",
    val health: Int = 100,
    /** 今天是什么日子：大扫除日 / 通宵追番日 / 生病日…普通日子为空 */
    val dayType: String = "",
)

/**
 * 消息被闸门压住了。
 *
 * 这不是失败：服务端已经落库，只是她这会儿在忙或在睡，到 [until] 才回。
 * 客户端要把它和「发送失败」分开，不然用户会以为消息丢了，反复重发。
 */
class HeldException(val until: String, val reason: String, val untilMs: Long = 0L) :
    Exception(
        when {
            reason == "asleep" && until.isNotBlank() -> "她睡着了，${until} 醒了回你"
            reason == "asleep" -> "她睡着了，醒来回你"
            until.isBlank() -> "她这会儿在忙，等会儿回你"
            else -> "她这会儿在忙，${until} 再回你"
        },
    )
