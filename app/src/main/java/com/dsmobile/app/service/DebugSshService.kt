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
import com.dsmobile.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.compression.BuiltinCompressions
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityProviderChoice
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.kex.DHGServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.security.Security

/**
 * 阶段 0 的 SSH/SFTP spike：局域网内用密码登录，SFTP 根目录为 App 私有 filesDir。
 * 正式版会改为公钥登录、IP 白名单、30 分钟自动关闭。
 */
class DebugSshService : Service() {

    companion object {
        private const val TAG = "DebugSshService"
        private const val CHANNEL_ID = "ds_debug"
        private const val NOTIFICATION_ID = 1002
        const val PORT = 8022
        const val USERNAME = "dsmobile"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var status: String = "未启动"
            private set

        @Volatile
        var password: String = ""
            private set

        @Volatile
        var ip: String = ""
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DebugSshService::class.java),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sshd: SshServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification("正在启动…"))
        password = randomPassword()
        ip = localIp()
        status = "正在启动…"
        scope.launch { startServer() }
        // 调试口默认 30 分钟后自动关闭，避免长期暴露在局域网。
        scope.launch {
            kotlinx.coroutines.delay(30L * 60L * 1000L)
            if (running) {
                status = "已自动关闭（30 分钟）"
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        status = "已停止"
        runCatching { sshd?.stop(true) }
        sshd = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        try {
            // Android 没有 /home，MINA SSHD 启动前必须给它一个 home 解析器
            PathUtils.setUserHomeFolderResolver { filesDir.toPath() }
            // Android 自带的 BC 版本较旧；优先用 AndroidOpenSSL(Conscrypt) 提供 SHA-256
            runCatching {
                val provider = Security.getProvider("AndroidOpenSSL")
                    ?: Security.getProvider("Conscrypt")
                if (provider != null) {
                    SecurityUtils.setDefaultProviderChoice(
                        SecurityProviderChoice.toSecurityProviderChoice(provider)
                    )
                }
            }
            val server = SshServer.setUpDefaultServer()
            if (server.keyExchangeFactories.isNullOrEmpty()) {
                // Android 上默认 KEX 列表可能为空，显式使用 group14
                server.keyExchangeFactories = listOf(
                    DHGServer.newFactory(BuiltinDHFactories.dhg14_256),
                    DHGServer.newFactory(BuiltinDHFactories.dhg14),
                )
            }
            server.port = PORT
            server.host = "0.0.0.0"
            server.keyPairProvider = SimpleGeneratorHostKeyProvider(
                File(filesDir, "debug_hostkey.ser").toPath()
            ).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            if (server.signatureFactories.isNullOrEmpty()) {
                server.signatureFactories = listOf(
                    BuiltinSignatures.rsaSHA256,
                    BuiltinSignatures.rsaSHA512,
                    BuiltinSignatures.rsa,
                )
            }
            if (server.cipherFactories.isNullOrEmpty()) {
                server.cipherFactories = listOf(
                    BuiltinCiphers.aes128ctr,
                    BuiltinCiphers.aes192ctr,
                    BuiltinCiphers.aes256ctr,
                )
            }
            if (server.macFactories.isNullOrEmpty()) {
                server.macFactories = listOf(
                    BuiltinMacs.hmacsha256,
                    BuiltinMacs.hmacsha512,
                )
            }
            if (server.compressionFactories.isNullOrEmpty()) {
                server.compressionFactories = listOf(BuiltinCompressions.none)
            }
            Log.i(
                TAG,
                "kex=${server.keyExchangeFactories?.joinToString { it.name }} " +
                    "sig=${server.signatureFactories?.joinToString { it.name }} " +
                    "cipher=${server.cipherFactories?.joinToString { it.name }} " +
                    "mac=${server.macFactories?.joinToString { it.name }}",
            )
            val authorizedKeys = File(filesDir, "authorized_keys")
                .takeIf { it.isFile }
                ?.readLines()
                ?.mapNotNull { line ->
                    runCatching {
                        AuthorizedKeyEntry.parseAuthorizedKeyEntry(line.trim())
                            .resolvePublicKey(null, PublicKeyEntryResolver.IGNORING)
                    }.getOrNull()
                }
                .orEmpty()
            if (authorizedKeys.isNotEmpty()) {
                server.passwordAuthenticator = PasswordAuthenticator { _, _, _ -> false }
                server.publickeyAuthenticator = PublickeyAuthenticator { username, key, _ ->
                    username == USERNAME && authorizedKeys.any { it == key }
                }
                Log.i(TAG, "auth=publickey keys=${authorizedKeys.size}")
            } else {
                server.passwordAuthenticator = PasswordAuthenticator { username, pass, _ ->
                    username == USERNAME && pass == password
                }
                Log.i(TAG, "auth=password")
            }
            server.subsystemFactories = listOf(SftpSubsystemFactory())
            server.fileSystemFactory = VirtualFileSystemFactory(filesDir.toPath())
            server.start()
            sshd = server
            running = true
            status = "运行中"
            updateNotification("sftp -P $PORT $USERNAME@$ip")
            Log.i(TAG, "SFTP started on $PORT")
        } catch (e: Throwable) {
            running = false
            status = "启动失败：${e.message}"
            Log.e(TAG, "SFTP start failed", e)
            updateNotification(status)
            stopSelf()
        }
    }

    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..12).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun localIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addresses = nif.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        return address.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "localIp failed", e)
        }
        return "未知"
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DS-Mobile 调试服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "SSH/SFTP 调试"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DS-Mobile 调试服务")
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
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
