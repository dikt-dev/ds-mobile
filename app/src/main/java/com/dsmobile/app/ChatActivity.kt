package com.dsmobile.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsmobile.app.data.AppDatabase
import com.dsmobile.app.data.MessageEntity
import com.dsmobile.app.data.PendingMessageEntity
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.media.MediaUtils
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.parse.DocumentParser
import com.dsmobile.app.speech.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val settings = SettingsStore(this)
        val db = AppDatabase.get(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatApp(settings, db)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.dsmobile.app.service.KeepAliveService.appVisible = true
    }

    override fun onPause() {
        com.dsmobile.app.service.KeepAliveService.appVisible = false
        super.onPause()
    }
}

@Composable
private fun ChatApp(settings: SettingsStore, db: AppDatabase) {
    var token by remember { mutableStateOf(settings.token) }
    if (token.isNullOrBlank()) {
        SetupScreen(settings) { newToken ->
            token = newToken
        }
    } else {
        ChatScreen(
            settings = settings,
            db = db,
            token = token!!,
            onLogout = {
                settings.token = null
                token = null
            },
        )
    }
}

@Composable
private fun SetupScreen(settings: SettingsStore, onPaired: (String) -> Unit) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var pairCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DS-Mobile 配对", style = MaterialTheme.typography.headlineSmall)
        Text(
            "当前阶段配对码使用 Worker 的 ASTROBOT_API_KEY；配对成功后只保存设备 Token。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Worker 地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = pairCode,
            onValueChange = { pairCode = it },
            label = { Text("配对码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            enabled = !busy && baseUrl.isNotBlank() && pairCode.isNotBlank(),
            onClick = {
                busy = true
                status = "配对中…"
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
                        status = "配对成功"
                        onPaired(token)
                    } catch (e: Exception) {
                        status = "配对失败：${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "配对中…" else "配对并进入聊天")
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(context, MainActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开阶段 0 调试页")
        }
        OutlinedButton(
            onClick = { openAutostartSettings(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开自启动/省电设置")
        }
    }
}

private fun openAutostartSettings(context: Context) {
    val candidates = listOf(
        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        ),
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // try next
        }
    }
}

@Composable
private fun ChatScreen(
    settings: SettingsStore,
    db: AppDatabase,
    token: String,
    onLogout: () -> Unit,
) {
    val dao = remember { db.messageDao() }
    val api = remember(settings.baseUrl, token) { ApiClient(settings.baseUrl, token) }
    val messages by dao.observe().collectAsState(initial = emptyList())
    val pending by dao.observePending().collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var streamingText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    suspend fun syncEvents() {
        val after = dao.maxId()
        val page = api.events(after, 100)
        if (page.messages.isNotEmpty()) dao.insertAll(page.messages)
    }

    suspend fun syncLatest() {
        val page = api.events(0, 50, latest = true)
        if (page.messages.isNotEmpty()) dao.insertAll(page.messages)
    }

    suspend fun sendText(text: String) {
        busy = true
        status = "发送中…"
        streamingText = ""
        val pendingMessage = PendingMessageEntity(
            content = text,
            clientMessageId = UUID.randomUUID().toString(),
        )
        val pendingId = dao.insertPending(pendingMessage)
        try {
            api.chatStream(text, pendingMessage.clientMessageId) { delta ->
                withContext(Dispatchers.Main) {
                    streamingText += delta
                    status = "接收中…"
                }
            }
            dao.deletePending(pendingMessage.copy(id = pendingId))
            delay(300)
            syncEvents()
            streamingText = ""
            status = ""
        } catch (e: Exception) {
            status = "发送失败：${e.message}，可在下方重试"
            streamingText = ""
        } finally {
            busy = false
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (spoken.isNullOrBlank()) {
                status = "没有识别到语音"
            } else {
                scope.launch { sendText(spoken) }
            }
        }
    }

    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说点什么…")
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            status = "系统没有语音识别服务"
        } else {
            try {
                voiceLauncher.launch(intent)
            } catch (e: Exception) {
                status = "语音识别启动失败：${e.message}"
            }
        }
    }

    val voicePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoice() else status = "需要麦克风权限"
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                status = "图片发送中…"
                try {
                    val dataUrl = MediaUtils.imageToDataUrl(context, uri)
                    api.chatWithImage("", dataUrl, UUID.randomUUID().toString())
                    delay(300)
                    syncEvents()
                    status = ""
                } catch (e: Exception) {
                    status = "图片发送失败：${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            scope.launch {
                busy = true
                status = "读取文件…"
                try {
                    val doc = DocumentParser.parse(context, uri)
                    status = "发送文件内容…"
                    val content = "【文件：${doc.name}】\n${doc.text}"
                    api.chat(content, UUID.randomUUID().toString())
                    delay(300)
                    syncEvents()
                    status = ""
                } catch (e: Exception) {
                    status = "文件解析失败：${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            com.dsmobile.app.service.KeepAliveService.start(context)
        }
    }

    LaunchedEffect(Unit) {
        try {
            syncLatest()
        } catch (e: Exception) {
            status = "同步失败：${e.message}"
        }
        while (true) {
            delay(5_000)
            try {
                syncEvents()
            } catch (_: Exception) {
                // 后台轮询失败不打断界面，下一轮重试
            }
        }
    }

    LaunchedEffect(messages.size + pending.size + if (streamingText.isNotEmpty()) 1 else 0) {
        val count = messages.size + pending.size + if (streamingText.isNotEmpty()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DS-Mobile", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { context.startActivity(Intent(context, MainActivity::class.java)) },
            ) { Text("调试") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { showSettings = true }) { Text("设置") }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    api = api,
                    onSpeak = { TtsManager.speak(context, settings, it) },
                    onPrefetch = { TtsManager.prefetch(context, settings, it) },
                )
            }
            items(pending, key = { "pending-${it.id}" }) { message ->
                PendingBubble(
                    message = message,
                    busy = busy,
                    onRetry = {
                        scope.launch {
                            busy = true
                            status = "重试发送…"
                            try {
                                api.chat(message.content, message.clientMessageId)
                                dao.deletePending(message)
                                delay(300)
                                syncEvents()
                                status = ""
                            } catch (e: Exception) {
                                status = "发送失败：${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
            if (streamingText.isNotEmpty()) {
                item(key = "streaming") {
                    StreamingBubble(streamingText)
                }
            }
        }
        if (status.isNotEmpty()) {
            Text(
                status,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !busy,
            ) {
                Text("图片")
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            Uri.parse(
                                "content://com.android.externalstorage.documents/document/primary%3ADownload"
                            ),
                        )
                    }
                    filePicker.launch(intent)
                },
                enabled = !busy,
            ) {
                Text("文件")
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) startVoice() else voicePermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !busy,
            ) {
                Text("语音")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…") },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    val text = input.trim()
                    input = ""
                    scope.launch { sendText(text) }
                },
            ) {
                Text(if (busy) "…" else "发送")
            }
        }
        if (showSettings) {
            SettingsDialog(
                settings = settings,
                onDismiss = { showSettings = false },
                onLogout = {
                    showSettings = false
                    onLogout()
                },
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    settings: SettingsStore,
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
) {
    var key by remember { mutableStateOf(settings.azureKey) }
    var region by remember { mutableStateOf(settings.azureRegion) }
    var voice by remember { mutableStateOf(settings.azureVoice) }
    var rate by remember { mutableStateOf(settings.azureRate) }
    var pitch by remember { mutableStateOf(settings.azurePitch) }
    var styleDegree by remember { mutableStateOf(settings.azureStyleDegree) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("Azure TTS（留空则用本地 TTS）", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Azure Speech Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("Region") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = voice,
                    onValueChange = { voice = it },
                    label = { Text("Voice") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it },
                    label = { Text("Rate（如 +5%）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pitch,
                    onValueChange = { pitch = it },
                    label = { Text("Pitch（如 +3Hz）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = styleDegree,
                    onValueChange = { styleDegree = it },
                    label = { Text("Style Degree（如 1.2）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.azureKey = key
                settings.azureRegion = region
                settings.azureVoice = voice
                settings.azureRate = rate
                settings.azurePitch = pitch
                settings.azureStyleDegree = styleDegree
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onLogout) { Text("重新配对") }
        },
    )
}

@Composable
private fun VoiceBar(onPlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPlay) { Text("▶ 语音") }
        Text("点击播放", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Card(modifier = Modifier.fillMaxWidth(0.86f)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("App · 大肥鱼 · 正在输入", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text
                        .replace("[语音]", "")
                        .replace("【语音消息】", "")
                        .replace("（语音）", ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun PendingBubble(
    message: PendingMessageEntity,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Card(modifier = Modifier.fillMaxWidth(0.86f)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("App · 待发送", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 30,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRetry, enabled = !busy) {
                    Text(if (busy) "发送中…" else "重试")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    api: ApiClient,
    onSpeak: (String) -> Unit,
    onPrefetch: (String) -> Unit,
) {
    val isUser = message.role == "user"
    val channel = when (message.channel) {
        "mobile" -> "App"
        "web" -> "网页"
        "astrobot" -> "QQ"
        else -> message.channel
    }
    val imageKeys = Regex("\\[图片:([^\\]]+)]")
        .findAll(message.content)
        .map { it.groupValues[1] }
        .toList()
    var display = message.content
        .replace(Regex("\\[图片:([^\\]]+)]"), "")
    val voiceMarkers = listOf("[语音]", "【语音消息】", "（语音）")
    val isVoice = !isUser && voiceMarkers.any { display.contains(it) }
    voiceMarkers.forEach { display = display.replace(it, "") }
    display = display.trim()
    LaunchedEffect(isVoice, display) {
        if (isVoice && display.isNotEmpty()) onPrefetch(display)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(modifier = Modifier.fillMaxWidth(0.86f)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    "$channel · ${if (isUser) "我" else "大肥鱼"}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))
                if (display.isNotEmpty()) {
                    Text(
                        display,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 30,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                imageKeys.forEach { key ->
                    MessageImage(api, key)
                }
                if (!isUser && isVoice) {
                    VoiceBar(onPlay = { onSpeak(display) })
                } else if (!isUser && display.isNotEmpty()) {
                    TextButton(onClick = { onSpeak(display) }) { Text("朗读") }
                }
            }
        }
    }
}

@Composable
private fun MessageImage(api: ApiClient, key: String) {
    var bitmap by remember(key) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(key) {
        runCatching { api.image(key) }.onSuccess { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                bitmap = it.asImageBitmap()
            }
        }
    }
    bitmap?.let {
        Spacer(Modifier.height(6.dp))
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            contentScale = ContentScale.Fit,
        )
    }
}
