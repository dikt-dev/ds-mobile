package com.dsmobile.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsmobile.app.DraftAttachment
import com.dsmobile.app.R
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.ui.components.DsButton
import com.dsmobile.app.ui.components.DsDivider
import com.dsmobile.app.ui.components.DsDot
import com.dsmobile.app.ui.components.DsField
import com.dsmobile.app.ui.components.DsIconButton
import com.dsmobile.app.ui.components.DsSegmented
import com.dsmobile.app.ui.components.DsTextButton
import com.dsmobile.app.ui.theme.Ds
import com.dsmobile.app.ui.theme.DsSpace
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.dsmobile.app.BILI_URL_REGEX
import com.dsmobile.app.BiliFrames
import com.dsmobile.app.ChatSegment
import com.dsmobile.app.MediaCaches
import com.dsmobile.app.URL_REGEX
import com.dsmobile.app.data.MessageEntity
import com.dsmobile.app.data.PendingMessageEntity
import com.dsmobile.app.formatBytes
import com.dsmobile.app.formatDuration
import com.dsmobile.app.messageTime
import com.dsmobile.app.normalizeWebFileBlock
import com.dsmobile.app.parseChatSegments
import com.dsmobile.app.replaceFaceMarkers
import com.dsmobile.app.revealedMessageIds
import com.dsmobile.app.stripBiliMeta
import com.dsmobile.app.stripSpokenActions
import com.dsmobile.app.ui.components.DsPill

// ══════════════════════════════════════════════════════════════════════
// 顶栏
// ══════════════════════════════════════════════════════════════════════

@Composable
fun ChatTopBar(
    name: String,
    statusText: String,
    online: Boolean,
    busy: Boolean,
    onAvatar: () -> Unit,
    onStatus: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Ds.colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(start = DsSpace.md, end = DsSpace.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAvatar),
            ) {
                Image(
                    painter = painterResource(R.drawable.avatar),
                    contentDescription = "她的头像，点开看状态",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            }
            Spacer(Modifier.width(DsSpace.sm))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onStatus),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ds.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = Ds.colors.accent,
                        )
                    } else {
                        if (online) {
                            DsDot(Ds.colors.success, 6.dp)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = Ds.colors.inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            DsIconButton(
                icon = Icons.Outlined.SentimentSatisfied,
                contentDescription = "她的状态",
                onClick = onStatus,
            )
            DsIconButton(
                icon = Icons.Outlined.Settings,
                contentDescription = "设置",
                onClick = onSettings,
            )
        }
        DsDivider()
    }
}

// ══════════════════════════════════════════════════════════════════════
// 空态 / 加载态 / 状态条
// ══════════════════════════════════════════════════════════════════════

@Composable
fun EmptyChat(onQuickStart: (String) -> Unit) {
    val suggestions = listOf("在吗", "今天过得怎么样", "我有件事想跟你说")
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = DsSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(DsSpace.md))
        Text(
            "还没有聊天记录",
            style = MaterialTheme.typography.titleMedium,
            color = Ds.colors.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "发一句，把她叫醒。",
            style = MaterialTheme.typography.bodySmall,
            color = Ds.colors.inkTertiary,
        )
        Spacer(Modifier.height(DsSpace.xl))
        suggestions.forEach { text ->
            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Ds.colors.hairline, RoundedCornerShape(14.dp))
                    .clickable { onQuickStart(text) }
                    .padding(horizontal = DsSpace.md, vertical = 10.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Ds.colors.inkSecondary)
            }
        }
    }
}

@Composable
fun ChatLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = Ds.colors.accent,
        )
        Spacer(Modifier.height(DsSpace.sm))
        Text(
            "正在加载聊天记录…",
            style = MaterialTheme.typography.labelMedium,
            color = Ds.colors.inkTertiary,
        )
    }
}

