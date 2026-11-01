package com.dsmobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.parse.ParseSpike
import com.dsmobile.app.service.DebugSshService
import com.dsmobile.app.service.KeepAliveService
import com.dsmobile.app.ui.components.DsButton
import com.dsmobile.app.ui.components.DsCard
import com.dsmobile.app.ui.components.DsDivider
import com.dsmobile.app.ui.components.DsDot
import com.dsmobile.app.ui.components.DsIconButton
import com.dsmobile.app.ui.components.DsOutlineButton
import com.dsmobile.app.ui.components.DsSettingRow
import com.dsmobile.app.ui.theme.Ds
import com.dsmobile.app.ui.theme.DsSpace
import com.dsmobile.app.ui.theme.DsTheme
import com.dsmobile.app.ui.theme.rememberDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 调试页：只在设置里点「连接状态与诊断」时进来，日常聊天看不到这一页。 */
class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, FGS still can run; notification visibility depends on it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 调试：adb shell am start -n com.dsmobile.app/.MainActivity --es debug_notify 1
        if (intent?.getStringExtra("debug_notify") != null) {
            com.dsmobile.app.service.ProactiveNotifier.notify(this, "测试通知：本地通知链路正常", 999999L)
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings = SettingsStore(this)
            DsTheme(darkTheme = rememberDarkTheme(settings.themeMode)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Ds.colors.canvas) {
                    DiagnoseScreen()
                }
            }
        }
    }

    @Composable
    private fun DiagnoseScreen() {
        var running by remember { mutableStateOf(KeepAliveService.running) }
        var status by remember { mutableStateOf(KeepAliveService.lastStatus) }
        var lastTime by remember { mutableLongStateOf(KeepAliveService.lastTime) }
        var sshRunning by remember { mutableStateOf(DebugSshService.running) }
        var sshStatus by remember { mutableStateOf(DebugSshService.status) }
        var sshPassword by remember { mutableStateOf(DebugSshService.password) }
        var sshIp by remember { mutableStateOf(DebugSshService.ip) }
        var notifyOn by remember {
            mutableStateOf(SettingsStore(this@MainActivity).proactiveNotifyEnabled)
        }
        var scheduleText by remember { mutableStateOf("") }
        var wakeText by remember { mutableStateOf("") }
        var parseRunning by remember { mutableStateOf(false) }
        var parseResult by remember { mutableStateOf("") }
        var sttRunning by remember { mutableStateOf(false) }
        var sttResult by remember { mutableStateOf("") }
    var resyncing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            while (true) {
                running = KeepAliveService.running
                status = KeepAliveService.lastStatus
                lastTime = KeepAliveService.lastTime
                sshRunning = DebugSshService.running
                sshStatus = DebugSshService.status
                sshPassword = DebugSshService.password
                sshIp = DebugSshService.ip
                notifyOn = SettingsStore(this@MainActivity).proactiveNotifyEnabled
                scheduleText = com.dsmobile.app.service.ProactiveScheduler.scheduleSummary(this@MainActivity)
                wakeText = com.dsmobile.app.service.ProactiveScheduler.wakeSummary(this@MainActivity)
                delay(1_000)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Ds.colors.canvas)
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ds.colors.surface)
                    .padding(start = 6.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsIconButton(Icons.Filled.Close, "关闭", onClick = { finish() })
                Text(
                    "连接与诊断",
                    style = MaterialTheme.typography.titleLarge,
                    color = Ds.colors.ink,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
            DsDivider()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(DsSpace.md),
                verticalArrangement = Arrangement.spacedBy(DsSpace.md),
            ) {
                // ── 前台服务 ──────────────────────────────────────
                DsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DsDot(if (running) Ds.colors.success else Ds.colors.inkTertiary, 8.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "后台保活",
                            style = MaterialTheme.typography.titleMedium,
                            color = Ds.colors.ink,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (running) "运行中" else "已停止",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (running) Ds.colors.success else Ds.colors.inkTertiary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "常驻时：每 5 秒拉一次消息、每 5 分钟报心跳、每 30 秒拉一次主动消息。默认关闭。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkTertiary,
                    )
                    Spacer(Modifier.height(DsSpace.sm))
                    KvRow("状态", status.ifBlank { "—" })
                    KvRow(
                        "最后检查",
                        if (lastTime > 0) {
                            SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(lastTime))
                        } else {
                            "—"
                        },
                    )
                    Spacer(Modifier.height(DsSpace.sm))
                    // 手动开关与设置里的「后台常驻」是同一个开关，避免两处状态打架
                    DsButton(
                        "启动后台服务",
                        enabled = !running,
                        onClick = {
                            SettingsStore(this@MainActivity).keepAliveEnabled = true
                            KeepAliveService.start(this@MainActivity)
                        },
                    )
                    Spacer(Modifier.height(DsSpace.xs))
                    DsOutlineButton(
                        "停止后台服务",
                        enabled = running,
                        onClick = {
                            SettingsStore(this@MainActivity).keepAliveEnabled = false
                            KeepAliveService.stop(this@MainActivity)
                        },
                    )
                    DsOutlineButton(
                        "电池优化：设为不限制",
                        onClick = { requestIgnoreBatteryOptimizations() },
                    )
                }

                // ── 到点提醒（闹钟）────────────────────────────
                DsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DsDot(
                            if (notifyOn) Ds.colors.success else Ds.colors.inkTertiary,
                            8.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "到点提醒（闹钟）",
                            style = MaterialTheme.typography.titleMedium,
                            color = Ds.colors.ink,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (notifyOn) "已开启" else "已关闭",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (notifyOn) Ds.colors.success else Ds.colors.inkTertiary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "一天最多 4 次唤醒，每次一个请求。不常驻、不需要精确闹钟权限；" +
                            "系统拖后十几分钟也不影响（窗口本身就宽）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkTertiary,
                    )
                    Spacer(Modifier.height(DsSpace.sm))
                    KvRow("下次唤醒", scheduleText.ifBlank { "—" })
                    Spacer(Modifier.height(DsSpace.xs))
                    KvRow("最近唤醒", wakeText.ifBlank { "—" })
                    Spacer(Modifier.height(DsSpace.sm))
                    DsButton(
                        "30 秒后自测触发",
                        onClick = {
                            com.dsmobile.app.service.ProactiveScheduler.triggerIn(
                                this@MainActivity,
                                30,
                            )
                        },
                    )
                    Spacer(Modifier.height(DsSpace.xs))
                    DsOutlineButton(
                        "测试通知",
                        onClick = {
                            com.dsmobile.app.service.ProactiveNotifier.notify(
                                this@MainActivity,
                                "测试通知：这条是自测，不是她说的。",
                                System.currentTimeMillis(),
                            )
                        },
                    )
                    Spacer(Modifier.height(DsSpace.xs))
                    DsOutlineButton(
                        "重新计算闹钟",
                        onClick = {
                            scope.launch {
                                com.dsmobile.app.service.ProactiveScheduler.refresh(this@MainActivity)
                            }
                        },
                    )
                    Spacer(Modifier.height(DsSpace.xs))
                    // 服务端删了消息，本地 Room 不会自己知道（没有墓碑机制），
                    // 真对不上时用这个：清空本地再从服务端整份重拉。
                    DsOutlineButton(
                        text = if (resyncing) "正在重拉…" else "清空本地记录并重新同步",
                        onClick = {
                        if (resyncing) return@DsOutlineButton
                        scope.launch {
                            resyncing = true
                            try {
                                val dao = com.dsmobile.app.data.AppDatabase.get(this@MainActivity).messageDao()
                                dao.deleteAllMessages()
                                dao.deleteAllPending()
                                val settings = SettingsStore(this@MainActivity)
                                val api = ApiClient(
                                    settings.baseUrl,
                                    settings.token,
                                    settings.biliSessdata,
                                    settings.biliCookie,
                                )
                                var after = 0L
                                var total = 0
                                while (true) {
                                    val page = api.events(after, 100)
                                    if (page.messages.isEmpty()) break
                                    dao.insertAll(page.messages)
                                    total += page.messages.size
                                    val next = page.messages.last().id
                                    if (!page.hasMore || next <= after) break
                                    after = next
                                }
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "已重拉 $total 条",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "重拉失败：${e.message}",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                resyncing = false
                            }
                        }
                        },
                    )
                }

                // ── SSH / SFTP ───────────────────────────────────
                DsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DsDot(if (sshRunning) Ds.colors.success else Ds.colors.inkTertiary, 8.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "SSH / SFTP 调试",
                            style = MaterialTheme.typography.titleMedium,
                            color = Ds.colors.ink,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (sshRunning) "运行中" else "已停止",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sshRunning) Ds.colors.success else Ds.colors.inkTertiary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sshStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkTertiary,
                    )
                    if (sshRunning) {
                        Spacer(Modifier.height(DsSpace.sm))
                        KvRow("地址", "${sshIp}:${DebugSshService.PORT}")
                        KvRow("用户", DebugSshService.USERNAME)
                        KvRow("密码", sshPassword)
                        KvRow(
                            "电脑连",
                            "sftp -P ${DebugSshService.PORT} ${DebugSshService.USERNAME}@$sshIp",
                        )
                    }
                    Spacer(Modifier.height(DsSpace.sm))
                    DsButton(
                        "启动 SSH / SFTP",
                        enabled = !sshRunning,
                        onClick = {
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                Intent(this@MainActivity, DebugSshService::class.java),
                            )
                        },
                    )
                    Spacer(Modifier.height(DsSpace.xs))
                    DsOutlineButton(
                        "停止 SSH / SFTP",
                        enabled = sshRunning,
                        onClick = {
                            stopService(Intent(this@MainActivity, DebugSshService::class.java))
                        },
                    )
                }

                // ── 自测 ─────────────────────────────────────────
                DsCard {
                    Text(
                        "本机自测",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ds.colors.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "只在这台手机上跑，不影响聊天。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkTertiary,
                    )
                    Spacer(Modifier.height(DsSpace.md))
                    DsOutlineButton(
                        if (parseRunning) "解析中…" else "解析 PDF / ZIP",
                        enabled = !parseRunning,
                        onClick = {
                            if (!parseRunning) {
                                parseRunning = true
                                parseResult = "解析中…"
                                scope.launch {
                                    parseResult = ParseSpike.run(this@MainActivity)
                                    parseRunning = false
                                }
                            }
                        },
                    )
                    if (parseResult.isNotEmpty()) {
                        Spacer(Modifier.height(DsSpace.xs))
                        Text(
                            parseResult,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ds.colors.inkSecondary,
                        )
                    }
                    Spacer(Modifier.height(DsSpace.md))
                    DsOutlineButton(
                        if (sttRunning) "识别中…" else "测试 Azure 语音识别",
                        enabled = !sttRunning,
                        onClick = {
                            if (!sttRunning) {
                                sttRunning = true
                                sttResult = "识别中…"
                                scope.launch {
                                    try {
                                        val settings = SettingsStore(this@MainActivity)
                                        val api = ApiClient(
                                            settings.baseUrl,
                                            settings.token,
                                            settings.biliSessdata,
                                            settings.biliCookie,
                                        )
                                        val wav = assets.open("test/stt-test.wav").readBytes()
                                        val text = api.stt(wav, settings.azureKey, settings.azureRegion)
                                        sttResult = "识别结果：$text"
                                    } catch (e: Exception) {
                                        sttResult = "识别失败：${e.message}"
                                    } finally {
                                        sttRunning = false
                                    }
                                }
                            }
                        },
                    )
                    if (sttResult.isNotEmpty()) {
                        Spacer(Modifier.height(DsSpace.xs))
                        Text(
                            sttResult,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ds.colors.inkSecondary,
                        )
                    }
                }

                Text(
                    "验收方法：启动服务 → 划掉最近任务 → 锁屏 8 小时 → 回来看看状态有没有一直在更新。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ds.colors.inkTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(DsSpace.lg))
            }
        }
    }

    @Composable
    private fun KvRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = Ds.colors.inkTertiary,
                modifier = Modifier.width(64.dp),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Ds.colors.inkSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在电池优化白名单", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "无法直接打开，请到系统设置里允许后台运行", Toast.LENGTH_LONG).show()
        }
    }
}
