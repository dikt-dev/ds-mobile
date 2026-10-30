package com.dsmobile.app

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.speech.TtsConfigSync

/**
 * 独立设置页：整屏、分区、每个输入框都有说明。
 *
 * 原来是塞在 AlertDialog 里的一个长滚动 Column，字段一多就不知道哪个是干什么的；
 * 现在按「声音 / 哔哩哔哩 / 服务器 / 通知 / 关于」分组，改完统一保存。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onClose: () -> Unit,
    onBiliLogin: () -> Unit,
    onNotificationSettings: () -> Unit,
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F5FB)) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                // 顶栏：关闭 / 标题 / 保存
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color(0xFF6C5CE7))
                    }
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF26232E),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { save(); onClose() },
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("保存") }
                }
                HorizontalDivider(color = Color(0x116C5CE7))
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SectionCard(
                        title = "声音",
                        subtitle = "Azure 语音合成。Key 只存在这台手机上，音色参数会同步到云端给网页用。",
                    ) {
                        Field("Azure Speech Key", key, { key = it }, password = true, hint = "留空则用手机自带的本地语音")
                        Field("Region", region, { region = it }, hint = "eastus / japaneast 等")
                        Field("音色（Voice）", voice, { voice = it }, hint = "zh-CN-XiaoyiNeural")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) {
                                Field("语速", rate, { rate = it }, hint = "+5%")
                            }
                            Box(Modifier.weight(1f)) {
                                Field("音调", pitch, { pitch = it }, hint = "+3Hz")
                            }
                        }
                        Field("情绪强度", styleDegree, { styleDegree = it }, hint = "0.5 – 2.0，默认 1.2")
                    }

                    SectionCard(
                        title = "哔哩哔哩",
                        subtitle = "填了才能把 B 站视频连字幕一起发给大肥鱼。",
                    ) {
                        Field("SESSDATA", biliSessdata, { biliSessdata = it }, password = true, hint = "浏览器 Cookie 里的 SESSDATA")
                        OutlinedButton(
                            onClick = { save(); onBiliLogin() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("在 App 里登录 B 站") }
                    }

                    SectionCard(
                        title = "服务器",
                        subtitle = "改地址后需要重新配对才能用。",
                    ) {
                        Field("服务器地址", baseUrl, { baseUrl = it }, hint = "https://fae.us.ci")
                        val device = settings.deviceId.take(8)
                        Text(
                            "设备标识：$device…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8F89A0),
                        )
                        OutlinedButton(
                            onClick = { save(); onLogout() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("重新配对") }
                    }

                    SectionCard(
                        title = "通知",
                        subtitle = "大肥鱼主动找你的时候，能不能弹出提示。",
                    ) {
                        OutlinedButton(
                            onClick = { save(); onNotificationSettings() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("打开系统通知设置") }
                    }

                    SectionCard(
                        title = "关于",
                        subtitle = "版本信息与调试用途。",
                    ) {
                        Text(
                            "版本 ${versionName(context)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF26232E),
                        )
                        Field("SSH 公钥", sshKey, { sshKey = it }, hint = "用于网页端 ssh 工具，可留空")
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { save(); onClose() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存并返回") }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

private fun versionName(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
}.getOrDefault("未知")

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF26232E),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8F89A0),
                lineHeight = 17.sp,
            )
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    hint: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        placeholder = if (hint.isNotEmpty()) {
            { Text(hint, style = MaterialTheme.typography.bodySmall) }
        } else {
            null
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}