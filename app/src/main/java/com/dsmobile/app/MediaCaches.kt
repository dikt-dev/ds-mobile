package com.dsmobile.app

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
    private val images = LruCache<String, Drawable>(24)
    private val bili = LruCache<String, BiliPreview>(32)
    private val web = LruCache<String, WebPreview>(32)
    private val players = mutableMapOf<String, ExoPlayer>()

    fun image(key: String): Drawable? = images.get(key)
    fun putImage(key: String, drawable: Drawable) = images.put(key, drawable)

    fun biliPreview(url: String): BiliPreview? = bili.get(url)
    fun putBiliPreview(url: String, preview: BiliPreview) = bili.put(url, preview)

    fun webPreview(url: String): WebPreview? = web.get(url)
    fun putWebPreview(url: String, preview: WebPreview) = web.put(url, preview)

    fun player(context: Context, api: ApiClient, key: String): ExoPlayer =
        players.getOrPut(key) {
            val sourceFactory = DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory().setDefaultRequestProperties(api.mediaHeaders())
            )
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(sourceFactory)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(api.mediaUrl(key)))
                    prepare()
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
    }
}
