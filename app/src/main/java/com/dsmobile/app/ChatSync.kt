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
import com.dsmobile.app.net.ApiClient

class ChatSync(
    private val dao: MessageDao,
    private val api: ApiClient,
    private val failedPendingIds: MutableMap<Long, Boolean>,
) {

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