/** 输入区上方的一条状态：只在录音/出错时出现，不占常驻空间。 */
@Composable
fun StatusStrip(text: String) {
    val bad = text.contains("失败") || text.contains("无法") || text.contains("没有")
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = DsSpace.xs, bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (bad) Ds.colors.danger.copy(alpha = 0.08f) else Ds.colors.surfaceMuted)
                .padding(horizontal = DsSpace.sm, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (bad) Ds.colors.danger else Ds.colors.inkSecondary,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// 输入区
// ══════════════════════════════════════════════════════════════════════

@Composable
internal fun ChatComposer(
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    busy: Boolean,
    draft: DraftAttachment?,
    onRemoveDraft: () -> Unit,
    trayOpen: Boolean,
    onTrayChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onPickCamera: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onPickVideo: () -> Unit,
    onPickLocation: () -> Unit,
    voiceMode: Boolean,
    onEnterVoice: () -> Unit,
    onExitVoice: () -> Unit,
    recording: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
    onFocusGained: () -> Unit,
) {
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxWidth().background(Ds.colors.surface)) {
        DsDivider()
        Column(Modifier.padding(horizontal = DsSpace.sm, vertical = DsSpace.sm)) {
            if (voiceMode) {
                VoicePanel(
                    recording = recording,
                    enabled = !busy,
                    onHoldStart = onHoldStart,
                    onHoldEnd = onHoldEnd,
                    onHoldCancel = onHoldCancel,
                    onSwitchKeyboard = {
                        if (recording) onHoldEnd()
                        onExitVoice()
                    },
                )
                return@Column
            }

            draft?.let { DraftBar(it, onRemoveDraft) }

            Row(verticalAlignment = Alignment.Bottom) {
                DsIconButton(
                    icon = Icons.Outlined.Add,
                    contentDescription = "添加附件",
                    size = 42.dp,
                    iconSize = 21.dp,
                    background = if (trayOpen) Ds.colors.accentSoft else Ds.colors.surfaceMuted,
                    tint = if (trayOpen) Ds.colors.accent else Ds.colors.inkSecondary,
                    onClick = {
                        onTrayChange(!trayOpen)
                        focus.clearFocus()
                    },
                )
                Spacer(Modifier.width(DsSpace.xs))
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp, max = 132.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(Ds.colors.surfaceMuted)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (input.isEmpty()) {
                            Text(
                                "说点什么…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Ds.colors.inkSecondary,
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = onInputChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (it.isFocused) onFocusGained() },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ds.colors.ink),
                            cursorBrush = SolidColor(Ds.colors.accent),
                            maxLines = 5,
                        )
                    }
                }
                Spacer(Modifier.width(DsSpace.xs))
                ComposerMainButton(
                    canSend = canSend,
                    busy = busy,
                    onClick = {
                        if (canSend) {
                            onTrayChange(false)
                            onSend()
                        } else {
                            onTrayChange(false)
                            onEnterVoice()
                        }
                    },
                )
            }

            AnimatedVisibility(
                visible = trayOpen && !voiceMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                AttachmentTray(
                    enabled = !busy,
                    onPickCamera = { onTrayChange(false); onPickCamera() },
                    onPickImage = { onTrayChange(false); onPickImage() },
                    onPickFile = { onTrayChange(false); onPickFile() },
                    onPickVideo = { onTrayChange(false); onPickVideo() },
                    onPickLocation = { onTrayChange(false); onPickLocation() },
                )
            }
        }
    }
}

@Composable
private fun ComposerMainButton(canSend: Boolean, busy: Boolean, onClick: () -> Unit) {
    val bg = if (canSend) Ds.colors.accent else Ds.colors.surfaceMuted
    val fg = if (canSend) Ds.colors.accentInk else Ds.colors.inkSecondary
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = fg,
            )
            canSend -> Icon(Icons.Filled.ArrowUpward, "发送", tint = fg, modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Outlined.Mic, "按住说话", tint = fg, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun DraftBar(draft: DraftAttachment, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = DsSpace.xs)
            .clip(RoundedCornerShape(14.dp))
            .background(Ds.colors.surfaceMuted)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val preview = draft.preview
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = draft.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Ds.colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (draft.kind) {
                        "video" -> "视频"
                        "file" -> "文件"
                        else -> "图片"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Ds.colors.inkSecondary,
                )
            }
        }
        Spacer(Modifier.width(DsSpace.sm))
        Column(Modifier.weight(1f)) {
            Text(
                draft.name,
                style = MaterialTheme.typography.bodySmall,
                color = Ds.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "待发送",
                style = MaterialTheme.typography.labelSmall,
                color = Ds.colors.inkTertiary,
            )
        }
        DsTextButton("移除", onRemove, color = Ds.colors.inkSecondary)
    }
}

@Composable
private fun AttachmentTray(
    enabled: Boolean,
    onPickCamera: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onPickVideo: () -> Unit,
    onPickLocation: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = DsSpace.sm, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TrayTile(Icons.Outlined.PhotoCamera, "拍照", enabled, Modifier.weight(1f), onPickCamera)
        TrayTile(Icons.Outlined.PhotoLibrary, "图片", enabled, Modifier.weight(1f), onPickImage)
        TrayTile(Icons.Outlined.AttachFile, "文件", enabled, Modifier.weight(1f), onPickFile)
        TrayTile(Icons.Outlined.Videocam, "视频", enabled, Modifier.weight(1f), onPickVideo)
        TrayTile(Icons.Outlined.LocationOn, "定位", enabled, Modifier.weight(1f), onPickLocation)
    }
}

@Composable
private fun TrayTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Ds.colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) Ds.colors.ink else Ds.colors.inkTertiary,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Ds.colors.inkSecondary else Ds.colors.inkTertiary,
        )
    }
}

@Composable
private fun VoicePanel(
    recording: Boolean,
    enabled: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
    onSwitchKeyboard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .padding(top = DsSpace.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (recording) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Ds.colors.accent.copy(alpha = 0.12f)),
                )
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .scale(if (recording) 1.04f else 1f)
                    .clip(CircleShape)
                    .background(if (recording) Ds.colors.accent else Ds.colors.accentSoft)
                    .pointerInput(enabled) {
                        if (enabled) {
                            detectTapGestures(
                                onPress = {
                                    onHoldStart()
                                    val released = tryAwaitRelease()
                                    if (released) onHoldEnd() else onHoldCancel()
                                },
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = "按住说话",
                    tint = if (recording) Ds.colors.accentInk else Ds.colors.accent,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(DsSpace.sm))
        Text(
            if (recording) "正在听，松手结束" else "按住说话",
            style = MaterialTheme.typography.bodyMedium,
            color = if (recording) Ds.colors.accent else Ds.colors.ink,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "按下开始录音，松手直接发送",
            style = MaterialTheme.typography.labelSmall,
            color = Ds.colors.inkTertiary,
        )
        Spacer(Modifier.height(DsSpace.xs))
        DsTextButton("用键盘打字", onSwitchKeyboard, color = Ds.colors.inkSecondary)
    }
}

