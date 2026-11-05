/**
 * 消息同步：增量拉取、首装全量补齐、pending 对账、老库向前回填。
 *
 * 从 ChatScreen 的闭包里搬出来——这四件事只依赖 dao / api / 失败标记，
 * 和界面状态无关，放在这里才能单独看、单独测。行为一字未改。
 */
package com.dsmobile.app

import android.util.Log
import com.dsmobile.app.data.MessageEntity
import com.dsmobile.app.data.MessageDao
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.service.KeepAliveService
import com.dsmobile.app.net.ApiClient

class ChatSync(
    private val dao: MessageDao,
    private val api: ApiClient,
    private val failedPendingIds: MutableMap<Long, Boolean>,
    private val settings: SettingsStore? = null,
) {
    private val BILI_URL_REGEX =
        Regex("https?://(?:www\\.)?bilibili\\.com/video/(?:BV[0-9A-Za-z]{10}|av\\d+)")

suspend fun syncEvents() {
    var after = dao.maxId()
    val changed = mutableListOf<MessageEntity>()
    while (true) {
        val page = api.events(after, 100)
        if (page.messages.isEmpty()) break
        changed.addAll(page.messages)
        val next = page.messages.last().id
        if (!page.hasMore || next <= after) break
        after = next
    }
    if (changed.isNotEmpty()) dao.applyServerMessages(changed)
    // 记下「界面已经看到哪」：后台通知据此跳过刚看过的消息
    if (KeepAliveService.appVisible) {
        val top = dao.maxId()
        KeepAliveService.seenUpTo = maxOf(KeepAliveService.seenUpTo, top)
        // 落盘一份：进程被杀以后内存里的 seenUpTo 会丢，水位得留在 SharedPreferences 里
        settings?.let { if (top > it.lastEventId) it.lastEventId = top }
    }
    applyTombstones()
    backfillBiliMeta(changed)
}

/** 服务端删掉的消息，本地也删——不然清掉的测试记录会永远存在手机上。 */
private suspend fun applyTombstones() {
    val ids = runCatching { api.tombstones() }.getOrDefault(emptyList())
    if (ids.isEmpty()) return
    runCatching { dao.deleteByIds(ids) }
        .onSuccess { Log.i("ChatSync", "tombstones applied n=" + ids.size) }
}

/**
 * 网页端发的 B 站链接在服务端拿不到 meta（机房 IP 被 412），手机能直连。
 * 同步到"有链接但没有资料"的用户消息时，本地解析一条就回填一条。
 * 一次最多三条，避免同步时突然连发十几个 B 站请求。
 */
private suspend fun backfillBiliMeta(rows: List<MessageEntity>) {
    val candidates = rows
        .filter { it.role == "user" && !it.content.contains("[BILI_META]") }
        .mapNotNull { m -> BILI_URL_REGEX.find(m.content)?.value?.let { m to it } }
        .take(3)
    for ((m, url) in candidates) {
        val preview = runCatching { api.biliPreview(url) }.getOrNull() ?: continue
        if (preview.title.isBlank()) continue
        val ok = runCatching { api.postBiliMeta(m.id, url, preview) }.getOrDefault(false)
        Log.i("ChatSync", "bili backfill id=" + m.id + " ok=" + ok + " title=" + preview.title.take(24))
    }
}

/** 首装全量补齐；之后只走 syncEvents 增量。 */
suspend fun syncAllHistory() {
    var after = 0L
    val all = mutableListOf<MessageEntity>()
    while (true) {
        val page = api.events(after, 100)
        if (page.messages.isEmpty()) break
        all.addAll(page.messages)
        val next = page.messages.last().id
        if (!page.hasMore || next <= after) break
        after = next
    }
    if (all.isNotEmpty()) dao.applyServerMessages(all)
    backfillBiliMeta(all)
}

/**
 * pending 对账：断网/超时后服务端可能已经落库，本地 pending 不能永远卡在底部。
 * 服务端按 client_message_id 查；找到就删除 pending，找不到才标记失败。
 */
suspend fun reconcilePending() {
    val rows = dao.pending()
    if (rows.isEmpty()) return
    val ids = rows.map { it.clientMessageId }.filter { it.isNotBlank() }
    if (ids.isEmpty()) return
    val found = runCatching { api.clientMessages(ids) }.getOrNull()
    if (found == null) {
        // 网络不可用时也要让旧 pending 显示“失败/重试”，不能继续伪装成发送中。
        rows.forEach { failedPendingIds[it.id] = true }
        return
    }
    if (found.isNotEmpty()) {
        dao.applyServerMessages(found)
        val matched = found.mapNotNull { it.clientMessageId }.toSet()
        rows.filter { it.clientMessageId in matched }.forEach { failedPendingIds.remove(it.id) }
    }
    val matched = found.mapNotNull { it.clientMessageId }.toSet()
    rows.filter { it.clientMessageId !in matched }.forEach { failedPendingIds[it.id] = true }
}

/** 老版本本地库只有最近 300 条；从本地最小 id 往前回填。 */
suspend fun backfillOlderHistory() {
    var before = dao.minId()
    var guard = 0
    android.util.Log.i("ChatSync", "backfill start before=$before")
    while (before > 1 && guard < 200) {
        val page = api.events(0, 100, beforeId = before)
        android.util.Log.i(
            "ChatSync",
            "backfill page n=${page.messages.size} first=${page.messages.firstOrNull()?.id} " +
                "hasMore=${page.hasMore}",
        )
        if (page.messages.isEmpty()) break
        dao.insertAll(page.messages)
        val next = page.messages.first().id
        if (next <= 0 || next >= before) break
        before = next
        if (!page.hasMore) break
        guard++
    }
    android.util.Log.i("ChatSync", "backfill done before=$before guard=$guard")
}
}
