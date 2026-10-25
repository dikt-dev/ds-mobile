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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阶段 0 的前台服务 spike：
 * 每 3 秒请求一次 Worker /api/health，更新常驻通知，验证划掉后台后仍能存活。
 *
 * 阶段 1 会把它替换为正式的 AppEventService（增量消息轮询/通知/本地库）。
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
        private const val HEALTH_URL = "https://fae.us.ci/api/health"

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

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveService::class.java),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifiedMessageIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private var loopJob: Job? = null
    private var eventJob: Job? = null
    private var heartbeatJob: Job? = null
    private var proactiveJob: Job? = null
    private lateinit var settings: SettingsStore

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        createNotificationChannel()
        startForegroundCompat(buildNotification("正在启动…"))
        running = true
        lastStatus = "正在启动…"
        loopJob = scope.launch { pollLoop() }
        eventJob = scope.launch {
            delay(1_500)
            eventLoop()
        }
        heartbeatJob = scope.launch {
            delay(2_500)
            heartbeatLoop()
        }
        proactiveJob = scope.launch {
            delay(4_000)
            proactiveLoop()
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
        loopJob?.cancel()
        eventJob?.cancel()
        heartbeatJob?.cancel()
        proactiveJob?.cancel()
        scope.cancel()
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val status = try {
                checkHealth()
            } catch (e: Exception) {
                "ERR ${e.javaClass.simpleName}: ${e.message ?: ""}"
            }
            lastStatus = status
            lastTime = System.currentTimeMillis()
            updateNotification(status)
            Log.d(TAG, "health=$status")
            delay(3_000)
        }
    }

    /** 5 分钟一次心跳；Worker 3 小时收不到才会认为通道掉线。 */
    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            val token = settings.token
            if (!token.isNullOrBlank()) {
                try {
                    ApiClient(settings.baseUrl, token).heartbeat()
                    Log.d(TAG, "heartbeat ok")
                } catch (e: Exception) {
                    Log.w(TAG, "heartbeat failed: ${e.message}")
                }
            }
            delay(5 * 60_000L)
        }
    }

    /** 主动消息队列消费者：消息本身走 events；这里 ACK 清队列并保留推送能力。 */
    private suspend fun proactiveLoop() {
        while (scope.isActive) {
            val token = settings.token
            if (!token.isNullOrBlank()) {
                try {
                    val api = ApiClient(settings.baseUrl, token)
                    val pushes = api.proactivePending()
                    for (push in pushes) {
                        val messageId = push.messageId
                        val alreadyNotified = messageId != null && messageId in notifiedMessageIds
                        if (!appVisible && !alreadyNotified && push.content.isNotBlank()) {
                            notifyProactive(
                                push.content,
                                messageId ?: push.queueId.toLong(),
                            )
                            if (messageId != null) notifiedMessageIds.add(messageId)
                        }
                    }
                    if (pushes.isNotEmpty()) {
                        api.proactiveAck(pushes.map { it.queueId })
                        Log.i(TAG, "proactive acked=${pushes.size}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "proactive poll failed: ${e.message}")
                }
            }
            delay(30_000)
        }
    }

    /** 增量消息轮询：App 在后台时给新助手消息发通知。 */
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

        while (scope.isActive) {
            val token = settings.token
            if (token.isNullOrBlank()) {
                delay(5_000)
                continue
            }
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
                                if (notifiedMessageIds.size > 800) notifiedMessageIds.clear()
                            }
                            last = message.id
                        }
                    }
                    if (!page.hasMore || page.messages.isEmpty()) break
                    val next = page.messages.last().id
                    if (next <= after) break
                    after = next
                }
                if (last != settings.lastEventId) settings.lastEventId = last
            } catch (e: Exception) {
                Log.w(TAG, "event poll failed: ${e.message}")
            }
            delay(5_000)
        }
    }

    private fun notifyMessage(message: MessageEntity) {
        notifyText(
            if (message.proactiveKind != null) "大肥鱼 · 主动消息" else "大肥鱼",
            message.content,
            message.id,
        )
    }

    /** 队列推送：即使 events 轮询还没到，也先把主动消息弹出来。 */
    private fun notifyProactive(content: String, sourceId: Long) {
        notifyText("大肥鱼 · 主动消息", content, sourceId)
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

    private fun checkHealth(): String {
        val connection = (URL(HEALTH_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DS-Mobile/0.1")
        }
        return try {
            val code = connection.responseCode
            if (code in 200..299) {
                "OK HTTP $code · ${SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())}"
            } else {
                "HTTP $code"
            }
        } finally {
            connection.disconnect()
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
