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

    /**
     * 比计划时刻早醒多少分钟：给系统的延迟留余量。
     *
     * 3 分钟是实测值——setAndAllowWhileIdle 在 Doze 里的抖动通常在 1~5 分钟，
     * 留 3 分钟不至于白醒太多次，也不至于每次都迟到。
     */
    const val EARLY_MINUTES = 3L

    /** 超过计划时刻这么久就不再弹通知（只等下次打开 App 看）。 */
    const val NOTIFY_DEADLINE_MINUTES = 45L

    /**
     * 离目标时刻小于这么久才允许发请求。
     *
     * 闹钟按 EARLY_MINUTES（3 分钟）提前挂，但系统还可能提前把我们叫醒；
     * 提前醒来时服务端那边还没准备好，拉回来的永远是 0 条——白醒一次、白耗一次电量。
     * 提前超过 1 分钟就只把闹钟挪到目标前 1 分钟，不发请求。
     */
    const val MIN_LEAD_MS = 60_000L
    private const val REARM_REQUEST = 300

    /** 每天凌晨的「翻页」闹钟：不依赖用户当天打开过 App。 */
    private const val ROLLOVER_REQUEST = 201

    /**
     * 「补看」闹钟：不看档位计划，每 30 分钟醒一次，只拉一次增量。
     *
     * 为什么需要：档位闹钟依赖 refresh() 挂表，一旦计划拉取失败、App 当天没打开、
     * 或者服务端生成比档位晚几分钟，那次提醒就没了（线上就这么丢的：消息在，没人叫）。
     * 补看闹钟不依赖任何计划，只要进程能醒就一定能补上，一天 40 次请求，可以忽略。
     */
    /** 她忙完/睡醒的到点闹钟：醒来拉一次，压着的消息这时候才有。 */
    private const val HOLD_REQUEST = 500
    private const val CATCHUP_REQUEST = 400
    const val CATCHUP_INTERVAL_MS = 30 * 60_000L
    private const val CATCHUP_FIRST_DELAY_MS = 5 * 60_000L

    private const val MAX_SLOTS = 4
    private const val RETRY_REQUEST_BASE = 100
    private const val RETRY_MAX = 4
    private const val RETRY_DELAY_MS = 3 * 60_000L

    /** 重算计划时是否连带清掉待重试闹钟；默认否。 */
    private const val CANCEL_RETRIES_ON_REFRESH = false

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

        val api = ApiClient(settings.baseUrl, token)
        // 进前台顺便校一次高水位：服务端换库后 id 从头涨，不校就永远收不到通知
        runCatching { healEventWatermark(api, settings) }

        val plan = runCatching { api.proactivePlan() }
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

        // 顺带挂明天的翻页闹钟：闹钟只覆盖当天，用户不打开 App 就会整天静默。
        ensureDailyRollover(app)
        settings.proactiveSchedule = toJson(scheduled)
        Log.i(
            TAG,
            "scheduled=${scheduled.size} " +
                scheduled.joinToString { "${it.slotKey}@${it.atLabel}" },
        )
        // 不挂补看闹钟：后台不做周期轮询，到点只由档位/闸门闹钟触发
        ensureDailyRollover(app)
        scheduled
    }

    /**
     * 挂一个每天 00:10 的「翻页」闹钟，醒来后重算当天的计划。
     *
     * 之前闹钟只在「打开 App / 开机 / 手动重算」时挂，只覆盖当天：
     * 哪一天没打开过 App，那天就一条提醒都没有（消息还在，只是没人叫你）。
     * 这个闹钟只做一件事——把第二天的档位挂上，然后把自己推到再下一天。
     */
    fun ensureDailyRollover(context: Context) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        var target = java.time.LocalDate.now(zone).atTime(0, 10).atZone(zone).toInstant().toEpochMilli()
        if (target <= now + 60_000L) {
            target = java.time.LocalDate.now(zone).plusDays(1).atTime(0, 10).atZone(zone)
                .toInstant().toEpochMilli()
        }
        setAlarm(context, ROLLOVER_REQUEST, target, "rollover", 0L, 0)
        Log.i(TAG, "daily rollover armed for " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(target)))
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

    /**
     * 挂下一次「补看」。用 setAndAllowWhileIdle（不是精确闹钟），Doze 里也会醒。
     * 醒来后无论取没取到消息都重新挂一次，形成 30 分钟的链。
     */
    /**
     * 她被闸门压住时：按服务端给的 until_ms 挂一次闹钟，到点来拉。
     * 平时不轮询，靠这一个闹钟把回复取回来。
     */
    fun scheduleHoldPull(context: Context, untilMs: Long) {
        if (untilMs <= 0L) return
        val triggerAt = maxOf(System.currentTimeMillis() + 15_000L, untilMs + 20_000L)
        setAlarm(context, HOLD_REQUEST, triggerAt, "hold", untilMs, 0)
        Log.i(TAG, "hold alarm in " + (triggerAt - System.currentTimeMillis()) / 1000 + "s")
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        for (i in 0 until MAX_SLOTS) {
            manager.cancel(pendingFor(context, i, "", 0L, 0))
        }
        // 待重试的闹钟故意不取消：打开 App 只是重算当天档位，
        // 不该把「已经醒来但还没取到消息」的那次补偿一起清掉。
        if (CANCEL_RETRIES_ON_REFRESH) {
            for (i in 0..RETRY_MAX) {
                manager.cancel(pendingFor(context, RETRY_REQUEST_BASE + i, "", 0L, i))
            }
        }
        manager.cancel(pendingFor(context, 200, "debug", 0L, 0))
        manager.cancel(pendingFor(context, REARM_REQUEST, "", 0L, 0))
        manager.cancel(pendingFor(context, ROLLOVER_REQUEST, "", 0L, 0))
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
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
            when {
                exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                else -> manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
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
