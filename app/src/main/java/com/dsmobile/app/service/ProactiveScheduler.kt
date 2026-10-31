package com.dsmobile.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.net.ProactivePlanSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「到点提醒」的闹钟调度器。
 *
 * 不用精确闹钟：setAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM，Doze 里也能醒，
 * 代价是可能被推迟十几分钟——主动消息本身带随机性，用户感知不到；30 分钟宽的窗口
 * 和 12 分钟提前量正好把这个抖动吸收掉。
 *
 * 一天最多 4 次唤醒（早上 / 深夜 / 白天随机 0~2 条），每次一个 events 请求。
 */
object ProactiveScheduler {

    private const val TAG = "ProactiveScheduler"

    /** 比计划时刻早醒多少分钟：给系统的延迟留余量。 */
    const val EARLY_MINUTES = 12L

    /** 超过计划时刻这么久就不再弹通知（只等下次打开 App 看）。 */
    const val NOTIFY_DEADLINE_MINUTES = 30L

    /**
     * 离目标时刻小于这么久才允许发请求。
     *
     * 闹钟故意提前 12 分钟挂（吸收 Doze 延迟），但提前醒来时服务端那边还没准备好，
     * 拉回来的永远是 0 条——白醒一次、白耗一次电量。提前超过 1 分钟就只把闹钟
     * 挪到目标前 1 分钟，不发请求。
     */
    const val MIN_LEAD_MS = 60_000L
    private const val REARM_REQUEST = 300

    private const val MAX_SLOTS = 4
    private const val RETRY_REQUEST_BASE = 100
    private const val RETRY_MAX = 2
    private const val RETRY_DELAY_MS = 10 * 60_000L

    private val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

    data class Scheduled(
        val slotKey: String,
        val kind: String,
        val atLabel: String,
        val triggerAtMs: Long,
    )

    /**
     * 拉今天的计划并重挂全部闹钟（幂等：先取消再设）。
     * 关闭开关 / 未配对 / 暂停中 → 只取消，不挂。
     */
    suspend fun refresh(context: Context): List<Scheduled> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val settings = SettingsStore(app)
        cancelAll(app)

        if (!settings.proactiveNotifyEnabled) {
            Log.i(TAG, "notify off, alarms cleared")
            return@withContext emptyList()
        }
        val token = settings.token
        if (token.isNullOrBlank()) return@withContext emptyList()

        val plan = runCatching { ApiClient(settings.baseUrl, token).proactivePlan() }
            .onFailure { Log.w(TAG, "plan fetch failed: ${it.message}") }
            .getOrNull()

        if (plan != null && plan.paused) {
            Log.i(TAG, "proactive paused, no alarms")
            settings.proactiveSchedule = null
            return@withContext emptyList()
        }

        val date = runCatching { LocalDate.parse(plan?.date.orEmpty()) }.getOrNull()
            ?: LocalDate.now(BEIJING)
        val slots = plan?.slots?.takeIf { it.isNotEmpty() } ?: fallbackSlots()
        val now = System.currentTimeMillis()
        val scheduled = mutableListOf<Scheduled>()

        for (slot in slots.sortedBy { if (it.atMinutes >= 0) it.atMinutes else minutesOfDay(it.at) }
            .take(MAX_SLOTS)) {
            if (slot.generated) continue
            val minutes = if (slot.atMinutes >= 0) slot.atMinutes else minutesOfDay(slot.at)
            if (minutes < 0) continue
            val targetMs = date.atStartOfDay(BEIJING).plusMinutes(minutes.toLong())
                .toInstant().toEpochMilli()
            if (now > targetMs + NOTIFY_DEADLINE_MINUTES * 60_000L) continue

            val triggerAt = maxOf(now + 30_000L, targetMs - EARLY_MINUTES * 60_000L)
            setAlarm(app, scheduled.size, triggerAt, slot.key, targetMs, 0)
            scheduled += Scheduled(slot.key, slot.kind, slot.at, triggerAt)
        }