// ══════════════════════════════════════════════════════════════════════
// 配对引导
// ══════════════════════════════════════════════════════════════════════

@Composable
fun PairingScreen(
    settings: SettingsStore,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onPaired: (String) -> Unit,
    onOpenDebug: () -> Unit,
    onOpenAutostart: () -> Unit,
) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var pairCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ds.colors.canvas)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DsSpace.xl, vertical = DsSpace.xxl),
    ) {
        Spacer(Modifier.height(24.dp))
        Image(
            painter = painterResource(R.drawable.avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(DsSpace.lg))
        Text(
            "连接大肥鱼",
            style = MaterialTheme.typography.headlineMedium,
            color = Ds.colors.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "填一次就好。配对之后，这台手机会接管和她有关的所有对话。",
            style = MaterialTheme.typography.bodyMedium,
            color = Ds.colors.inkSecondary,
        )
        Spacer(Modifier.height(DsSpace.xxl))

        DsField(
            label = "服务器地址",
            value = baseUrl,
            onValueChange = { baseUrl = it },
            placeholder = "https://fae.us.ci",
        )
        Spacer(Modifier.height(DsSpace.md))
        DsField(
            label = "配对码",
            value = pairCode,
            onValueChange = { pairCode = it },
            placeholder = "Worker 的 API Key",
            password = true,
        )
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(DsSpace.sm))
            Text(error, style = MaterialTheme.typography.bodySmall, color = Ds.colors.danger)
        }
        Spacer(Modifier.height(DsSpace.xl))
        DsButton(
            text = if (busy) "配对中…" else "配对并进入",
            loading = busy,
            enabled = baseUrl.isNotBlank() && pairCode.isNotBlank(),
            onClick = {
                busy = true
                error = ""
                scope.launch {
                    try {
                        val normalized = baseUrl.trim().trimEnd('/')
                        val token = ApiClient(normalized).pair(
                            pairCode = pairCode,
                            deviceName = "Redmi K60",
                            deviceId = settings.deviceId,
                        )
                        settings.baseUrl = normalized
                        settings.token = token
                        onPaired(token)
                    } catch (e: Exception) {
                        error = "配对失败：${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
        )
        Spacer(Modifier.height(DsSpace.xl))
        DsDivider()
        Spacer(Modifier.height(DsSpace.md))
        Text(
            "外观",
            style = MaterialTheme.typography.labelMedium,
            color = Ds.colors.inkTertiary,
        )
        Spacer(Modifier.height(6.dp))
        DsSegmented(
            options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
            selected = themeMode,
            onSelect = onThemeModeChange,
        )
        Spacer(Modifier.height(DsSpace.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsTextButton("自启动设置", onOpenAutostart, color = Ds.colors.inkSecondary)
            Spacer(Modifier.weight(1f))
            DsTextButton("调试页", onOpenDebug, color = Ds.colors.inkTertiary)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// 日期分隔 / 头像
// ══════════════════════════════════════════════════════════════════════

@Composable
fun DaySeparator(day: String) {
    val label = remember(day) { dayLabel(day) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Ds.colors.hairline))
        Text(
            label,
            modifier = Modifier.padding(horizontal = DsSpace.sm),
            style = MaterialTheme.typography.labelMedium,
            color = Ds.colors.inkTertiary,
        )
        Box(Modifier.weight(1f).height(1.dp).background(Ds.colors.hairline))
    }
}

private fun dayLabel(day: String): String {
    val parts = day.split("-")
    val month = parts.getOrNull(0)?.toIntOrNull() ?: return day
    val date = parts.getOrNull(1)?.toIntOrNull() ?: return day
    val today = LocalDate.now()
    val target = runCatching { LocalDate.of(today.year, month, date) }.getOrNull()
    return when (target) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${month}月${date}日"
    }
}

@Composable
fun ChatAvatar(size: androidx.compose.ui.unit.Dp = 30.dp, onClick: (() -> Unit)? = null) {
    Image(
        painter = painterResource(R.drawable.avatar),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

// ══════════════════════════════════════════════════════════════════════
// 消息
// ══════════════════════════════════════════════════════════════════════

/**
 * B 站分享到聊天里的原始文本是「【标题-哔哩哔哩】 短链 自己的话」拼成的一行。
 * 标题和封面已经由下面的卡片承载，气泡里只该留自己打的那句；
 * 否则自己的话会被夹在标题和卡片中间，看起来像被包起来了。
 */
private val BILI_SHARE_HEADER = Regex(
    "^\\s*【[^】]{0,90}(?:哔哩哔哩|bilibili)[^】]{0,10}】\\s*",
    RegexOption.IGNORE_CASE,
)

internal fun stripBiliShareHeader(text: String): String =
    BILI_SHARE_HEADER.replace(text, "").trim()

private fun bubbleShape(isUser: Boolean, grouped: Boolean): RoundedCornerShape {
    val tail = 6.dp
    val body = 20.dp
    return if (isUser) {
        RoundedCornerShape(
            topStart = body,
            topEnd = if (grouped) body else tail,
            bottomEnd = body,
            bottomStart = body,
        )
    } else {
        RoundedCornerShape(
            topStart = if (grouped) body else tail,
            topEnd = body,
            bottomEnd = body,
            bottomStart = body,
        )
    }
}

@Composable
private fun TextBubble(
    isUser: Boolean,
    shape: RoundedCornerShape,
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .then(
                if (isUser) Modifier
                else Modifier.border(1.dp, Ds.colors.hairline, shape),
            )
            .clip(shape)
            .background(if (isUser) Ds.colors.userBubble else Ds.colors.aiBubble)
            .padding(horizontal = 15.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                replaceFaceMarkers(text),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isUser) Ds.colors.userBubbleText else Ds.colors.aiBubbleText,
                maxLines = if (expanded) Int.MAX_VALUE else maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (expandable) {
                Text(
                    if (expanded) "收起" else "展开全文",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isUser) Ds.colors.userBubbleText.copy(alpha = 0.8f) else Ds.colors.accent,
                    modifier = Modifier.clickable { onToggleExpand?.invoke() },
                )
            }
        }
    }
}

@Composable
private fun MetaRow(name: String, channel: String?, time: String, isUser: Boolean) {
    Row(
        modifier = Modifier.padding(bottom = 5.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isUser) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = Ds.colors.inkSecondary)
        }
        if (!channel.isNullOrBlank() && channel != "App") {
            Spacer(Modifier.width(5.dp))
            DsPill(channel)
        }
        if (time.isNotEmpty()) {
            Spacer(Modifier.width(if (isUser) 0.dp else 6.dp))
            Text(time, style = MaterialTheme.typography.labelSmall, color = Ds.colors.inkTertiary)
        }
    }
}

