package com.dsmobile.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.net.BiliPreview
import com.dsmobile.app.net.WebPreview

/**
 * 跨 LazyColumn item 复用：
 * - 图片/GIF 解码结果
 * - B站/网页预览元数据
 * - 视频 ExoPlayer 实例，避免划走再划回来重新 prepare
 */
object MediaCaches {
    // 动图解码很贵，多留一些在内存里，滑回去不用重解
    private val images = LruCache<String, Drawable>(48)
    private val bili = LruCache<String, BiliPreview>(32)
    private val web = LruCache<String, WebPreview>(32)
    private val players = mutableMapOf<String, ExoPlayer>()
    private val biliFrames = LruCache<String, Bitmap>(8)

    fun image(key: String): Drawable? = images.get(key)
    fun putImage(key: String, drawable: Drawable) = images.put(key, drawable)

    /**
     * 刚发出、还没拿到服务端 key 的本地图。
     *
     * 发图流程是：本地气泡（有 localUri）→ 上传 → 服务端生成 [图片:images/日期/毫秒.ext]
     * → 消息列表刷新成服务端那条。两条气泡之间没有交集，服务端那条要重新下载才显示，
     * 于是用户看到"图片消失一下再出现"。这里把本地位图先存起来，刷新后的第一个
     * 未命中缓存的图直接认领它，气泡一次到位。
     *
     * 只保留最近 4 张、3 分钟内有效：上传 + 等服务端压缩 + 等 AI 回复可能超过 20 秒，
     * 窗口太短会让正式气泡退回「从服务器下载」，看起来就是「AI 都回话了图才出来」。
     */
    private val outgoing = ArrayDeque<Pair<Long, Drawable>>()
    private const val OUTGOING_TTL_MS = 180_000L

    fun rememberOutgoing(drawable: Drawable) {
        outgoing.addLast(System.currentTimeMillis() to drawable)
        while (outgoing.size > 4) outgoing.removeFirst()
    }

    fun claimOutgoing(now: Long = System.currentTimeMillis()): Drawable? {
        while (outgoing.isNotEmpty()) {
            val (at, drawable) = outgoing.removeFirst()
            if (now - at < OUTGOING_TTL_MS) return drawable
        }
        return null
    }

    fun biliPreview(url: String): BiliPreview? = bili.get(url)
    fun putBiliPreview(url: String, preview: BiliPreview) = bili.put(url, preview)

    fun webPreview(url: String): WebPreview? = web.get(url)
    fun putWebPreview(url: String, preview: WebPreview) = web.put(url, preview)

    fun biliFrame(url: String): Bitmap? = biliFrames.get(url)
    fun putBiliFrame(url: String, bitmap: Bitmap) = biliFrames.put(url, bitmap)

    /**
     * 视频播放器。给了 localFile 就直接放本地缓存，省一次跨网下载；
     * 没有就走网络流（边下边播），并在后台落地。
     */
    fun player(context: Context, api: ApiClient, key: String, localFile: File? = null): ExoPlayer {
        val cacheKey = if (localFile != null) "$key@file" else key
        return players.getOrPut(cacheKey) {
            val sourceFactory = DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory().setDefaultRequestProperties(api.mediaHeaders())
            )
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(sourceFactory)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(localFile?.toURI()?.toString() ?: api.mediaUrl(key)))
                    prepare()
                }
        }
    }

    fun clear() {
        images.evictAll()
        bili.evictAll()
        web.evictAll()
        players.values.forEach { it.release() }
        players.clear()
    }

    fun clearBili() {
        bili.evictAll()
        biliFrames.evictAll()
    }
}
