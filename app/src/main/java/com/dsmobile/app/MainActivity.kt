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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.parse.ParseSpike
import com.dsmobile.app.service.DebugSshService
import com.dsmobile.app.service.KeepAliveService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, FGS still can run; notification visibility depends on it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen()
                }
            }
        }
    }

    @Composable
    private fun SpikeScreen() {
        var running by remember { mutableStateOf(KeepAliveService.running) }
        var status by remember { mutableStateOf(KeepAliveService.lastStatus) }
        var lastTime by remember { mutableLongStateOf(KeepAliveService.lastTime) }
        var sshRunning by remember { mutableStateOf(DebugSshService.running) }
        var sshStatus by remember { mutableStateOf(DebugSshService.status) }
        var sshPassword by remember { mutableStateOf(DebugSshService.password) }
        var sshIp by remember { mutableStateOf(DebugSshService.ip) }
        var parseRunning by remember { mutableStateOf(false) }
        var parseResult by remember { mutableStateOf("") }
        var sttRunning by remember { mutableStateOf(false) }
        var sttResult by remember { mutableStateOf("") }
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
                delay(1_000)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "DS-Mobile · 阶段 0 Spike",
                style = MaterialTheme.typography.headlineSmall,
            )
            Button(
                onClick = {
                    startActivity(Intent(this@MainActivity, ChatActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("进入聊天（阶段 1）")
            }
            Text(
                text = "目标：前台服务每 3 秒检查 Worker 健康接口，验证划掉后台仍存活。",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("服务：${if (running) "运行中" else "已停止"}")
                    Text("状态：$status")
                    Text(
                        "最后检查：" + if (lastTime > 0) {
                            SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(lastTime))
                        } else {
                            "—"
                        }
                    )
                }
            }

            Button(
                onClick = {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, KeepAliveService::class.java),
                    )
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("启动后台服务")
            }

            OutlinedButton(
                onClick = {
                    stopService(Intent(this@MainActivity, KeepAliveService::class.java))
                },
                enabled = running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("停止后台服务")
            }

            OutlinedButton(
                onClick = { requestIgnoreBatteryOptimizations() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("电池优化：设为不限制")
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("SSH/SFTP 调试", style = MaterialTheme.typography.titleMedium)
                    Text("状态：$sshStatus")
                    if (sshRunning) {
                        Text("地址：$sshIp:${DebugSshService.PORT}")
                        Text("用户：${DebugSshService.USERNAME}")
                        Text("密码：$sshPassword")
                        Text(
                            "PC：sftp -P ${DebugSshService.PORT} " +
                                "${DebugSshService.USERNAME}@$sshIp",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = {
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                Intent(this@MainActivity, DebugSshService::class.java),
                            )
                        },
                        enabled = !sshRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("启动 SSH/SFTP 调试")
                    }
                    OutlinedButton(
                        onClick = {
                            stopService(Intent(this@MainActivity, DebugSshService::class.java))
                        },
                        enabled = sshRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("停止 SSH/SFTP 调试")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("文件解析 Spike", style = MaterialTheme.typography.titleMedium)
                    Text("生成并解析 50 页 PDF + 100 条目 ZIP，验证 K60 不 OOM。")
                    Button(
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
                        enabled = !parseRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (parseRunning) "解析中…" else "运行 PDF/ZIP 解析")
                    }
                    if (parseResult.isNotEmpty()) {
                        Text(parseResult, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            if (!sttRunning) {
                                sttRunning = true
                                sttResult = "STT 测试中…"
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
                                        sttResult = "STT: $text"
                                    } catch (e: Exception) {
                                        sttResult = "STT 失败：${e.message}"
                                    } finally {
                                        sttRunning = false
                                    }
                                }
                            }
                        },
                        enabled = !sttRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (sttRunning) "STT 测试中…" else "测试 Azure STT（内置语音）")
                    }
                    if (sttResult.isNotEmpty()) {
                        Text(sttResult, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                text = "验证步骤：启动服务 → 划掉最近任务 → 锁屏 8 小时 → 回来看状态是否持续更新。",
                style = MaterialTheme.typography.bodySmall,
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
