package com.dsmobile.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机广播：闹钟不跨重启，开机后必须重新挂上「到点提醒」。
 *
 * 顺带保留后台常驻的启动（仅在用户手动开过时才生效，KeepAliveService.start
 * 自己会检查开关）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        Log.i("BootReceiver", "boot completed, re-arm proactive alarms")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ProactiveScheduler.refresh(app) }
                .onFailure { Log.w("BootReceiver", "re-arm failed: ${it.message}") }
        }
        KeepAliveService.start(app)
    }
}
