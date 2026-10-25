package com.dsmobile.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dsmobile.app.ChatActivity
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
        private const val MESSAGE_CHANNEL_ID = "ds_messages"
        private const val NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_ID = 2001
        private const val HEALTH_URL = "https://fae.us.ci/api/health"

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
    private var loopJob: Job? = null
    private var eventJob: Job? = null
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
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, keep running")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        loopJob?.cancel()
        eventJob?.cancel()
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
                var last = settings.lastEventId
                val page = ApiClient(settings.baseUrl, token).events(last, 20)
                for (message in page.messages) {
                    if (message.id > last) {
                        if (message.role == "assistant" && !appVisible) {
                            notifyMessage(message)
                        }
                        last = message.id
                    }
                }
                if (last != settings.lastEventId) settings.lastEventId = last
            } catch (e: Exception) {
                Log.w(TAG, "event poll failed: ${e.message}")
            }
            delay(5_000)
        }
    }

    private fun notifyMessage(message: MessageEntity) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val text = message.content.replace(Regex("\\s+"), " ").trim().take(160)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle("大肥鱼")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(MESSAGE_NOTIFICATION_ID, notification)
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

        val messageChannel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            "DS-Mobile 消息",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "新消息提醒"
        }
        manager.createNotificationChannel(messageChannel)
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
