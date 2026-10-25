package com.dsmobile.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsmobile.app.data.AppDatabase
import com.dsmobile.app.data.MessageEntity
import com.dsmobile.app.data.PendingMessageEntity
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.media.MediaUtils
import com.dsmobile.app.net.ApiClient
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
            OutlinedButton(onClick = onLogout) { Text("重新配对") }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message, api)
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
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
            Spacer(Modifier.width(8.dp))
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
                    busy = true
                    status = "发送中…"
                    streamingText = ""
                    scope.launch {
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
                },
            ) {
                Text(if (busy) "…" else "发送")
            }
        }
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
                Text(text, style = MaterialTheme.typography.bodyMedium)
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
private fun MessageBubble(message: MessageEntity, api: ApiClient) {
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
    val display = message.content
        .replace(Regex("\\[图片:([^\\]]+)]"), "")
        .trim()
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
