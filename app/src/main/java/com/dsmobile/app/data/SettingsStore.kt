package com.dsmobile.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsStore(context: Context) {
    companion object {
        private const val TAG = "SettingsStore"
        private const val SECURE_NAME = "ds_mobile_settings_secure"
        private const val LEGACY_NAME = "ds_mobile_settings"
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val secure = EncryptedSharedPreferences.create(
            context,
            SECURE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        migrateLegacy(context, secure)
        secure
    } catch (e: Exception) {
        Log.e(TAG, "encrypted prefs unavailable, fallback to plain", e)
        context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs.getString("base_url", "https://fae.us.ci") ?: "https://fae.us.ci"
        set(value) = prefs.edit().putString("base_url", value.trimEnd('/')).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().putString("token", value).apply()

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    var lastEventId: Long
        get() = prefs.getLong("last_event_id", 0L)
        set(value) = prefs.edit().putLong("last_event_id", value).apply()

    /**
     * 后台常驻（前台服务）：**默认关闭**（2026-10-31 改）。
     *
     * 开着才有「锁屏也能收到她消息 + 主动消息通知」；代价是一条常驻通知、
     * 每 5 秒一次 /api/app/events 轮询（一天约 1.7 万次 D1 读）和一点电。
     * 关着时 App 只在打开期间收消息，最省电。
     */
    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean("keep_alive_enabled", false)
        set(value) = prefs.edit().putBoolean("keep_alive_enabled", value).apply()

    /**
     * 「到点提醒」：闹钟在计划时刻前 12 分钟醒来，拉一次消息并弹本地通知。
     *
     * 不常驻、不轮询：一天最多 4 次唤醒，每次一个请求。
     * 默认开着——这是后台保活退休后的通知手段。
     */
    var proactiveNotifyEnabled: Boolean
        get() = prefs.getBoolean("proactive_notify_enabled", true)
        set(value) = prefs.edit().putBoolean("proactive_notify_enabled", value).apply()

    /** 已经弹过通知的最大消息 id，避免同一条反复提醒。 */
    var lastNotifiedId: Long
        get() = prefs.getLong("last_notified_id", 0L)
        set(value) = prefs.edit().putLong("last_notified_id", value).apply()

    /** 最近几次唤醒记录（JSON 数组），调试页展示「闹钟延迟」用。 */
    var proactiveWakeLog: String?
        get() = prefs.getString("proactive_wake_log", null)
        set(value) = prefs.edit().putString("proactive_wake_log", value).apply()

    /** 当前挂着的闹钟（JSON 数组），调试页展示下次唤醒时间用。 */
    var proactiveSchedule: String?
        get() = prefs.getString("proactive_schedule", null)
        set(value) = prefs.edit().putString("proactive_schedule", value).apply()

    var azureKey: String
        get() = prefs.getString("azure_key", "") ?: ""
        set(value) = prefs.edit().putString("azure_key", value.trim()).apply()

    var azureRegion: String
        get() = prefs.getString("azure_region", "eastus") ?: "eastus"
        set(value) = prefs.edit().putString("azure_region", value.trim()).apply()

    var azureVoice: String
        get() = prefs.getString("azure_voice", "zh-CN-XiaoyiNeural") ?: "zh-CN-XiaoyiNeural"
        set(value) = prefs.edit().putString("azure_voice", value.trim()).apply()

    var azureRate: String
        get() = prefs.getString("azure_rate", "+5%") ?: "+5%"
        set(value) = prefs.edit().putString("azure_rate", value.trim()).apply()

    var azurePitch: String
        get() = prefs.getString("azure_pitch", "+3Hz") ?: "+3Hz"
        set(value) = prefs.edit().putString("azure_pitch", value.trim()).apply()

    var azureStyleDegree: String
        get() = prefs.getString("azure_style_degree", "1.2") ?: "1.2"
        set(value) = prefs.edit().putString("azure_style_degree", value.trim()).apply()

    var biliSessdata: String
        get() = prefs.getString("bili_sessdata", "") ?: ""
        set(value) = prefs.edit().putString("bili_sessdata", value.trim()).apply()

    var biliCookie: String
        get() = prefs.getString("bili_cookie", "") ?: ""
        set(value) = prefs.edit().putString("bili_cookie", value.trim()).apply()

    var sshPublicKey: String
        get() = prefs.getString("ssh_public_key", "") ?: ""
        set(value) = prefs.edit().putString("ssh_public_key", value.trim()).apply()

    /** 外观：system / light / dark */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun migrateLegacy(context: Context, secure: SharedPreferences) {
        if (secure.contains("token")) return
        val legacy = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        val token = legacy.getString("token", null) ?: return
        secure.edit()
            .putString("base_url", legacy.getString("base_url", "https://fae.us.ci"))
            .putString("token", token)
            .putString("device_id", legacy.getString("device_id", null))
            .putLong("last_event_id", legacy.getLong("last_event_id", 0L))
            .apply()
        legacy.edit().clear().apply()
        Log.i(TAG, "migrated legacy token to encrypted prefs")
    }
}
