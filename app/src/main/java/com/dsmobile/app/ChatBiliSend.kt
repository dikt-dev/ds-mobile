/**
 * 发 B 站链接时的取帧流水线：现取 playUrl → 抽宫格 → 长视频再补细节帧。
 *
 * 从 sendText 里整段搬出来。这段是"发一条视频消息"最慢的部分（3 分钟视频约 40s），
 * 单独放一个文件才看得清它到底在等什么。行为一字未改。
 */
package com.dsmobile.app

import android.graphics.Bitmap
import android.util.Log
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.net.*
import com.dsmobile.app.net.BiliPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

data class BiliSendMedia(
    val frames: List<Bitmap>,
    val detailFrames: List<Bitmap>,
    val durationSec: Double,
    val playUrl: String,
    /** 千问 omni 写的画面过程；拿不到就是空串，卡片退回只看宫格 */
    val vision: String,
)

suspend fun prepareBiliMedia(
    api: ApiClient,
    bili: BiliPreview?,
    text: String,
    biliCookie: String,
    cacheDir: File,
    debug: Boolean,
): BiliSendMedia {
// 缓存里的 playUrl 会过期（deadline/sign），发之前现取一条
val livePlayUrl = if (bili != null && bili.bvid.isNotBlank() && bili.cid > 0) {
    runCatching { api.freshPlayUrl(bili.bvid, bili.cid, biliCookie) }.getOrDefault("")
} else {
    ""
}
val usingPlayUrl = livePlayUrl.ifBlank { bili?.playUrl.orEmpty() }
val biliDuration = if (bili != null) {
    if (bili.duration > 0) bili.duration.toDouble()
    else (bili.storyboardIndex.lastOrNull() ?: 0.0)
} else {
    0.0
}
val longVideo = bili != null && biliDuration >= 150.0 && usingPlayUrl.isNotBlank()
// 规划轮只吃文字，和抽宫格并行跑：它的耗时被宫格的 4 秒吃掉，不再额外加延迟。
val paired = coroutineScope {
    // 让模型看整段视频，和抽宫格并行跑：两边都要下载，但一个是给模型、一个是给人
    val visionDeferred = if (usingPlayUrl.isNotBlank()) async {
        runCatching {
            api.biliVision(
                direct = usingPlayUrl,
                title = bili?.title.orEmpty(),
                duration = biliDuration.toInt(),
                cacheKey = (bili?.bvid.orEmpty() + "-" + (bili?.cid ?: 0)),
                bvid = bili?.bvid.orEmpty(),
                cid = bili?.cid ?: 0L,
            )
        }.getOrNull().orEmpty()
    } else {
        null
    }
    val planDeferred: kotlinx.coroutines.Deferred<List<Double>>? = if (longVideo) async {
        runCatching {
            parseLookTimes(api.chat(textPlanPrompt(bili!!, biliDuration), null, record = false))
        }.getOrDefault(emptyList<Double>())
    } else {
        null
    }
    val grids = if (bili != null && bili.storyboardUrl.isNotBlank()) {
    runCatching {
        BiliFrames.loadAiFrames(
            storyboardUrl = bili.storyboardUrl,
            cols = bili.storyboardCols,
            rows = bili.storyboardRows,
            cookie = biliCookie,
            cacheDir = cacheDir,
            timestamps = bili.storyboardIndex,
            spriteUrls = bili.storyboardUrls,
            playUrl = usingPlayUrl,
            cacheKey = bili.bvid,
            dumpDebug = debug,
        )
        }.getOrDefault(emptyList())
    } else {
        emptyList()
    }
    Triple(grids, planDeferred?.await() ?: emptyList(), visionDeferred?.await().orEmpty())
}
val frames = paired.first
val plannedTimes = paired.second
val visionText = paired.third
// 长视频：从本地缓存的 MP4 里补原始分辨率帧。
// 规划轮 record=false，不写 D1、不进记忆，用户在界面上也看不到。
val detailFrames = if (frames.isNotEmpty() && bili != null && usingPlayUrl.isNotBlank()) {
    runCatching {
        val dur = biliDuration
        if (dur < 150.0) {
            emptyList()
        } else {
            android.util.Log.i("BiliAi", "text plan times=" + plannedTimes.joinToString())
            val times = plannedTimes.ifEmpty { pickDetailTimes(dur, bili.subtitle) }
            android.util.Log.i("BiliAi", "detail times=" + times.joinToString())
            if (times.isEmpty()) emptyList()
            else BiliFrames.detailFrames(usingPlayUrl, cacheDir, times, bili.bvid)
        }
    }.getOrDefault(emptyList())
} else {
    emptyList()
}
android.util.Log.i("BiliAi", "frames=" + frames.size + " detail=" + detailFrames.size)
    return BiliSendMedia(frames, detailFrames, biliDuration, usingPlayUrl, visionText)
}
