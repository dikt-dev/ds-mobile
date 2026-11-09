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

internal suspend fun ApiClient.videoAsr(file: File): Pair<List<AsrLine>, Boolean> = withContext(Dispatchers.IO) {
    runCatching {
        val body = file.asRequestBody("audio/mp4".toMediaType())
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/app/video-asr")
            .header("authorization", "Bearer " + (token ?: ""))
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@use emptyList<AsrLine>() to true
            val root = JSONObject(text)
            val arr = root.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("text").trim()
                if (t.isEmpty()) null else AsrLine(o.optDouble("t", 0.0), t)
            }
            lines to false
        }
    }.getOrDefault(Pair(emptyList<AsrLine>(), true))
}

/**
 * 视频理解：服务端拿签名 URL 交给千问 omni，看完返回带时间点的观察笔记。
 *
 * 失败返回 null——调用方退回「抽帧 + whisper」那条老路，消息照发。
 */

internal suspend fun ApiClient.videoVision(key: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/app/video-vision")
            .header("authorization", "Bearer " + (token ?: ""))
            .post(JSONObject().put("key", key).toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@use null
            val root = JSONObject(text)
            if (!root.optBoolean("ok")) null else root.optString("text").takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

/**
 * B 站视频理解：把 playUrl 直链交给服务端，让千问 omni 看完整段画面。
 *
 * 服务端不碰 B 站接口（机房 IP 会被 412），直链由手机上现取现传。
 * 失败返回 null，卡片保持原来的大纲 + 字幕 + 雪碧图。
 */

internal suspend fun ApiClient.biliVision(
    direct: String,
    title: String,
    duration: Int,
    cacheKey: String,
): String? = withContext(Dispatchers.IO) {
    if (direct.isBlank()) return@withContext null
    runCatching {
        val payload = JSONObject()
            .put("direct", direct)
            .put("title", title)
            .put("duration", duration)
            .put("cacheKey", cacheKey)
            .toString()
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/app/bili-vision")
            .header("authorization", "Bearer " + (token ?: ""))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val root = JSONObject(resp.body?.string().orEmpty())
            if (!root.optBoolean("ok")) null else root.optString("text").takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

internal suspend fun ApiClient.uploadMedia(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
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

/** 拍照后直接发：不用落临时文件、不用 FileProvider，少一层权限问题。 */

internal suspend fun ApiClient.uploadMediaBytes(bytes: ByteArray, mime: String = "image/jpeg"): String =
    withContext(Dispatchers.IO) {
        if (bytes.size > 60L * 1024 * 1024) throw ApiException("文件超过 60MB")
        val request = Request.Builder()
            .url("$baseUrl/api/app/upload/video")
            .header("Authorization", "Bearer ${requireToken()}")
            .post(bytes.toRequestBody(mime.toMediaTypeOrNull()))
            .build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(errorText(response.code, text))
            JSONObject(text).optString("key").ifBlank { throw ApiException("上传响应缺少 key") }
        }
    }

internal fun ApiClient.mediaUrl(key: String): String =
    "$baseUrl/api/app/media?key=${java.net.URLEncoder.encode(key, "UTF-8")}"

/**
 * 视频落地成文件：第二次看同一条直接读本地，不再重新下载。
 *
 * App 给了 1GB 缓存配额，视频是最占流量也最值得缓存的东西——
 * 不落地的话每划回来一次就要重下一遍。
 */
/** 同步看一眼本地有没有这个视频文件（不下载），用于决定播放器起哪种源。 */

internal fun ApiClient.cachedMediaFile(key: String): File? {
    val root = cacheRoot ?: return null
    val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = File(File(root, "media-files"), safe)
    return if (target.isFile && target.length() > 0L) target else null
}

internal suspend fun ApiClient.mediaFile(key: String): File? = withContext(Dispatchers.IO) {
    val root = cacheRoot ?: return@withContext null
    val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = File(File(root, "media-files"), safe)
    if (target.isFile && target.length() > 0L) return@withContext target
    val bytes = runCatching { mediaBytes(key) }.getOrNull() ?: return@withContext null
    runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
        target
    }.getOrNull()
}

internal suspend fun ApiClient.mediaBytes(key: String): ByteArray = withContext(Dispatchers.IO) {
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

internal fun ApiClient.mediaHeaders(): Map<String, String> =
    mapOf("Authorization" to "Bearer ${requireToken()}")

/** 缓存键带版本：字段结构变了就自然失效，不需要清用户数据。 */
