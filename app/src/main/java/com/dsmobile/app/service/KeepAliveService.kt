package com.dsmobile.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dsmobile.app.ChatActivity
import com.dsmobile.app.replaceFaceMarkers
import com.dsmobile.app.data.MessageEntity
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台常驻服务（可选，默认关）：锁屏也能收到她的消息。
 *
 * 两条循环：
 * ① eventLoop   每 5 秒拉一次 /api/app/events 增量，App 不在前台就给新消息弹通知
 * ② heartbeatLoop 每 5 分钟报一次活着，服务端据此判断通道是否掉线
 *
 * 关掉它不等于没有通知——「到点提醒」闹钟（ProactiveScheduler）仍会兜底，
 * 只是延迟从 5 秒变成最多 30 分钟。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "ds_keepalive"
        // v2：确保已安装旧渠道也能拿到悬浮/锁屏/角标配置；旧渠道会被删除
        private const val MESSAGE_CHANNEL_ID = "ds_messages_v2"
        private const val LEGACY_MESSAGE_CHANNEL_ID = "ds_messages"
        private const val NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_ID = 2001
        private const val MESSAGE_NOTIFICATION_TAG = "ds_message"

        @Volatile
        private var unreadCount: Int = 0
        private val postedMessageNotificationIds =
            java.util.Collections.synchronizedSet(mutableSetOf<Int>())

        /** App 打开聊天页时清掉消息通知与角标。 */
        fun clearMessageNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel(MESSAGE_NOTIFICATION_ID)
            synchronized(postedMessageNotificationIds) {
                for (id in postedMessageNotificationIds) {
                    manager.cancel(MESSAGE_NOTIFICATION_TAG, id)
                }
                postedMessageNotificationIds.clear()
            }
            unreadCount = 0
        }

        @Volatile
        var appVisible: Boolean = false

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastStatus: String = "未启动"
            private set

        @Volatile
        var lastTime: Long = 0L
            private set

        /**
         * 启动后台常驻。**默认关闭**（设置 → 通知 → 后台常驻）。
         * 关闭时直接返回：App 只在打开期间收消息，没有任何常驻开销。
         */
        fun start(context: Context) {
            if (!SettingsStore(context).keepAliveEnabled) {
                Log.i(TAG, "keep-alive disabled (setting off), skip start")
                return
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveService::class.java),
            )
        }

        /**
         * 停止后台常驻（设置里关掉开关时调用）。
         *
         * 顺手报一次 `background-off` 心跳：服务端据此知道「是用户主动关的」，
         * 而不是「客户端死了」，避免 3 小时后收到一条「通道掉线」的运维提醒。
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
            val s = SettingsStore(context)
            val token = s.token
            if (token.isNullOrBlank()) return
            Thread {
                runCatching {
                    val api = ApiClient(s.baseUrl, token, s.biliSessdata, s.biliCookie, java.io.File(context.cacheDir, "api-media"))
                    kotlinx.coroutines.runBlocking { api.heartbeat("ds-mobile/background-off") }
                }
            }.start()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifiedMessageIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private var eventJob: Job? = null
    private var heartbeatJob: Job? = null
    private lateinit var settings: SettingsStore

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        createNotificationChannel()
        startForegroundCompat(buildNotification("正在启动…"))
        running = true
        lastStatus = "正在启动…"
        eventJob = scope.launch {
            delay(1_500)
            eventLoop()
        }
        heartbeatJob = scope.launch {
            delay(2_500)
            heartbeatLoop()
        }
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, keep running")
        // 某些 OEM 在划掉最近任务时会顺手杀服务；再拉一次，配合 START_STICKY。
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, KeepAliveService::class.java),
            )
        } catch (e: Exception) {
            Log.w(TAG, "restart after task removal failed: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        eventJob?.cancel()
        heartbeatJob?.cancel()
        scope.cancel()
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 连接状态由消息轮询顺手更新，不再单独探测。
     *
     * 原来是每 3 秒打一次 /api/health：那接口要查一次 D1、读一次 KV，
     * 一天 28800 次请求，全是为了给通知栏刷一行时间戳。现在零额外请求。
     */
    private fun markConnection(ok: Boolean) {
        lastStatus = if (ok) "已连接" else "重连中…"
        lastTime = System.currentTimeMillis()
        updateNotification(lastStatus)
    }

    /**
     * 5 分钟一次心跳；Worker 3 小时收不到才会认为通道掉线。
     *
     * 连续失败会把次数写进常驻通知——服务被系统静默冻结时，通知栏那行字
     * 会从「已连接」变成「心跳失败 N 次」，否则它永远停在最后一次成功的状态，
     * 看上去一切正常，实际早就收不到消息了。
     */
    private suspend fun heartbeatLoop() {
        var failures = 0
        while (scope.isActive) {
            val token = settings.token
            if (!token.isNullOrBlank()) {
                try {
                    ApiClient(settings.baseUrl, token).heartbeat()
                    Log.d(TAG, "heartbeat ok")
                    if (failures > 0) {
                        failures = 0
                        updateNotification(if (appVisible) "前台在线" else lastStatus)
                    }
                } catch (e: Exception) {
                    failures += 1
                    Log.w(TAG, "heartbeat failed (${failures}): ${e.message}")
                    updateNotification("心跳失败 $failures 次（${e.message?.take(40) ?: "未知"}）")
                }
            }
            delay(5 * 60_000L)
        }
    }

    /**
     * 增量消息轮询：App 在后台时给新助手消息发通知。
     *
     * 间隔不是固定 5 秒——那是"随时可能来消息"的假设，代价是一天 17,280 次请求，
     * 绝大多数时候什么都没拉到。改成三档：
     *
     * | 状态 | 间隔 |
     * | --- | --- |
     * | 刚收到新消息（2 分钟窗口内） | 5 秒（她可能连发几句） |
     * | 聊天页可见 | 30 秒（不弹通知，只为让高水位跟上） |
     * | 其余后台时间 | 45 秒 ± 15% 抖动 |
     *
     * 一天约 2,000 次，比原来少八成；最坏延迟 45 秒，而这层本来就是"锁屏也能收到"，
     * 真要 5 秒级的实时感，聊天页开着时走的是 ChatActivity 自己的同步。
     */
    private suspend fun eventLoop() {
        // 首次启动先取最新 id 作为基线，避免把历史消息全部弹一遍
        while (scope.isActive && settings.lastEventId <= 0) {
            val token = settings.token ?: break
            try {
                val page = ApiClient(settings.baseUrl, token).events(0, 1, latest = true)
                settings.lastEventId = page.latestId
                Log.i(TAG, "event baseline=${page.latestId}")
            } catch (e: Exception) {
                Log.w(TAG, "event baseline failed: ${e.message}")
                delay(5_000)
            }
        }

        var burstUntil = 0L
        while (scope.isActive) {
            val token = settings.token
            if (token.isNullOrBlank()) {
                delay(10_000)
                continue
            }
            // 服务端重置过库时，本地高水位会一直超前，轮询永远拿不到新消息
            runCatching { healEventWatermark(ApiClient(settings.baseUrl, token), settings) }
            try {
                var after = settings.lastEventId
                var last = after
                while (true) {
                    val page = ApiClient(settings.baseUrl, token).events(after, 100)
                    for (message in page.messages) {
                        if (message.id > last) {
                            if (message.role == "assistant" && !appVisible &&
                                message.id !in notifiedMessageIds
                            ) {
                                notifyMessage(message)
                                notifiedMessageIds.add(message.id)
                                // 只清最旧的一批：整体 clear() 会让集合区间内的消息再弹一遍
                                if (notifiedMessageIds.size > 800) {
                                    val iter = notifiedMessageIds.iterator()
                                    repeat(200) {
                                        if (iter.hasNext()) {
                                            iter.next()
                                            iter.remove()
                                        }
                                    }
                                }
                            }
                            last = message.id
                        }
                    }
                    if (!page.hasMore || page.messages.isEmpty()) break
                    val next = page.messages.last().id
                    if (next <= after) break
                    after = next
                }
                if (last != settings.lastEventId) {
                    settings.lastEventId = last
                    // 有新消息就进入 2 分钟的快速档：她常常连发两三条
                    burstUntil = System.currentTimeMillis() + 2 * 60_000L
                }
                markConnection(true)
            } catch (e: Exception) {
                Log.w(TAG, "event poll failed: ${e.message}")
                markConnection(false)
            }

            val now = System.currentTimeMillis()
            val gap = when {
                now < burstUntil -> 5_000L
                appVisible -> 30_000L
                // ±15% 抖动：避免多个实例/重连时整齐地同时打
                else -> 45_000L + (0..6750).random() - 3375
            }
            Log.d(TAG, "next poll in ${gap}ms (visible=$appVisible burst=${now < burstUntil})")
            delay(gap.coerceAtLeast(2_000L))
        }
    }

    private fun notifyMessage(message: MessageEntity) {
        notifyText(
            if (message.proactiveKind != null) "大肥鱼 · 主动消息" else "大肥鱼",
            message.content,
            message.id,
        )
    }

    private fun notifyText(title: String, raw: String, sourceId: Long) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val text = replaceFaceMarkers(raw).replace(Regex("\\s+"), " ").trim().take(160)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        unreadCount += 1
        Log.i(TAG, "notify title=$title visible=$appVisible unread=$unreadCount")
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setNumber(unreadCount)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setVibrate(longArrayOf(0, 220, 120, 220))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .build()
        // 每条消息用独立 ID：复用同一个 ID 只会“更新通知”，系统不会再弹悬浮横幅
        val notificationId = MESSAGE_NOTIFICATION_ID + ((sourceId % 100000L).toInt())
        manager.notify(MESSAGE_NOTIFICATION_TAG, notificationId, notification)
        postedMessageNotificationIds.add(notificationId)
        alertFallback()
    }

    /**
     * 小米/HyperOS 有时会静默掉通知渠道的提示音和震动；
     * 手动补一次，保证“有通知就有声音/震动”。
     */
    private fun alertFallback() {
        try {
            RingtoneManager.getRingtone(
                this,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )?.play()
        } catch (e: Exception) {
            Log.w(TAG, "notification sound failed: ${e.message}")
        }
        try {
            val vibrator = getSystemService(Vibrator::class.java)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 220, 120, 220),
                            -1,
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 220, 120, 220), -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "notification vibration failed: ${e.message}")
        }
    }


    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DS-Mobile 后台服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持与 Worker 的连接，接收消息"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val messageChannel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            "DS-Mobile 消息",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "新消息提醒：悬浮横幅、锁屏、角标、声音和震动"
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableLights(true)
            lightColor = 0xFF7C5CFF.toInt()
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 220, 120, 220)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                audioAttributes,
            )
        }
        manager.createNotificationChannel(messageChannel)
        // 旧渠道的 importance/锁屏设置无法原地升级；清掉避免设置页出现重复项。
        manager.deleteNotificationChannel(LEGACY_MESSAGE_CHANNEL_ID)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DS-Mobile 正在运行")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
