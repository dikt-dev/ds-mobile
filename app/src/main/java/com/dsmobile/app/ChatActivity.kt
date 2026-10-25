package com.dsmobile.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsmobile.app.data.AppDatabase
import com.dsmobile.app.data.MessageEntity
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.net.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
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

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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
                MessageBubble(message)
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
                    scope.launch {
                        try {
                            api.chat(text)
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
            ) {
                Text(if (busy) "…" else "发送")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity) {
    val isUser = message.role == "user"
    val channel = when (message.channel) {
        "mobile" -> "App"
        "web" -> "网页"
        "astrobot" -> "QQ"
        else -> message.channel
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
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 30,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
