/**
 * 网络层的数据模型：分页、主动消息、B站/网页预览、配对快照。
 *
 * 从 ApiClient.kt 里搬出来——这些只是形状定义，和请求逻辑无关。
 */
package com.dsmobile.app.net

import com.dsmobile.app.data.MessageEntity

data class EventPage(val messages: List<MessageEntity>, val latestId: Long, val hasMore: Boolean)

data class ProactivePush(
    val queueId: Int,
    val content: String,
    val messageId: Long?,
    val createdAt: String,
)

/** 今日主动消息计划里的一档：客户端在 at 前 12 分钟设闹钟。 */
data class ProactivePlanSlot(
    val key: String,
    val kind: String,
    /** 北京时间 HH:MM */
    val at: String,
    /** 当天第几分钟（0-1439，北京时间）；fallback 条目可能为 -1 */
    val atMinutes: Int,
    val generated: Boolean = false,
)

data class ProactivePlan(
    val date: String,
    val paused: Boolean,
    val level: String = "normal",
    val toleranceMin: Int = 15,
    val notifyDeadlineMin: Int = 30,
    val slots: List<ProactivePlanSlot> = emptyList(),
    val fallback: List<ProactivePlanSlot> = emptyList(),
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


class ApiException(message: String) : Exception(message)

/** 字幕时间戳：m:ss，超过一小时用 h:mm:ss。 */
internal fun biliClock(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}
