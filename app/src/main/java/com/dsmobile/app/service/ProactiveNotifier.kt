package com.dsmobile.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dsmobile.app.ChatActivity
import com.dsmobile.app.replaceFaceMarkers

/**
 * 「到点提醒」的通知出口。
 *
 * 跟 KeepAliveService 里的消息通知分开：独立 channel，用户可以把「到点提醒」
 * 单独静音，而不影响别的东西。这里不占常驻通知位。
 */
object ProactiveNotifier {

    private const val TAG = "ProactiveNotifier"
    private const val CHANNEL_ID = "ds_proactive"
    private const val NOTIFICATION_TAG = "ds_proactive"
    private const val NOTIFICATION_ID_BASE = 3000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "大肥鱼 · 到点提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "她到点发来消息时提醒你（一天最多几次，不常驻）"
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
        manager.createNotificationChannel(channel)
    }

    /**
     * 弹一条通知。messageId 用来生成独立通知 id：复用同一个 id 只会“更新”，
     * 系统不会再弹悬浮横幅。
     */
    fun notify(context: Context, raw: String, messageId: Long) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) {
            Log.i(TAG, "notifications disabled, skip")
            return
        }
        val text = replaceFaceMarkers(raw).replace(Regex("\\s+"), " ").trim().take(160)
        if (text.isBlank()) return

        val intent = Intent(context, ChatActivity::class.java).apply {
            putExtra("focus_message_id", messageId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("大肥鱼")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .build()

        val id = NOTIFICATION_ID_BASE + ((messageId % 10000L).toInt())
        manager.notify(NOTIFICATION_TAG, id, notification)
        Log.i(TAG, "notified message=$messageId id=$id")
    }
}