/**
 * 消息的回执。
 *
 * - [SENT]：她那边已经收到了这条（D1 里有了），但还没开口
 * - [READ]：她的回复已经到这台手机上，才算"读了"
 *
 * 中间那一档很重要：从"发出去"到她开口有好几秒，这期间什么都不显示会让人
 * 怀疑自己没发成功。
 */
enum class Receipt { SENT, READ }

@Composable
private fun ReadMark(failed: Boolean, read: Boolean = true) {
    Row(
        modifier = Modifier.padding(top = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                failed -> "发送失败"
                read -> "已读"
                else -> "已发送"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) Ds.colors.danger else Ds.colors.inkTertiary,
        )
        Spacer(Modifier.width(3.dp))
        Icon(
            when {
                failed -> Icons.Filled.Close
                read -> Icons.Filled.DoneAll
                else -> Icons.Filled.Done
            },
            contentDescription = null,
            tint = if (failed) Ds.colors.danger else Ds.colors.inkTertiary,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
fun MessageItem(
    message: MessageEntity,
    api: ApiClient,
    biliCookie: String,
    showMeta: Boolean,
    grouped: Boolean,
    receipt: Receipt?,
    animate: Boolean,
    onSpeak: (String) -> Unit,
    onPrefetch: (String) -> Unit,
    onReveal: () -> Unit,
) {
    val isUser = message.role == "user"
    val channel = when (message.channel) {
        "mobile" -> "App"
        "web" -> "网页"
        "astrobot" -> "QQ"
        else -> message.channel
    }
    // 网页端发来的文件块是「【文件：name】 + 围栏正文 + 【/文件】」，
    // 和 App 的 [文件:name|size|mime] 不是一套格式；不统一就会把整份正文当聊天内容显示。
    val rawContent = normalizeWebFileBlock(stripBiliMeta(message.content))
    val fileRegex = Regex("""\[文件:([^|\]]+)\|(\d+)\|([^\]]*)]""")
    val fileMatch = fileRegex.find(rawContent)
    val fileName = fileMatch?.groupValues?.get(1) ?: ""
    val fileSize = fileMatch?.groupValues?.get(2)?.toLongOrNull() ?: 0L
    val fileMime = fileMatch?.groupValues?.get(3) ?: ""
    val fileContent = if (fileMatch != null) {
        rawContent.substring(fileMatch.range.last + 1)
            .substringBefore("【/文件】")
            .removePrefix("\n")
            .removePrefix("【文件内容】")
            .trim()
    } else {
        ""
    }
    val contentForText = if (fileMatch != null) {
        rawContent.substring(0, fileMatch.range.first)
    } else {
        rawContent
    }
    val mediaRegex = Regex("\\[(视频|GIF):([^\\]]+)]")
    val mediaItems = mediaRegex.findAll(contentForText)
        .map { it.groupValues[1] to it.groupValues[2] }
        .toList()
    val imageKeys = Regex("\\[图片:([^\\]]+)]")
        .findAll(contentForText)
        .map { it.groupValues[1] }
        .toList()

    val voiceMarkers = listOf("【语音消息】", "[语音消息]", "【语音】", "[语音]", "（语音）")
    val firstMarker = voiceMarkers
        .mapNotNull { marker -> contentForText.indexOf(marker).takeIf { it >= 0 }?.let { it to marker } }
        .minByOrNull { it.first }
    val textPart = if (firstMarker != null) contentForText.substring(0, firstMarker.first) else contentForText
    val voicePart = if (firstMarker != null) {
        contentForText.substring(firstMarker.first + firstMarker.second.length)
    } else {
        ""
    }
    val displayText = textPart
        .replace(Regex("\\[图片:([^\\]]+)]"), "")
        .replace(mediaRegex, "")
        .trim()
    val voiceText = voicePart
        .replace(Regex("\\[图片:([^\\]]+)]"), "")
        .trim()
    val isVoice = !isUser && voiceText.isNotEmpty()

    val biliUrls = BILI_URL_REGEX.findAll(displayText)
        .map { it.value.trimEnd(')', ']', '】', '。', '，', '！', '？') }
        .distinct()
        .toList()
    val webUrls = URL_REGEX.findAll(displayText)
        .map { it.value.trimEnd(')', ']', '】', '。', '，', '！', '？') }
        .filterNot { BILI_URL_REGEX.containsMatchIn(it) }
        .distinct()
        .toList()
    val bodyText = URL_REGEX.replace(displayText, "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    var expanded by remember(message.id) { mutableStateOf(false) }
    val shownText = if (biliUrls.isNotEmpty()) stripBiliShareHeader(bodyText) else bodyText
    val isLongText = shownText.length > 600

    val segmentSource = contentForText
        .replace(mediaRegex, "")
        .replace(Regex("\\[图片:([^\\]]+)]"), "")
        .let { URL_REGEX.replace(it, "") }
        .trim()
    val segments = remember(message.id, segmentSource) {
        if (
            !isUser && fileMatch == null && imageKeys.isEmpty() && mediaItems.isEmpty() &&
            biliUrls.isEmpty() && webUrls.isEmpty()
        ) {
            parseChatSegments(segmentSource)
        } else {
            emptyList()
        }
    }
    val useSegments = !isUser && (segments.size > 1 || segments.any { it is ChatSegment.Voice })

    // 「她收到 → 读了 → 停一下 → 才开口」：整条消息的停顿在这里统一处理，
    // 而不是只对分段回复生效——纯文字回复以前是一到就蹦出来，没有呼吸。
    val richReply = imageKeys.isNotEmpty() || mediaItems.isNotEmpty() ||
        biliUrls.isNotEmpty() || webUrls.isNotEmpty() || fileMatch != null
    val holdMs = if (richReply) READ_HOLD_MEDIA_MS else READ_HOLD_MS
    var holdDone by remember(message.id) {
        mutableStateOf(!animate || isUser || revealedMessageIds.contains(message.id))
    }
    LaunchedEffect(message.id, animate, isUser) {
        if (!holdDone) {
            delay(holdMs)
            holdDone = true
            // 非分段回复没有内部动画来收尾，这里替它记账，否则滚动回来会重演一次
            if (!useSegments) revealedMessageIds.add(message.id)
            onReveal()
        }
    }

    LaunchedEffect(isVoice, voiceText) {
        if (isVoice) onPrefetch(voiceText)
    }

    val align = if (isUser) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        if (showMeta) {
            MetaRow(
                name = "大肥鱼",
                // 只在「主动消息」时打标：她不是被问出来的，是自己冒出来的。
                // 平台标签（QQ / 网页 / App）在手机上看纯属噪音——人在 App 里，
                // 自己的消息却挂着「QQ」，只会让人以为串台了。
                channel = if (message.proactiveKind != null) "主动" else null,
                time = messageTime(message.createdAt),
                isUser = isUser,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            // 头像对齐这一组的第一条，而不是最后一条：多气泡组里挂在底部会显得掉下去
            verticalAlignment = Alignment.Top,
        ) {
            if (!isUser) {
                if (!grouped) {
                    ChatAvatar(30.dp)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Spacer(Modifier.width(38.dp))
                }
            }
            Column(
                modifier = Modifier.widthIn(max = 302.dp),
                horizontalAlignment = align,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!holdDone) {
                    TypingDots()
                } else if (useSegments) {
                    SegmentedBody(
                        messageId = message.id,
                        segments = segments,
                        animate = animate,
                        isUser = isUser,
                        onSpeak = onSpeak,
                        onPrefetch = onPrefetch,
                        onReveal = onReveal,
                    )
                } else {
                    if (shownText.isNotEmpty()) {
                        TextBubble(
                            isUser = isUser,
                            shape = bubbleShape(isUser, grouped),
                            text = shownText,
                            maxLines = if (isLongText) 8 else Int.MAX_VALUE,
                            expandable = isLongText,
                            expanded = expanded,
                            onToggleExpand = { expanded = !expanded },
                        )
                    }
                    imageKeys.forEach { key -> ImageBlock(api, key) }
                    mediaItems.forEach { (kind, key) ->
                        if (kind.equals("GIF", ignoreCase = true)) GifBlock(api, key) else VideoBlock(api, key)
                    }
                    biliUrls.forEach { url -> BiliCard(api, url, biliCookie) }
                    webUrls.forEach { url -> WebCard(api, url) }
                    if (fileMatch != null) {
                        FileCard(fileName, fileSize, fileMime, fileContent)
                    }
                    if (isVoice) {
                        VoiceBubble(voiceText, isUser = false, onPlay = { onSpeak(voiceText) })
                    }
                }
            }
        }
        if (isUser && receipt != null) ReadMark(failed = false, read = receipt == Receipt.READ)
    }
}

@Composable
private fun SegmentedBody(
    messageId: Long,
    segments: List<ChatSegment>,
    animate: Boolean,
    isUser: Boolean,
    onSpeak: (String) -> Unit,
    onPrefetch: (String) -> Unit,
    onReveal: () -> Unit,
) {
    val voices = segments.filterIsInstance<ChatSegment.Voice>().map { it.text }
    LaunchedEffect(voices) {
        voices.forEach { if (it.isNotBlank()) onPrefetch(it) }
    }
    val alreadyRevealed = !animate || revealedMessageIds.contains(messageId)
    // 停顿由 MessageItem 统一做（那里的"正在输入"和已读是同一拍），
    // 这里只管一句一句往外放。
    var visibleCount by remember(messageId) {
        mutableStateOf(if (alreadyRevealed) segments.size else 1)
    }
    LaunchedEffect(messageId, segments.size, animate) {
        if (animate && !revealedMessageIds.contains(messageId) && segments.isNotEmpty()) {
            while (visibleCount < segments.size) {
                delay(1000)
                visibleCount++
                onReveal()
            }
            revealedMessageIds.add(messageId)
        }
    }
    segments.take(visibleCount.coerceAtMost(segments.size)).forEach { segment ->
        when (segment) {
            is ChatSegment.Text -> if (segment.text.isNotBlank()) {
                TextBubble(isUser = isUser, shape = bubbleShape(false, false), text = segment.text)
            }
            is ChatSegment.Voice -> VoiceBubble(
                segment.text,
                isUser = isUser,
                onPlay = { onSpeak(segment.text) },
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// 语音条
// ══════════════════════════════════════════════════════════════════════

@Composable
fun VoiceBubble(text: String, isUser: Boolean, onPlay: () -> Unit) {
    val seconds = (stripSpokenActions(text).length / 5).coerceIn(2, 60)
    val width = (170 + seconds * 2).coerceAtMost(268).dp
    var showTranscript by remember(text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(18.dp))
                .background(if (isUser) Ds.colors.userBubble else Ds.colors.aiBubble)
                .then(if (isUser) Modifier else Modifier.border(1.dp, Ds.colors.hairline, RoundedCornerShape(18.dp)))
                .clickable(onClick = onPlay)
                .padding(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (isUser) Ds.colors.accentInk.copy(alpha = 0.18f) else Ds.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = if (isUser) Ds.colors.accentInk else Ds.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            VoiceWaveform(
                color = if (isUser) Ds.colors.accentInk else Ds.colors.accent,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatDuration(seconds),
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) Ds.colors.accentInk.copy(alpha = 0.9f) else Ds.colors.inkSecondary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (showTranscript) "收起" else "转文字",
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) Ds.colors.accentInk.copy(alpha = 0.9f) else Ds.colors.inkTertiary,
                modifier = Modifier.clickable { showTranscript = !showTranscript },
            )
        }
        if (showTranscript) {
            Box(
                modifier = Modifier
                    .widthIn(max = 268.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Ds.colors.surfaceMuted)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ds.colors.inkSecondary,
                )
            }
        }
    }
}

@Composable
private fun VoiceWaveform(color: Color, modifier: Modifier = Modifier) {
    val heights = listOf(0.3f, 0.55f, 0.85f, 0.45f, 0.7f, 1f, 0.5f, 0.78f, 0.6f, 0.9f, 0.35f, 0.62f)
    Canvas(modifier = modifier.height(18.dp)) {
        val step = size.width / heights.size
        heights.forEachIndexed { i, ratio ->
            val h = size.height * ratio
            val x = step * i + step / 2
            drawLine(
                color = color.copy(alpha = 0.55f),
                start = Offset(x, (size.height - h) / 2),
                end = Offset(x, (size.height + h) / 2),
                strokeWidth = 2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// 正在输入 / 待发送
// ══════════════════════════════════════════════════════════════════════

/** "已读"亮起到她开口之间的停顿（毫秒）。 */
private const val READ_HOLD_MS = 1500L

/** 她的回复本身就带图/视频/链接时，停顿短一些：看图比读字快，等久了反而假。 */
private const val READ_HOLD_MEDIA_MS = 600L

@Composable
private fun TypingDots() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(600, delayMillis = i * 160),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Ds.colors.inkTertiary.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
fun StreamingMessage(text: String, onReveal: () -> Unit) {
    val segments = parseChatSegments(text)
    var visibleCount by remember { mutableStateOf(1) }
    LaunchedEffect(segments.size) {
        while (visibleCount < segments.size) {
            delay(1000)
            visibleCount++
            onReveal()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 2.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("大肥鱼", style = MaterialTheme.typography.labelMedium, color = Ds.colors.inkSecondary)
            Spacer(Modifier.width(6.dp))
            TypingDots()
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ChatAvatar(30.dp)
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.widthIn(max = 302.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                segments
                    .filterIsInstance<ChatSegment.Text>()
                    .take(visibleCount.coerceAtMost(segments.size))
                    .forEach { segment ->
                        if (segment.text.isNotBlank()) {
                            TextBubble(isUser = false, shape = bubbleShape(false, false), text = segment.text)
                        }
                    }
                if (segments.any { it is ChatSegment.Voice }) {
                    Text(
                        "语音生成中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ds.colors.inkTertiary,
                    )
                }
            }
        }
    }
}

/**
 * 还没发出去的链接卡片。
 *
 * `ready = false` 时只画骨架（尺寸和真卡片一致，不预留就会跳），
 * 解析完原地换成真的卡片——缓存这时已经热了，所以不会再等第二次加载。
 */
data class PendingCard(val url: String, val bili: Boolean, val ready: Boolean)

@Composable
private fun LinkSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ds.colors.surfaceMuted)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Ds.colors.surface),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "正在读取链接…",
            style = MaterialTheme.typography.bodySmall,
            color = Ds.colors.inkTertiary,
        )
    }
}

@Composable
fun PendingMessageRow(
    message: PendingMessageEntity,
    api: ApiClient,
    biliCookie: String,
    card: PendingCard?,
    busy: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val localPreview = remember(message.localUri) {
        message.localUri?.let { raw ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(raw))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Row(
            modifier = Modifier.padding(bottom = 5.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (failed) "待重试" else "发送中",
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) Ds.colors.danger else Ds.colors.inkTertiary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 302.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val shape = bubbleShape(true, false)
                Box(
                    modifier = Modifier
                        .then(if (failed) Modifier else Modifier.border(0.dp, Color.Transparent, shape))
                        .clip(shape)
                        .background(Ds.colors.userBubble.copy(alpha = if (failed) 0.55f else 0.9f))
                        .padding(horizontal = 15.dp, vertical = 10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (localPreview != null) {
                            Image(
                                bitmap = localPreview.asImageBitmap(),
                                contentDescription = message.attachmentName ?: "待发送图片",
                                modifier = Modifier
                                    .widthIn(max = 240.dp)
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        } else if (message.attachmentKind != null) {
                            Text(
                                message.attachmentName ?: "待发送附件",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ds.colors.userBubbleText,
                            )
                        }
                        if (message.content.isNotBlank()) {
                            Text(
                                replaceFaceMarkers(stripBiliMeta(message.content)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Ds.colors.userBubbleText,
                                maxLines = 30,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 卡片在气泡里就跟上，别等她回了才补出来
                        if (card != null) {
                            if (card.ready) {
                                if (card.bili) {
                                    BiliCard(api, card.url, biliCookie)
                                } else {
                                    WebCard(api, card.url)
                                }
                            } else {
                                LinkSkeleton()
                            }
                        }
                    }
                }
                if (failed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DsTextButton("重试", onRetry, enabled = !busy, color = Ds.colors.accent)
                        DsTextButton("删除", onDismiss, enabled = !busy, color = Ds.colors.inkSecondary)
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// 消息里的卡片
// ══════════════════════════════════════════════════════════════════════

@Composable
private fun CardShell(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ds.colors.surface)
            .border(1.dp, Ds.colors.hairline, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
        content = content,
    )
}

@Composable
fun WebCard(api: ApiClient, url: String) {
    val uriHandler = LocalUriHandler.current
    var preview by remember(url) { mutableStateOf(MediaCaches.webPreview(url)) }
    LaunchedEffect(url) {
        if (preview == null) {
            preview = runCatching { api.webPreview(url) }.getOrNull()
            preview?.let { MediaCaches.putWebPreview(url, it) }
        }
    }
    val isDouyin = preview?.douyin == true || url.contains("douyin", ignoreCase = true)
    val site = preview?.site?.ifBlank { null } ?: url.substringAfter("://").substringBefore('/')
    CardShell(onClick = { uriHandler.openUri(url) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ds.colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isDouyin) Icons.Outlined.PlayCircleOutline else Icons.Outlined.Language,
                    contentDescription = null,
                    tint = Ds.colors.inkSecondary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    preview?.title?.takeIf { it.isNotBlank() }
                        ?: if (isDouyin) "抖音视频" else "网页链接",
                    style = MaterialTheme.typography.titleSmall,
                    color = Ds.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!preview?.description.isNullOrBlank()) {
                    Text(
                        preview!!.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    site,
                    style = MaterialTheme.typography.labelSmall,
                    color = Ds.colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun FileCard(name: String, size: Long, mime: String, content: String) {
    var expanded by remember(name) { mutableStateOf(false) }
    CardShell {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ds.colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = Ds.colors.inkSecondary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ds.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatBytes(size)} · ${mime.substringAfterLast('/').uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ds.colors.inkTertiary,
                )
            }
        }
        if (content.isNotBlank()) {
            Text(
                if (expanded) "收起" else "查看解析内容",
                style = MaterialTheme.typography.labelMedium,
                color = Ds.colors.accent,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { expanded = !expanded },
            )
            if (expanded) {
                Text(
                    content,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ds.colors.inkSecondary,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BiliCover(api: ApiClient, url: String, duration: Int) {
    var drawable by remember(url) { mutableStateOf(MediaCaches.image("bili-cover:$url")) }
    LaunchedEffect(url) {
        if (drawable == null && url.isNotBlank()) {
            val bytes = api.biliImage(url)
            if (bytes != null) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                    val d = BitmapDrawable(null, it)
                    MediaCaches.putImage("bili-cover:$url", d)
                    drawable = d
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .width(92.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Ds.colors.surfaceSunken),
    ) {
        drawable?.let { image ->
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageDrawable(image)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (duration > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    formatDuration(duration),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
fun BiliCard(api: ApiClient, url: String, biliCookie: String) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var preview by remember(url) { mutableStateOf(MediaCaches.biliPreview(url)) }
    var frames by remember(url) { mutableStateOf(MediaCaches.biliFrame(url)) }
    var framesLoading by remember(url) { mutableStateOf(false) }
    var showFrames by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (preview == null) {
            preview = runCatching { api.biliPreview(url) }.getOrNull()
            preview?.let { MediaCaches.putBiliPreview(url, it) }
        }
    }
    LaunchedEffect(preview?.storyboardUrl, preview?.playUrl) {
        val p = preview
        if (frames == null && p != null && (p.storyboardUrl.isNotBlank() || p.playUrl.isNotBlank())) {
            framesLoading = true
            frames = BiliFrames.load(
                cacheKey = url,
                storyboardUrl = p.storyboardUrl,
                storyboardCols = p.storyboardCols,
                storyboardRows = p.storyboardRows,
                playUrl = p.playUrl,
                cookie = biliCookie,
                cacheDir = context.cacheDir,
            )
            framesLoading = false
        }
    }
    val summary = preview?.let { it.summary.ifBlank { it.desc } }.orEmpty()
    CardShell(onClick = { uriHandler.openUri(url) }) {
        Row(verticalAlignment = Alignment.Top) {
            BiliCover(api, preview?.cover.orEmpty(), preview?.duration ?: 0)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    preview?.title?.takeIf { it.isNotBlank() } ?: "哔哩哔哩视频",
                    style = MaterialTheme.typography.titleSmall,
                    color = Ds.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        "哔哩哔哩",
                        preview?.owner?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ds.colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (frames != null || framesLoading) {
            Text(
                if (showFrames) "收起视频帧" else if (framesLoading) "视频抽帧中…" else "查看视频帧",
                style = MaterialTheme.typography.labelMedium,
                color = Ds.colors.accent,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { showFrames = !showFrames },
            )
        }
        if (showFrames && frames != null) {
            Image(
                bitmap = frames!!.asImageBitmap(),
                contentDescription = "B站视频帧",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
fun ImageBlock(api: ApiClient, key: String) {
    var drawable by remember(key) {
        mutableStateOf(
            MediaCaches.image(key) ?: MediaCaches.claimOutgoing()?.also { MediaCaches.putImage(key, it) },
        )
    }
    LaunchedEffect(key) {
        if (drawable == null) {
            runCatching { api.image(key) }.onSuccess { bytes ->
                runCatching {
                    val decoded = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                    )
                    (decoded as? AnimatedImageDrawable)?.start()
                    decoded
                }.onSuccess {
                    MediaCaches.putImage(key, it)
                    drawable = it
                }
            }
        }
    }
    drawable?.let { image ->
        (image as? AnimatedImageDrawable)?.start()
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setImageDrawable(image)
                }
            },
            modifier = Modifier
                .width(230.dp)
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Ds.colors.hairline, RoundedCornerShape(16.dp)),
        )
    }
}

@Composable
fun GifBlock(api: ApiClient, key: String) {
    var drawable by remember(key) { mutableStateOf(MediaCaches.image(key)) }
    var failed by remember(key) { mutableStateOf(false) }
    var attempt by remember(key) { mutableStateOf(0) }
    LaunchedEffect(key, attempt) {
        if (drawable == null) {
            failed = false
            runCatching { api.mediaBytes(key) }
                .onSuccess { bytes ->
                    val decoded = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                    )
                    (decoded as? AnimatedImageDrawable)?.start()
                    MediaCaches.putImage(key, decoded)
                    drawable = decoded
                }
                .onFailure { failed = true }
        }
    }
    val gif = drawable
    if (gif != null) {
        (gif as? AnimatedImageDrawable)?.start()
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(gif)
                }
            },
            modifier = Modifier
                .width(220.dp)
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Ds.colors.hairline, RoundedCornerShape(16.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Ds.colors.surfaceMuted)
                .clickable(enabled = failed) {
                    failed = false
                    attempt++
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (failed) "动态加载失败，点此重试" else "加载中…",
                style = MaterialTheme.typography.labelSmall,
                color = Ds.colors.inkTertiary,
            )
        }
    }
}

@Composable
fun VideoBlock(api: ApiClient, key: String) {
    val context = LocalContext.current
    val cachedFile = remember(key) { api.cachedMediaFile(key) }
    val player = remember(key, cachedFile) { MediaCaches.player(context, api, key, cachedFile) }
    LaunchedEffect(key) {
        if (cachedFile == null) runCatching { api.mediaFile(key) }
    }
    DisposableEffect(player) {
        onDispose { player.pause() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = Modifier
            .width(240.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(16.dp)),
    )
}

