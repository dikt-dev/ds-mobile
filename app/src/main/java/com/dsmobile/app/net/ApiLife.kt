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

internal suspend fun ApiClient.diaryList(limit: Int = 60): List<DiaryItem> = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url("$baseUrl/api/app/ai-diary/index?limit=$limit")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) return@runCatching emptyList()
            val root = JSONObject(response.body?.string().orEmpty())
            val arr = root.optJSONArray("items") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        DiaryItem(
                            date = o.optString("date"),
                            weekday = o.optString("weekday"),
                            weather = o.optString("weather"),
                            tempC = if (o.has("tempC") && !o.isNull("tempC")) o.optInt("tempC") else null,
                            location = o.optString("location"),
                            health = if (o.has("health") && !o.isNull("health")) o.optInt("health") else null,
                            visible = o.optBoolean("visible"),
                            asked = o.optBoolean("asked"),
                            expired = o.optBoolean("expired"),
                            declined = o.optBoolean("declined"),
                        )
                    )
                }
            }
        }
    }.getOrElse { emptyList() }
}

/** 单篇：没同意时 text 为空，只回基本信息和一句状态。 */

internal suspend fun ApiClient.diaryDetail(date: String): DiaryDetail? = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url("$baseUrl/api/app/ai-diary?date=$date")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) return@runCatching null
            val o = JSONObject(response.body?.string().orEmpty())
            val basics = o.optJSONObject("basics")
            DiaryDetail(
                date = o.optString("date", date),
                visible = o.optBoolean("visible"),
                text = if (o.isNull("text")) null else o.optString("text").ifBlank { null },
                asked = o.optBoolean("asked"),
                expired = o.optBoolean("expired"),
                declined = o.optBoolean("declined"),
                peeked = o.optBoolean("peeked"),
                images = o.optJSONArray("images")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
                reason = o.optString("reason"),
                weekday = basics?.optString("weekday").orEmpty(),
                weather = basics?.optString("weather").orEmpty(),
                tempC = basics?.let { if (it.has("tempC") && !it.isNull("tempC")) it.optInt("tempC") else null },
                location = basics?.optString("location").orEmpty(),
            )
        }
    }.getOrNull()
}

/** 「问问她」：把这天标成待回答，等她聊天里回话。 */

internal suspend fun ApiClient.askDiary(date: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val body = JSONObject().put("date", date).toString()
        val request = Request.Builder()
            .url("$baseUrl/api/app/ai-diary/ask")
            .header("Authorization", "Bearer ${requireToken()}")
            .post(body.toRequestBody(jsonType))
            .build()
        execute(request).use { it.isSuccessful }
    }.getOrElse { false }
}

/** 她现在的状态（睡了/在忙/有空）+ 正在做什么，顶栏那行用它。 */
/** 到点拉取：让服务端把压着的消息生成出来（客户端按 until_ms 挂闹钟后调用）。 */
