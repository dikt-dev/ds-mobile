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
 * 一分钟一轮：心跳 + 增量同步各一次。
 *
 * 连续 3 次心跳没回来就在常驻通知上写「离线」——服务被系统冻住时，
 * 通知栏那行字是唯一能看出「其实早就断了」的地方。
 *
 * 关掉常驻后仍然能收消息：「到点拉取」闹钟（ProactiveScheduler）照样兜底，
 * 主动消息按档位、她被闸门压住按 until_ms。
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
            pollLoop()
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
    /**
     * 一分钟一轮：先心跳，再同步增量。
     *
     * 心跳连不上 = 网络断了/Worker 挂了，这时候同步也会失败，先报离线再说。
     * 三次没回就写死「离线」，不再反复显示「重连中」——那个词看不出到底断了多久。
     */
    private suspend fun pollLoop() {
        var failures = 0
        while (scope.isActive) {
            val token = settings.token
            if (token.isNullOrBlank()) {
                delay(60_000)
                continue
            }
            try {
                ApiClient(settings.baseUrl, token).heartbeat()
                failures = 0
                lastStatus = "已连接"
                lastTime = System.currentTimeMillis()
            } catch (e: Exception) {
                failures += 1
                lastTime = System.currentTimeMillis()
                lastStatus = if (failures >= 3) "离线（心跳 $failures 次没回）" else "重连中…"
                Log.w(TAG, "heartbeat failed ($failures): ${e.message}")
            }
            if (failures == 0) Log.i(TAG, "tick: heartbeat ok")
            updateNotification(lastStatus)

            // 心跳没通就别再打同步请求，省一次必然失败的往返
            if (failures < 3) {
                runCatching { pullOnce() }
            }
            delay(60_000)
        }
    }

    /** 拉一次增量（只走一页到底，不循环等待）。 */
    private suspend fun pullOnce() {
        // 首次启动先取最新 id 作为基线，避免把历史消息全部弹一遍。
        // 最多试 3 次：不做「失败就一直重试」，那又是另一种轮询。
        var tries = 0
        while (settings.lastEventId <= 0 && tries < 3) {
            val token = settings.token ?: break
            tries += 1
            try {
                val page = ApiClient(settings.baseUrl, token).events(0, 1, latest = true)
                settings.lastEventId = page.latestId
                Log.i(TAG, "event baseline=${page.latestId}")
            } catch (e: Exception) {
                Log.w(TAG, "event baseline failed ($tries/3): ${e.message}")
                if (tries < 3) delay(5_000)
            }
        }

        run {
            val token = settings.token
            if (token.isNullOrBlank()) return
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
                }
                markConnection(true)
            } catch (e: Exception) {
                Log.w(TAG, "event poll failed: ${e.message}")
                markConnection(false)
            }

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