        settings.proactiveSchedule = toJson(scheduled)
        Log.i(
            TAG,
            "scheduled=${scheduled.size} " +
                scheduled.joinToString { "${it.slotKey}@${it.atLabel}" },
        )
        scheduled
    }

    /** 醒来没取到消息时的一次重试（最多 RETRY_MAX 次，都在窗口内）。 */
    fun scheduleRetry(context: Context, slotKey: String, targetMs: Long, retry: Int) {
        if (retry > RETRY_MAX) return
        val triggerAt = System.currentTimeMillis() + RETRY_DELAY_MS
        setAlarm(context, RETRY_REQUEST_BASE + retry, triggerAt, slotKey, targetMs, retry)
        Log.i(TAG, "retry=$retry scheduled for $slotKey at +${RETRY_DELAY_MS / 60_000}min")
    }

    /**
     * 「醒太早」时把闹钟挪到目标时刻前 1 分钟。
     *
     * 只移一次：挪过去的闹钟触发时已满足 MIN_LEAD_MS，会正常发请求。
     */
    fun rearmNear(context: Context, slotKey: String, targetMs: Long) {
        // 挪到目标前 45 秒：比 MIN_LEAD_MS（60 秒）留 15 秒余量，
        // 否则新闹钟自己也会被判成「太早」，来回挪个没完。
        val triggerAt = maxOf(System.currentTimeMillis() + 5_000L, targetMs - 45_000L)
        setAlarm(context, REARM_REQUEST, triggerAt, slotKey, targetMs, 0)
        Log.i(TAG, "rearmed " + slotKey + " in " + (triggerAt - System.currentTimeMillis()) / 1000 + "s")
    }

    /** 调试用：N 秒后立刻跑一次检查，验证「醒来 → 拉消息 → 通知」链路。 */
    fun triggerIn(context: Context, seconds: Long) {
        val at = System.currentTimeMillis() + seconds * 1000
        setAlarm(context, 200, at, "debug", at, 0)
        Log.i(TAG, "debug alarm in ${seconds}s")
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        for (i in 0 until MAX_SLOTS) {
            manager.cancel(pendingFor(context, i, "", 0L, 0))
        }
        for (i in 0..RETRY_MAX) {
            manager.cancel(pendingFor(context, RETRY_REQUEST_BASE + i, "", 0L, i))
        }
        manager.cancel(pendingFor(context, 200, "debug", 0L, 0))
        manager.cancel(pendingFor(context, REARM_REQUEST, "", 0L, 0))
    }

    /** 记一次唤醒：调试页据此看「计划时刻 vs 实际醒来」的延迟分布。 */
    fun recordWake(context: Context, slotKey: String, targetMs: Long, delivered: Boolean, count: Int) {
        val settings = SettingsStore(context)
        val now = System.currentTimeMillis()
        val entry = JSONObject()
            .put("at", now)
            .put("slot", slotKey)
            .put("target", targetMs)
            .put("delay_min", if (targetMs > 0L) (now - targetMs) / 60000.0 else 0.0)
            .put("delivered", delivered)
            .put("count", count)
        val previous = runCatching { JSONArray(settings.proactiveWakeLog ?: "[]") }
            .getOrDefault(JSONArray())
        val kept = JSONArray()
        kept.put(entry)
        for (i in 0 until minOf(previous.length(), 7)) kept.put(previous.get(i))
        settings.proactiveWakeLog = kept.toString()
    }

    /** 调试页展示：当前挂着的闹钟（下次唤醒时间）。 */
    fun scheduleSummary(context: Context): String {
        val raw = SettingsStore(context).proactiveSchedule ?: return "未设置"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        if (arr.length() == 0) return "未设置"
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        val lines = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            lines += "${o.optString("slot")} → ${fmt.format(java.util.Date(o.optLong("trigger_at")))}"
        }
        return lines.joinToString("\n").ifBlank { "未设置" }
    }

    /** 调试页展示：最近几次唤醒的计划时刻 vs 实际延迟。 */
    fun wakeSummary(context: Context): String {
        val raw = SettingsStore(context).proactiveWakeLog ?: return "还没有记录"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        if (arr.length() == 0) return "还没有记录"
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        val lines = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val delay = Math.round(o.optDouble("delay_min", 0.0))
            val got = if (o.optBoolean("delivered")) o.optInt("count") else 0
            lines += "${fmt.format(java.util.Date(o.optLong("at")))} ${o.optString("slot")} 延迟${delay}分 取到$got"
        }
        return lines.joinToString("\n")
    }

    private fun setAlarm(
        context: Context,
        requestCode: Int,
        triggerAt: Long,
        slotKey: String,
        targetMs: Long,
        retry: Int,
    ) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingFor(context, requestCode, slotKey, targetMs, retry)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }.onFailure { Log.w(TAG, "set alarm failed: ${it.message}") }
    }

    private fun pendingFor(
        context: Context,
        requestCode: Int,
        slotKey: String,
        targetMs: Long,
        retry: Int,
    ): PendingIntent {
        val intent = Intent(context, ProactiveCheckReceiver::class.java).apply {
            action = ProactiveCheckReceiver.ACTION_CHECK
            putExtra(ProactiveCheckReceiver.EXTRA_SLOT, slotKey)
            putExtra(ProactiveCheckReceiver.EXTRA_TARGET_MS, targetMs)
            putExtra(ProactiveCheckReceiver.EXTRA_RETRY, retry)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 计划接口挂了也得能提醒：退回两个固定时段。 */
    private fun fallbackSlots(): List<ProactivePlanSlot> = listOf(
        ProactivePlanSlot("morning", "morning", "07:50", 7 * 60 + 50),
        ProactivePlanSlot("evening", "evening", "22:50", 22 * 60 + 50),
    )

    private fun minutesOfDay(at: String): Int {
        val parts = at.split(":")
        if (parts.size != 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }

    private fun toJson(items: List<Scheduled>): String {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("slot", item.slotKey)
                    .put("kind", item.kind)
                    .put("at", item.atLabel)
                    .put("trigger_at", item.triggerAtMs),
            )
        }
        return arr.toString()
    }
}
