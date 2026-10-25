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
