package com.dsmobile.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.speech.TtsConfigSync
import com.dsmobile.app.speech.TtsManager
import com.dsmobile.app.ui.components.DsButton
import com.dsmobile.app.ui.components.DsCard
import com.dsmobile.app.ui.components.DsDivider
import com.dsmobile.app.ui.components.DsField
import com.dsmobile.app.ui.components.DsIconButton
import com.dsmobile.app.ui.components.DsOutlineButton
import com.dsmobile.app.ui.components.DsSectionLabel
import com.dsmobile.app.ui.components.DsSegmented
import com.dsmobile.app.ui.components.DsSettingRow
import com.dsmobile.app.ui.components.DsSwitchRow
import com.dsmobile.app.service.KeepAliveService
import com.dsmobile.app.ui.components.DsTextButton
import com.dsmobile.app.ui.theme.Ds
import com.dsmobile.app.ui.theme.DsSpace
import kotlinx.coroutines.launch

/** 试听固定念这一句：有语气、够短，能听出 style 有没有生效。 */
private const val PREVIEW_TEXT = "哼，先听听这个声音顺不顺耳。"

/**
 * 设置页：整屏、分段、每个输入框上面有标签。
 *
 * 分组顺序按「先用到的在上面」：声音 → 哔哩哔哩 → 服务器 → 通知 → 关于。
 * 改完统一保存，避免每改一个字段就发一次请求。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onClose: () -> Unit,
    onBiliLogin: () -> Unit,
    onNotificationSettings: () -> Unit,
    onOpenDebug: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(settings.azureKey) }
    var region by remember { mutableStateOf(settings.azureRegion) }
    var voice by remember { mutableStateOf(settings.azureVoice) }
    var rate by remember { mutableStateOf(settings.azureRate) }
    var pitch by remember { mutableStateOf(settings.azurePitch) }
    var styleDegree by remember { mutableStateOf(settings.azureStyleDegree) }
    var biliSessdata by remember { mutableStateOf(settings.biliSessdata) }
    var sshKey by remember { mutableStateOf(settings.sshPublicKey) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var previewing by remember { mutableStateOf(false) }
    var previewMsg by remember { mutableStateOf("") }
    // 后台常驻是即时生效的（不是保存才生效），因为它控制一个正在跑的服务
    var keepAliveOn by remember { mutableStateOf(settings.keepAliveEnabled) }
    // 到点提醒也是即时生效：改了就要立刻重挂/取消闹钟
    var notifyOn by remember { mutableStateOf(settings.proactiveNotifyEnabled) }
    // 清 Token 是不可逆操作，二次确认一次，避免在设置里滑一下就掉登录
    var confirmAction by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save() {
        settings.baseUrl = baseUrl
        settings.azureKey = key
        settings.azureRegion = region
        settings.azureVoice = voice
        settings.azureRate = rate
        settings.azurePitch = pitch
        settings.azureStyleDegree = styleDegree
        settings.biliSessdata = biliSessdata
        settings.sshPublicKey = sshKey
        // 音色参数同步到云端，网页端用同一份配置合成
        TtsConfigSync.push(settings)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ds.colors.canvas)
            .statusBarsPadding()
            .imePadding(),
    ) {
                // 顶栏：关闭 / 标题 / 保存
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ds.colors.surface)
                        .padding(start = 6.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsIconButton(Icons.Filled.Close, "关闭", onClick = onClose)
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLarge,
                        color = Ds.colors.ink,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    DsTextButton(
                        "保存",
                        onClick = { save(); onClose() },
                    )
                }
                DsDivider()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DsSpace.md, vertical = DsSpace.md),
                    verticalArrangement = Arrangement.spacedBy(DsSpace.lg),
                ) {
                    // ── 外观 ───────────────────────────────────────────
                    Column {
                        DsSectionLabel("外观")
                        DsCard {
                            Text(
                                "深色模式跟随系统，或者自己固定一种。改完立刻生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ds.colors.inkTertiary,
                            )
                            Spacer(Modifier.height(DsSpace.sm))
                            DsSegmented(
                                options = listOf(
                                    "system" to "跟随系统",
                                    "light" to "浅色",
                                    "dark" to "深色",
                                ),
                                selected = themeMode,
                                onSelect = onThemeModeChange,
                            )
                        }
                    }

                    // ── 声音 ───────────────────────────────────────────
                    Column {
                        DsSectionLabel("声音")
                        DsCard {
                            Text(
                                "Azure 语音合成。Key 只存在这台手机上，音色参数会同步到云端给网页用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ds.colors.inkTertiary,
                            )
                            Spacer(Modifier.height(DsSpace.sm))
                            DsField(
                                label = "Azure Speech Key",
                                value = key,
                                onValueChange = { key = it },
                                placeholder = "留空则用手机自带语音",
                                password = true,
                            )
                            Spacer(Modifier.height(DsSpace.sm))
                            DsField(
                                label = "Region",
                                value = region,
                                onValueChange = { region = it },
                                placeholder = "eastus / japaneast",
                            )
                            Spacer(Modifier.height(DsSpace.sm))
                            DsField(
                                label = "音色",
                                value = voice,
                                onValueChange = { voice = it },
                                placeholder = "zh-CN-XiaoyiNeural",
                            )
                            Spacer(Modifier.height(DsSpace.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.sm)) {
                                Box(Modifier.weight(1f)) {
                                    DsField(
                                        label = "语速",
                                        value = rate,
                                        onValueChange = { rate = it },
                                        placeholder = "+5%",
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    DsField(
                                        label = "音调",
                                        value = pitch,
                                        onValueChange = { pitch = it },
                                        placeholder = "+3Hz",
                                    )
                                }
                            }
                            Spacer(Modifier.height(DsSpace.sm))
                            DsField(
                                label = "情绪强度",
                                value = styleDegree,
                                onValueChange = { styleDegree = it },
                                placeholder = "0.5 – 2.0，默认 1.2",
                            )
                            Spacer(Modifier.height(DsSpace.md))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DsButton(
                                    text = if (previewing) "合成中…" else "试听",
                                    fillWidth = false,
                                    loading = previewing,
                                    enabled = key.isNotBlank(),
                                    onClick = {
                                        previewing = true
                                        previewMsg = ""
                                        scope.launch {
                                            val result = TtsManager.preview(
                                                context = context,
                                                settings = settings,
                                                text = PREVIEW_TEXT,
                                                voice = voice,
                                                rate = rate,
                                                pitch = pitch,
                                                styleDegree = styleDegree,
                                            )
                                            previewing = false
                                            previewMsg = result.fold(
                                                onSuccess = { "" },
                                                onFailure = { e ->
                                                    val msg = e.message.orEmpty()
                                                    when {
                                                        msg.contains("401") || msg.contains("403") -> "Key 好像不对"
                                                        msg.contains("Azure Key") -> "先填 Azure Key"
                                                        msg.contains("HTTP") -> "试听失败（$msg）"
                                                        else -> "试听失败了，检查一下网络"
                                                    }
                                                },
                                            )
                                        }
                                    },
                                )
                                Spacer(Modifier.width(DsSpace.sm))
                                Text(
                                    previewMsg.ifEmpty {
                                        if (key.isBlank()) "填了 Key 才能试听" else "用上面填的参数念一句"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (previewMsg.isEmpty()) Ds.colors.inkTertiary else Ds.colors.danger,
                                )
                            }
                        }
                    }

                    // ── 哔哩哔哩 ───────────────────────────────────────
                    Column {
                        DsSectionLabel("哔哩哔哩")
                        DsCard {
                            Text(
                                "登录后才能把视频连同字幕、画面一起发给她。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ds.colors.inkTertiary,
                            )
                            Spacer(Modifier.height(DsSpace.sm))
                            DsField(
                                label = "SESSDATA",
                                value = biliSessdata,
                                onValueChange = { biliSessdata = it },
                                placeholder = "浏览器 Cookie 里的 SESSDATA",
                                password = true,
                            )
                            Spacer(Modifier.height(DsSpace.md))
                            DsOutlineButton(
                                "在 App 里登录 B 站",
                                onClick = { save(); onBiliLogin() },
                            )
                        }
                    }

                    // ── 服务器 ─────────────────────────────────────────
                    Column {
                        DsSectionLabel("服务器")
                        DsCard {
                            DsField(
                                label = "服务器地址",
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                placeholder = "https://fae.us.ci",
                            )
                            Spacer(Modifier.height(DsSpace.sm))
                            Text(
                                "设备标识：${settings.deviceId.take(8)}…   改地址后需要重新配对。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ds.colors.inkTertiary,
                            )
                            Spacer(Modifier.height(DsSpace.md))
                            DsOutlineButton(
                                "重新配对",
                                onClick = { confirmAction = "repair" },
                                danger = true,
                            )
                        }
                    }

                    // ── 通知 ───────────────────────────────────────────
                    Column {
                        DsSectionLabel("通知")
                        DsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            DsSwitchRow(
                                title = "到点提醒",
                                checked = notifyOn,
                                subtitle = if (notifyOn) {
                                    "一天最多 4 次唤醒，她到点发消息时弹通知（不常驻）"
                                } else {
                                    "关掉后只有打开 App 才看到她的主动消息"
                                },
                                onCheckedChange = { on ->
                                    notifyOn = on
                                    settings.proactiveNotifyEnabled = on
                                    scope.launch {
                                        if (on) {
                                            com.dsmobile.app.service.ProactiveScheduler.refresh(context)
                                        } else {
                                            com.dsmobile.app.service.ProactiveScheduler.cancelAll(context)
                                        }
                                    }
                                },
                            )
                            DsDivider(Modifier.padding(start = 16.dp))
                            DsSwitchRow(
                                title = "后台常驻",
                                checked = keepAliveOn,
                                subtitle = if (keepAliveOn) {
                                    "锁屏也能收到她的消息；代价是一条常驻通知，每分钟同步一次，连续 3 次没回就报离线"
                                } else {
                                    "只在打开 App 时收消息，最省电（默认）"
                                },
                                onCheckedChange = { on ->
                                    keepAliveOn = on
                                    settings.keepAliveEnabled = on
                                    if (on) KeepAliveService.start(context) else KeepAliveService.stop(context)
                                },
                            )
                            DsDivider(Modifier.padding(start = 16.dp))
                            DsSettingRow(
                                title = "系统通知设置",
                                subtitle = "她主动找你的时候，能不能弹出来",
                                onClick = { save(); onNotificationSettings() },
                            )
                        }
                    }

                    // ── 调试 ───────────────────────────────────────────
                    Column {
                        DsSectionLabel("调试")
                        DsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            DsSettingRow(
                                title = "连接状态与诊断",
                                subtitle = "后台服务、SSH/SFTP、解析自测",
                                onClick = onOpenDebug,
                            )
                            DsDivider(Modifier.padding(start = 16.dp))
                            Column(Modifier.padding(start = DsSpace.md, end = DsSpace.md, top = DsSpace.xs, bottom = DsSpace.sm)) {
                                DsField(
                                    label = "SSH 公钥",
                                    value = sshKey,
                                    onValueChange = { sshKey = it },
                                    placeholder = "用于网页端 ssh 工具，可留空",
                                )
                            }
                        }
                    }

                    // ── 关于 ───────────────────────────────────────────
                    Column {
                        DsSectionLabel("关于")
                        DsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            DsSettingRow(title = "版本", value = versionName(context))
                            DsDivider(Modifier.padding(start = 16.dp))
                            DsSettingRow(
                                title = "退出登录",
                                subtitle = "只清除本机的设备 Token",
                                showChevron = false,
                                onClick = { confirmAction = "logout" },
                            )
                        }
                    }

                    Text(
                        "DS-Mobile · 私有部署，数据只经过你自己的 Worker。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ds.colors.inkTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.height(DsSpace.lg))
                }
            }

        confirmAction?.let { action ->
            AlertDialog(
                onDismissRequest = { confirmAction = null },
                containerColor = Ds.colors.surface,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        if (action == "repair") "重新配对？" else "退出登录？",
                        color = Ds.colors.ink,
                    )
                },
                text = {
                    Text(
                        "会清除本机保存的设备 Token，之后要重新输入配对码才能进来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ds.colors.inkSecondary,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            save()
                            confirmAction = null
                            onLogout()
                        },
                    ) { Text("确认", color = Ds.colors.danger) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmAction = null }) {
                        Text("取消", color = Ds.colors.inkSecondary)
                    }
                },
            )
        }
}

private fun versionName(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
}.getOrDefault("未知")
