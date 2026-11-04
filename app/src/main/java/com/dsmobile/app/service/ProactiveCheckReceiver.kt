package com.dsmobile.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dsmobile.app.data.MessageEntity
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟醒来后的检查：拉一次增量消息，有新主动消息就弹通知。
 *
 * 用 goAsync() 而不是 WorkManager：一次请求几秒钟，广播的 10 秒预算够用，
 * 而且不需要额外的任务依赖。
 */
class ProactiveCheckReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CHECK = "com.dsmobile.app.action.PROACTIVE_CHECK"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_TARGET_MS = "target_ms"
        const val EXTRA_RETRY = "retry"

        private const val TAG = "ProactiveCheck"
        private const val MAX_RETRY = 2
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        val pending = goAsync()
        val app = context.applicationContext
        val slotKey = intent.getStringExtra(EXTRA_SLOT).orEmpty()
        val targetMs = intent.getLongExtra(EXTRA_TARGET_MS, 0L)
        val retry = intent.getIntExtra(EXTRA_RETRY, 0)

        // 补看：不看计划，直接拉一次增量，然后把下一个补看挂上。
        // 目标时刻传 0，check() 就不会走「太早/重试」那条线。
        if (slotKey == "catchup") {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    check(app, "catchup", 0L)
                } catch (t: Throwable) {
                    Log.w(TAG, "catchup failed: ${t.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // 到点拉取：她被压住的消息这时候才生成。先催一次，再走下面的增量拉取。
        if (slotKey == "hold") {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = SettingsStore(app)
                    settings.token?.let { token ->
                        runCatching { ApiClient(settings.baseUrl, token).flushHeld() }
                    }
                    val ok = check(app, "hold", System.currentTimeMillis(), holdPull = true)
                    // 服务端可能还没生成出来（cron 抖动 / 模型失败），3 分钟后自己再拉一次
                    if (!ok) {
                        ProactiveScheduler.scheduleRetry(
                            app, "hold", System.currentTimeMillis(), 1,
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "hold pull failed: ${t.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // 翻页闹钟：只重算当天计划（顺带把自己推到明天），不发请求
        if (slotKey == "rollover") {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ProactiveScheduler.refresh(app)
                } catch (t: Throwable) {
                    Log.w(TAG, "rollover refresh failed: ${t.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var delivered = false
            try {
                delivered = check(app, slotKey, targetMs)
            } catch (t: Throwable) {
                Log.w(TAG, "check failed: ${t.message}")
            } finally {
                pending.finish()
            }

            val deadline = if (targetMs > 0L) {
                targetMs + ProactiveScheduler.NOTIFY_DEADLINE_MINUTES * 60_000L
            } else {
                Long.MAX_VALUE
            }
            if (!delivered && retry < MAX_RETRY && System.currentTimeMillis() < deadline) {
                ProactiveScheduler.scheduleRetry(app, slotKey, targetMs, retry + 1)
            }
        }
    }

    /** @return true = 这件事结束了（取到了 / 不需要了）；false = 窗口内可以再试一次。 */
    private suspend fun check(
        app: Context,
        slotKey: String,
        targetMs: Long,
        holdPull: Boolean = false,
    ): Boolean {
        val settings = SettingsStore(app)
        if (!settings.proactiveNotifyEnabled) return true
        val token = settings.token ?: return true
        val api = ApiClient(settings.baseUrl, token)

        // 提前超过 1 分钟醒来：服务端窗口还没开，请求必然空手而归。
        // 直接把这个闹钟挪到目标前 1 分钟，省一次请求和一次心跳。
        val now = System.currentTimeMillis()
        if (targetMs > 0L && now < targetMs - ProactiveScheduler.MIN_LEAD_MS) {
            Log.i(TAG, "too early by " + (targetMs - now) / 60_000 + "min, rearm slot=" + slotKey)
            ProactiveScheduler.rearmNear(app, slotKey, targetMs)
            return true
        }

        // 拉取即心跳：服务端据此知道这台设备是「闹钟模式」，不再等 5 秒一次的轮询
        runCatching { api.heartbeat("ds-mobile/alarm") }

        // 从来没有基线时先对齐，避免把历史消息整个弹一遍
        if (settings.lastEventId <= 0L) {
            val page = runCatching { api.events(0, 1, latest = true) }.getOrNull() ?: return false
            settings.lastEventId = page.latestId
            settings.lastNotifiedId = maxOf(settings.lastNotifiedId, page.latestId)
            Log.i(TAG, "baseline=${page.latestId} slot=$slotKey")
            ProactiveScheduler.recordWake(app, slotKey, targetMs, false, 0)
            return true
        }

        // 服务端重置过库的话，先对齐高水位，否则下面这一句永远返回空
        healEventWatermark(api, settings)

        val page = runCatching { api.events(settings.lastEventId, 50) }.getOrNull() ?: return false
        if (page.messages.isNotEmpty()) {
            settings.lastEventId = maxOf(settings.lastEventId, page.messages.maxOf { it.id })
        }

        // 闸门到点拉取：她这条是回复，不是主动消息，不能按 proactiveKind 过滤
        val fresh = page.messages
            .filter {
                it.id > settings.lastNotifiedId &&
                    (if (holdPull) it.role == "assistant" else isProactive(it))
            }
            .sortedBy { it.id }
        Log.i(
            TAG,
            "slot=" + slotKey + " lastEvent=" + settings.lastEventId +
                " lastNotified=" + settings.lastNotifiedId + " got=" + page.messages.size +
                " fresh=" + fresh.size + " visible=" + KeepAliveService.appVisible,
        )

        if (fresh.isEmpty()) {
            Log.i(TAG, "no proactive message yet, slot=$slotKey")
            ProactiveScheduler.recordWake(app, slotKey, targetMs, false, 0)
            return false
        }

        settings.lastNotifiedId = maxOf(settings.lastNotifiedId, fresh.maxOf { it.id })
        if (!KeepAliveService.appVisible) {
            val text = fresh.joinToString("\n") { it.content }.trim()
            ProactiveNotifier.notify(app, text, fresh.last().id)
        }
        ProactiveScheduler.recordWake(app, slotKey, targetMs, true, fresh.size)
        Log.i(TAG, "delivered=${fresh.size} slot=$slotKey")
        return true
    }

    private fun isProactive(message: MessageEntity): Boolean =
        message.role == "assistant" && message.proactiveKind != null
}
