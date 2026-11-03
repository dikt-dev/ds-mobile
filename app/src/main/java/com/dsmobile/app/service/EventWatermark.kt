package com.dsmobile.app.service

import android.util.Log
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.net.ApiClient

/**
 * 修「高水位超前」：服务端库被重置后，消息 id 会重新从 1 开始涨，
 * 而手机里记着旧库的 lastEventId（比如 1776）。之后所有 /api/app/events?after=1776
 * 都返回空 —— 通知看起来"永远收不到"，其实消息一直在库里有。
 *
 * 判据很简单：问一次最新 id，比本地高水位小，说明服务端换过库，直接对齐过去。
 */
internal suspend fun healEventWatermark(api: ApiClient, settings: SettingsStore): Boolean {
    val after = settings.lastEventId
    if (after <= 0L) return false
    val probe = runCatching { api.events(0, 1, latest = true) }.getOrNull() ?: return false
    if (probe.latestId <= 0L || after <= probe.latestId) return false
    Log.w(
        "EventWatermark",
        "server reset: lastEventId ${after} > latest ${probe.latestId}, realigning",
    )
    settings.lastEventId = probe.latestId
    // 两个水位一起对齐：只降到 latest 不会再弹一遍旧消息，也不会漏掉以后的新消息。
    settings.lastNotifiedId = probe.latestId
    return true
}
