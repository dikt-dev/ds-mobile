package com.dsmobile.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Base64
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.material.icons.filled.ArrowDownward
import com.dsmobile.app.data.AppDatabase
import com.dsmobile.app.data.MessageEntity
import com.dsmobile.app.data.PendingMessageEntity
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.media.MediaUtils
import com.dsmobile.app.net.HeldException
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.net.BiliPreview
import com.dsmobile.app.net.WebPreview
import java.io.ByteArrayOutputStream
import java.io.File
import com.dsmobile.app.parse.DocumentParser
import com.dsmobile.app.speech.TtsConfigSync
import com.dsmobile.app.ui.ChatComposer
import com.dsmobile.app.ui.ChatLoading
import com.dsmobile.app.ui.ChatTopBar
import com.dsmobile.app.ui.DaySeparator
import com.dsmobile.app.ui.EmptyChat
import com.dsmobile.app.ui.MessageItem
import com.dsmobile.app.ui.PairingScreen
import com.dsmobile.app.ui.PendingCard
import com.dsmobile.app.ui.PendingMessageRow
import com.dsmobile.app.ui.Receipt
import com.dsmobile.app.ui.StatusStrip
import com.dsmobile.app.ui.StreamingMessage
import com.dsmobile.app.ui.components.DsField
import com.dsmobile.app.ui.theme.Ds
import com.dsmobile.app.ui.theme.DsTheme
import com.dsmobile.app.ui.theme.rememberDarkTheme
import com.dsmobile.app.speech.TtsManager
import com.dsmobile.app.speech.VoiceRecorder
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 状态面板开关。
 *
 * 打开入口分散在很深的组合树里（气泡头像），层层传回调不划算；
 * 用文件级状态，ChatApp 直接观察它。
 */
/**
 * 媒体消息的「安静期」。
 *
 * 先让用户看见图片/视频/链接卡片，再让 AI 开口——她不需要秒回，
 * 反而是「刚发出去就被回复」会让人觉得自己发的东西没被看。
 */
private const val MEDIA_SETTLE_MS = 1500L

private object StatusPanelState {
    var visible by mutableStateOf(false)
}

/** 设置页的所有入口都在 ChatScreen 里，用同一个办法提到 ChatApp 顶层渲染，避免 Dialog 顶到状态栏。 */
private object SettingsPanelState {
    var visible by mutableStateOf(false)
    var onBiliLogin: (() -> Unit)? by mutableStateOf(null)
    var onNotification: (() -> Unit)? by mutableStateOf(null)
    var onDebug: (() -> Unit)? by mutableStateOf(null)
    var onLogout: (() -> Unit)? by mutableStateOf(null)
}

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
        // 每次开 App 同步一次语音参数：网页端靠它合成，和手机声音保持一致。
        TtsConfigSync.push(settings)
        setContent {
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            DsTheme(darkTheme = rememberDarkTheme(themeMode)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Ds.colors.canvas) {
                    ChatApp(
                        settings = settings,
                        db = db,
                        themeMode = themeMode,
                        onThemeModeChange = {
                            settings.themeMode = it
                            themeMode = it
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到聊天页都对一次账：闹钟只覆盖当天，进前台就该把今天剩下的档位挂上
        lifecycleScope.launch {
            runCatching { com.dsmobile.app.service.ProactiveScheduler.refresh(this@ChatActivity) }
        }
        com.dsmobile.app.service.KeepAliveService.appVisible = true
        com.dsmobile.app.service.KeepAliveService.clearMessageNotification(this)
        // HyperOS 有时不遵守 Manifest 的 excludeFromRecents，运行时再设置一次当前任务。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                getSystemService(android.app.ActivityManager::class.java)
                    ?.appTasks
                    ?.firstOrNull()
                    ?.setExcludeFromRecents(true)
            }
        }
    }

    override fun onPause() {
        com.dsmobile.app.service.KeepAliveService.appVisible = false
        super.onPause()
    }
}

@Composable
private fun ChatApp(
    settings: SettingsStore,
    db: AppDatabase,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
) {
    var token by remember { mutableStateOf(settings.token) }
    val context = LocalContext.current
    if (token.isNullOrBlank()) {
        // 配对页是根页面：键盘开着先收键盘，再按一次才把任务收到后台，不结束 App
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val keyboard = LocalSoftwareKeyboardController.current
        BackHandler {
            if (imeVisible) keyboard?.hide() else (context as? Activity)?.moveTaskToBack(true)
        }
        PairingScreen(
            settings = settings,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onPaired = { token = it },
            onOpenDebug = { context.startActivity(Intent(context, MainActivity::class.java)) },
            onOpenAutostart = { openAutostartSettings(context) },
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            ChatScreen(
                settings = settings,
                db = db,
                token = token!!,
                onLogout = {
                    settings.token = null
                    token = null
                },
            )
            if (SettingsPanelState.visible) {
                BackHandler { SettingsPanelState.visible = false }
                SettingsScreen(
                    settings = settings,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onClose = { SettingsPanelState.visible = false },
                    onBiliLogin = { SettingsPanelState.onBiliLogin?.invoke() },
                    onNotificationSettings = { SettingsPanelState.onNotification?.invoke() },
                    onOpenDebug = { SettingsPanelState.onDebug?.invoke() },
                    onLogout = { SettingsPanelState.onLogout?.invoke() },
                )
            }
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

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        )
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
    val context = LocalContext.current
    val api = remember(settings.baseUrl, token, settings.biliSessdata, settings.biliCookie) {
        ApiClient(
            settings.baseUrl,
            token,
            settings.biliSessdata,
            settings.biliCookie,
            File(context.cacheDir, "api-media"),
        )
    }
    val messages by dao.observe().collectAsState(initial = emptyList())
    val pending by dao.observePending().collectAsState(initial = emptyList())
    val reversedMessages = remember(messages) { orderForDisplay(messages).asReversed() }
    var input by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<DraftAttachment?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var locationLat by remember { mutableStateOf<Double?>(null) }
    var locationLng by remember { mutableStateOf<Double?>(null) }
    var locationAccuracy by remember { mutableStateOf(0f) }
    var locationReason by remember { mutableStateOf("用户主动更新位置") }
    var status by remember { mutableStateOf("") }
    var streamingText by remember { mutableStateOf("") }
    var initialScrollDone by remember { mutableStateOf(false) }
    var historyReady by remember { mutableStateOf(false) }
    var stickToBottom by remember { mutableStateOf(true) }
    var animateSegments by remember { mutableStateOf(false) }
    val failedPendingIds = remember { mutableStateMapOf<Long, Boolean>() }
    // pending 气泡里的链接卡片状态：先骨架，解析完原地填。pending 删掉时一起清。
    val pendingCards = remember { mutableStateMapOf<Long, PendingCard>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val syncer = remember(dao, api, context) {
        ChatSync(dao, api, failedPendingIds, com.dsmobile.app.data.SettingsStore(context))
    }


    var cameraFile by remember { mutableStateOf<File?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    // 拍照交给系统相机：对焦、曝光、方向、清晰度都由它管，我们只管把文件接回来
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = cameraUri
        val file = cameraFile
        cameraUri = null
        cameraFile = null
        if (ok && uri != null) {
            // 和相册选的图走同一条路：先挂在输入框上方当草稿，你确认了再发
            scope.launch {
                draft = DraftAttachment(
                    uri = uri,
                    kind = "image",
                    name = file?.name ?: "拍照.jpg",
                    mime = "image/jpeg",
                    size = withContext(Dispatchers.IO) { file?.length() ?: 0L },
                    preview = withContext(Dispatchers.IO) { loadDraftPreview(context, uri) },
                )
            }
        } else {
            file?.delete()
        }
    }
    suspend fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("/更正")) {
            val fact = trimmed.removePrefix("/更正").trim()
            if (fact.isBlank()) {
                android.widget.Toast.makeText(context, "用法：/更正 职业=嵌入式开发", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            busy = true
            status = "正在更正记忆…"
            try {
                api.correctMemory(fact)
                android.widget.Toast.makeText(context, "记忆已更正", android.widget.Toast.LENGTH_SHORT).show()
                status = ""
            } catch (e: Exception) {
                status = "记忆更正失败：${e.message}"
            } finally {
                busy = false
            }
            return
        }
        stickToBottom = true
        busy = true
        // 先把自己的消息落到本地列表，B 站/网页解析在后台继续；用户不应等解析完才看见消息。
        status = "等待回复…"
        streamingText = ""
        val pendingMessage = PendingMessageEntity(
            content = text,
            clientMessageId = UUID.randomUUID().toString(),
        )
        val pendingId = dao.insertPending(pendingMessage)
        failedPendingIds.remove(pendingId)
        // 先按链接长相占个位：气泡 0ms 就完整出现（文字 + 骨架），解析结果原地填进去
        val earlyBili = BILI_URL_REGEX.find(text)?.value
        val earlyUrl = earlyBili ?: URL_REGEX.find(text)?.value
        if (earlyUrl != null) {
            pendingCards[pendingId] = PendingCard(earlyUrl, earlyBili != null, ready = false)
        }
        withFrameNanos { }
        listState.scrollToItem(0)
        val biliUrl = BILI_URL_REGEX.find(text)?.value
        val bili = if (biliUrl != null) {
            runCatching { api.biliPreview(biliUrl) }.getOrNull()
        } else {
            null
        }
        val webUrl = if (biliUrl == null) {
            URL_REGEX.find(text)?.value
        } else {
            null
        }
        val web = if (webUrl != null && bili == null) {
            runCatching { api.webPreview(webUrl) }.getOrNull()
        } else {
            null
        }
        val outgoing = when {
            bili != null && bili.title.isNotBlank() -> text + "\n\n" + biliMetaText(bili, biliUrl!!)
            web != null -> text + "\n\n" + webMetaText(web, webUrl!!)
            else -> text
        }
        // 解析结果先塞进缓存，再把骨架换成真卡片：卡片一出现就是完整的，
        // 不会先显示"哔哩哔哩视频"占位标题再刷一次封面。
        if (bili != null && !biliUrl.isNullOrBlank() && bili.title.isNotBlank()) {
            MediaCaches.putBiliPreview(biliUrl, bili)
            pendingCards[pendingId] = PendingCard(biliUrl, true, ready = true)
        } else if (web != null && !webUrl.isNullOrBlank()) {
            MediaCaches.putWebPreview(webUrl, web)
            pendingCards[pendingId] = PendingCard(webUrl, false, ready = true)
        } else if (earlyUrl != null) {
            // 没读出来就别留个空骨架骗人
            pendingCards.remove(pendingId)
            status = "链接没读出来，先按文字发"
        }
        val biliCookie = settings.biliCookie.ifBlank {
            if (settings.biliSessdata.isBlank()) "" else "SESSDATA=${settings.biliSessdata}"
        }
        // 取帧、抽细节帧、规划时间点都在 ChatBiliSend.prepareBiliMedia 里
        val media = prepareBiliMedia(
            api = api,
            bili = bili,
            text = text,
            biliCookie = biliCookie,
            cacheDir = context.cacheDir,
            debug = (context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
        val frames = media.frames
        val detailFrames = media.detailFrames
        // 带链接/视频时给状态一行提示，但不再固定停 1.5 秒：
        // 卡片在她开口前早就渲染好了，停顿改由"已读到开口"统一承担，避免两段空等叠加。
        val mediaTurn = bili != null || web != null ||
            text.contains("[视频:") || text.contains("[GIF:")
        if (mediaTurn) {
            status = "正看着你发的…"
            withFrameNanos { }
        }
        try {
            // 非流式等待完整回复，避免一边输出一边重排；语音先合成，再让消息进入列表逐句展示。
            val allFrames = frames + detailFrames
            // 静帧说明不再拼进正文：那段话以前会落库，然后原样出现在气泡里。
            // 现在只发画面，提示词由服务端补。
            val replyText = outgoing
            val reply = coroutineScope {
                // 等她回话的这几秒里每 0.7 秒对一次账：服务端一落库，pending 就被换成正身，
                // 气泡从"发送中"变"已发送"。否则界面毫无变化，看着像没发出去。
                val watcher = launch {
                    while (isActive) {
                        delay(700)
                        runCatching { syncer.syncEvents() }
                    }
                }
                try {
                    if (allFrames.isNotEmpty()) {
                        api.chatWithImages(
                            replyText,
                            allFrames.map { bitmapToDataUrl(it) },
                            pendingMessage.clientMessageId,
                            transient = true,
                        ).also {
                            allFrames.forEach { bmp -> bmp.recycle() }
                        }
                    } else {
                        api.chat(replyText, pendingMessage.clientMessageId)
                    }
                } finally {
                    watcher.cancel()
                }
            }
            val voices = parseChatSegments(reply)
                .filterIsInstance<ChatSegment.Voice>()
                .map { it.text }
                .distinct()
            voices.forEach { TtsManager.prepare(context, settings, it) }
            // 先让 D1 的 user/assistant 落进 Room，再移除本地 pending；
            // 反过来会先删掉自己的气泡，等网络回来才补上，产生可见的空白闪动。
            syncer.syncEvents()
            dao.deletePending(pendingMessage.copy(id = pendingId))
            failedPendingIds.remove(pendingId)
            pendingCards.remove(pendingId)
            streamingText = ""
            status = ""
        } catch (e: HeldException) {
            // 被闸门压住：别挂成「发送失败」，那会让人反复重发同一条。
            // 消息早已落库，等下一轮对账把 pending 换成正身即可。
            frames.forEach { it.recycle() }
            runCatching { syncer.syncEvents() }
            runCatching { syncer.reconcilePending() }
            // 到点拉取：按她忙完/睡醒的时刻挂一次闹钟，不再靠轮询
            runCatching { com.dsmobile.app.service.ProactiveScheduler.scheduleHoldPull(context, e.untilMs) }
            dao.deletePending(pendingMessage.copy(id = pendingId))
            pendingCards.remove(pendingId)
            failedPendingIds.remove(pendingId)
            streamingText = ""
            status = ""
            android.widget.Toast.makeText(context, e.message, android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            frames.forEach { it.recycle() }
            failedPendingIds[pendingId] = true
            // 502 可能发生在服务端已落库之后；对账一次，避免成功消息还卡在 pending。
            runCatching { syncer.reconcilePending() }
            status = "发送失败：${e.message}，可在下方重试"
            streamingText = ""
        } finally {
            busy = false
        }
    }

    fun sendDraft() {
        val attachment = draft ?: return
        val text = input.trim()
        draft = null
        input = ""
        scope.launch {
            busy = true
            status = "处理附件…"
            val clientId = UUID.randomUUID().toString()
            val pendingId = dao.insertPending(
                PendingMessageEntity(
                    content = text,
                    clientMessageId = clientId,
                    localUri = attachment.uri.toString(),
                    attachmentKind = attachment.kind,
                    attachmentName = attachment.name,
                )
            )
            stickToBottom = true
            withFrameNanos { }
            listState.scrollToItem(0)
            // ★ 附件先露个面：气泡已经在列表里，再等 1.5 秒才请求
            status = "正看着你发的…"
            withFrameNanos { }
            delay(MEDIA_SETTLE_MS)
            try {
                when (attachment.kind) {
                    "image" -> {
                        // 先把自己这边的位图放进缓存：消息刷新后气泡直接命中，不再"消失一下"
                        val localDrawable = withContext(Dispatchers.IO) {
                            runCatching {
                                ImageDecoder.decodeDrawable(
                                    ImageDecoder.createSource(context.contentResolver, attachment.uri),
                                )
                            }.getOrNull()
                        }
                        localDrawable?.let { MediaCaches.rememberOutgoing(it) }
                        if (attachment.mime == "image/gif") {
                            val key = api.uploadMedia(context, attachment.uri)
                            // GIF 的 key 上传后就知道，直接按 key 绑，不依赖"认领"
                            localDrawable?.let { MediaCaches.putImage(key, it) }
                            val caption = if (text.isBlank()) "[GIF:$key]" else "$text\n[GIF:$key]"
                            // 动图几 MB，模型那边会整段丢掉——只看到一串路径，等于没看见。
                            // 这里同时补一张首帧静图给它看画面；气泡里仍然是会动的 GIF。
                            // 抽九帧拼成一张：只有首帧的话，她只能看出「谁、拿着什么」，
                            // 看不出「怎么动的」。九帧能读出整个动作过程。
                            val frame = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bytes = context.contentResolver
                                        .openInputStream(attachment.uri)
                                        ?.use { it.readBytes() }
                                    if (bytes == null) return@runCatching null
                                    val sheet = gifContactSheet(bytes)
                                    if (sheet != null) {
                                        val dataUrl = bitmapToDataUrl(sheet)
                                        sheet.recycle()
                                        dataUrl
                                    } else {
                                        // 抽帧失败（非标准 GIF）就退回首帧，至少能看到画面
                                        val bmp = ImageDecoder.decodeBitmap(
                                            ImageDecoder.createSource(context.contentResolver, attachment.uri),
                                        )
                                        val dataUrl = bitmapToDataUrl(bmp)
                                        bmp.recycle()
                                        dataUrl
                                    }
                                }.getOrNull()
                            }
                            if (frame != null) {
                                api.chatWithImages(caption, listOf(frame), clientId, transient = true)
                            } else {
                                api.chat(caption, clientId)
                            }
                        } else {
                            val dataUrl = MediaUtils.imageToDataUrl(context, attachment.uri)
                            api.chatWithImage(text, dataUrl, clientId)
                        }
                    }
                    "video" -> {
                        val key = api.uploadMedia(context, attachment.uri)
                        // 抽不出画面时至少还能发文字 + 链接
                        val plainCaption = if (text.isBlank()) "[视频:$key]" else "$text\n[视频:$key]"
                        // 首选：让千问 omni 把整段视频看完，换成一份带时间点的笔记。
                        // 它同时听得到声音，所以不需要再跑 whisper，也不用抽帧。
                        // 失败（没配 key、超时、限流）就走下面那条老路，消息照发。
                        val vision = runCatching { api.videoVision(key) }.getOrNull();
                        if (!vision.isNullOrBlank()) {
                        val meta = runCatching { VideoFrames.meta(context, attachment.uri) }.getOrNull()
                        val card = VideoCard.buildFromVision(
                        durationMs = meta?.first ?: 0L,
                        aspect = meta?.second ?: 1f,
                        observation = vision,
                        )
                        val caption = listOf(text, "[视频:$key]", card)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                        api.chat(caption, clientId)
                        } else {
                        // 排版跟 B 站抽帧一致：固定 3 列，横屏 3×3、竖屏 3×2，时长越长越多张。
                        // 只发宫格不发单帧——13 张图（12 帧 + 1 宫格）换来的细节，
                        // 宫格放大后一样读得到，但 token 差一个量级。
                        // 抽不出来（编码怪、时长读不到）就退回纯文字，至少不挡住消息。
                        // 抽帧和抽音轨并行：一个给人看，一个给耳朵听。
                        // 音轨中间的失败都不影响发消息，最多卡里少一段台词。
                        val planned = runCatching { VideoFrames.extractPlanned(context, attachment.uri) }
                            .getOrNull()
                        val (asrLines, asrFailed) = runCatching {
                            withContext(Dispatchers.IO) {
                                val audio = VideoAudio.extract(context, attachment.uri)
                                    ?: return@withContext Pair(emptyList<com.dsmobile.app.AsrLine>(), false)
                                try {
                                    api.videoAsr(audio)
                                } finally {
                                    audio.delete()
                                }
                            }
                        }.getOrDefault(Pair(emptyList<com.dsmobile.app.AsrLine>(), true))
                        if (planned == null || planned.frames.isEmpty()) {
                            api.chat(plainCaption, clientId)
                        } else {
                            val plan = planned.plan
                            val sheets = runCatching {
                                planned.frames.chunked(plan.perSheet).mapIndexedNotNull { i, chunk ->
                                    val header = if (plan.sheets <= 1) {
                                        "同一段视频，按时间从左到右、从上到下"
                                    } else {
                                        "同一段视频 第 ${i + 1}/${plan.sheets} 张，按时间从左到右、从上到下"
                                    }
                                    contactSheet(chunk, plan.cols, plan.rows, header)
                                }
                            }.getOrDefault(emptyList())
                            planned.frames.forEach { it.recycle() }
                            // 观察卡：时长、横竖、台词、怎么读宫格——和 B 站那张同构
                            val card = VideoCard.build(
                                durationMs = planned.durationMs,
                                aspect = planned.aspect,
                                cols = plan.cols,
                                rows = plan.rows,
                                sheets = plan.sheets,
                                asr = asrLines,
                                asrFailed = asrFailed,
                            )
                            val caption = listOf(text, "[视频:$key]", card)
                                .filter { it.isNotBlank() }
                                .joinToString("\n\n")
                            if (sheets.isEmpty()) {
                                api.chat(caption, clientId)
                            } else {
                                val frames = sheets.map { bitmapToDataUrl(it) }
                                sheets.forEach { it.recycle() }
                                api.chatWithImages(caption, frames, clientId, transient = true)
                            }
                        }
                        }
                    }
                    else -> {
                        val doc = DocumentParser.parse(context, attachment.uri)
                        val safeName = doc.name.replace('|', '_').replace(']', '_')
                        val body = "[文件:$safeName|${attachment.size}|${attachment.mime.replace(']', '_')}]\n" +
                            "【文件内容】\n${doc.text}\n【/文件】"
                        api.chat(if (text.isBlank()) body else "$text\n\n$body", clientId)
                    }
                }
                syncer.syncEvents()
                status = ""
            } catch (e: Exception) {
                failedPendingIds[pendingId] = true
                status = "附件发送失败：${e.message}"
            } finally {
                busy = false
            }
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

    val recorder = remember { VoiceRecorder() }
    var recording by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    fun startAzureRecording() {
        if (settings.azureKey.isBlank()) {
            status = "未配置 Azure Speech Key，改用系统识别"
            startVoice()
            return
        }
        if (recorder.start()) {
            recording = true
            status = "录音中…再点“停止”结束"
        } else {
            status = "无法启动录音，改用系统识别"
            startVoice()
        }
    }

    fun stopAzureRecording() {
        recording = false
        val wav = recorder.stop()
        if (wav == null) {
            status = "录音太短"
            return
        }
        status = "转写中…"
        scope.launch {
            try {
                val text = api.stt(wav, settings.azureKey, settings.azureRegion)
                status = ""
                if (text.isNotBlank()) sendText(text)
            } catch (e: Exception) {
                status = "转写失败：${e.message}"
            }
        }
    }

    val voicePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAzureRecording() else status = "需要麦克风权限"
    }

    fun beginVoiceHold() {
        if (recording || busy) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startAzureRecording()
    }

    fun endVoiceHold() {
        if (recording) stopAzureRecording()
    }

    fun cancelVoiceHold() {
        if (recording) {
            recorder.cancel()
            recording = false
            status = ""
        }
    }

    val biliLoginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            MediaCaches.clearBili()
            (context as? Activity)?.recreate()
        }
    }

    fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            status = "需要定位权限"
            return
        }
        try {
            val lm = context.getSystemService(LocationManager::class.java)
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            val last = providers
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null) {
                locationLat = last.latitude
                locationLng = last.longitude
                locationAccuracy = last.accuracy
                showLocationDialog = true
                return
            }
            val provider = providers.firstOrNull { lm.isProviderEnabled(it) }
            if (provider == null) {
                status = "定位服务未开启"
                return
            }
            lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { loc ->
                if (loc == null) {
                    status = "暂时拿不到当前位置"
                } else {
                    locationLat = loc.latitude
                    locationLng = loc.longitude
                    locationAccuracy = loc.accuracy
                    showLocationDialog = true
                }
            }
        } catch (e: Exception) {
            status = "定位失败：${e.message}"
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchLocation() else status = "需要定位权限"
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val mime = context.contentResolver.getType(uri) ?: "image/*"
                draft = DraftAttachment(
                    uri = uri,
                    kind = "image",
                    name = queryAttachmentName(context, uri),
                    mime = mime,
                    size = queryAttachmentSize(context, uri),
                    preview = withContext(Dispatchers.IO) { loadDraftPreview(context, uri) },
                )
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                draft = DraftAttachment(
                    uri = uri,
                    kind = "video",
                    name = queryAttachmentName(context, uri),
                    mime = context.contentResolver.getType(uri) ?: "video/*",
                    size = queryAttachmentSize(context, uri),
                    preview = null,
                )
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            scope.launch {
                draft = DraftAttachment(
                    uri = uri,
                    kind = "file",
                    name = queryAttachmentName(context, uri),
                    mime = context.contentResolver.getType(uri) ?: "application/octet-stream",
                    size = queryAttachmentSize(context, uri),
                    preview = null,
                )
            }
        }
    }

    // 到点提醒：每次进聊天页对一次账（幂等重挂），计划接口挂了就用兜底时段
    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            runCatching { com.dsmobile.app.service.ProactiveScheduler.refresh(context) }
        }
    }

    // 后台常驻默认关闭：只有设置里开了才常驻（省电、少一条常驻通知）
    LaunchedEffect(token, settings.keepAliveEnabled) {
        if (token.isNotBlank() && settings.keepAliveEnabled) {
            com.dsmobile.app.service.KeepAliveService.start(context)
        } else {
            com.dsmobile.app.service.KeepAliveService.stop(context)
        }
    }

    // 先把本地 Room 缓存显示出来，不等网络；首装无缓存时才显示加载态。
    LaunchedEffect(messages.size, pending.size) {
        if (!historyReady && (messages.isNotEmpty() || pending.isNotEmpty())) {
            historyReady = true
        }
    }

    LaunchedEffect(Unit) {
        try {
            // 旧版本可能已把 [END]/历史时间标签当成正文落进 Room，启动时清理一次。
            dao.stripSilentEndMarkers()
            dao.stripLeadingTimeLabels()
            // 首装全量补齐历史；已有旧版本缓存则增量 + 往前回填更早的记录。
            if (dao.maxId() == 0L) {
                syncer.syncAllHistory()
                // D1 只有最近两天；首装也要继续从 R2 归档把更早的历史回灌。
                syncer.backfillOlderHistory()
            } else {
                syncer.syncEvents()
                syncer.backfillOlderHistory()
            }
            syncer.reconcilePending()
        } catch (e: Exception) {
            android.util.Log.w("ChatSync", "initial sync failed: ${e.message}", e)
            status = "同步失败：${e.message}"
        } finally {
            // 首屏等历史补齐后再显示，避免用户翻到一半突然冒出旧记录。
            // 旧消息必须直接完整显示；只有启动后新到达的消息才做逐句动画。
            runCatching { revealedMessageIds.addAll(dao.allIds()) }
            historyReady = true
            animateSegments = true
        }
        while (true) {
            delay(5_000)
            // 退到后台就完全停：这时靠闹钟到点拉，前台不替后台打请求
            if (!com.dsmobile.app.service.KeepAliveService.appVisible) continue
            try {
                syncer.syncEvents()
            } catch (_: Exception) {
                // 轮询失败不打断界面，下一轮重试
            }
        }
    }

    // 首屏把最近几张动图预解码到内存，避免第一次弹出键盘时才开始解码动画。
    // 这是有限预热，不做全量预加载；原图字节仍走 MediaDiskCache。
    LaunchedEffect(historyReady) {
        if (!historyReady) return@LaunchedEffect
        delay(1_200) // 让首屏先完成布局，避免预解码和第一次键盘弹出抢 CPU
        withContext(Dispatchers.IO) {
            val mediaPattern = Regex("""\[(?:图片|GIF):([^\]]+)]""")
            val keys = messages.takeLast(12)
                .flatMap { m -> mediaPattern.findAll(m.content).map { it.groupValues[1] }.toList() }
                .distinct()
                .take(4)
            for (key in keys) {
                if (MediaCaches.image(key) != null) continue
                runCatching {
                    val bytes = api.image(key)
                    val drawable = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                    )
                    MediaCaches.putImage(key, drawable)
                }
            }
        }
    }

    val itemCount = messages.size + pending.size + if (streamingText.isNotEmpty()) 1 else 0
    val canSend = !busy && (input.isNotBlank() || draft != null)
    val focusManager = LocalFocusManager.current
    var inputFocused by remember { mutableStateOf(false) }
    var trayOpen by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    /**
     * 返回键逐层收：附件抽屉 → 语音面板 → 键盘 → 才离开聊天页。
     * 之前每层都直接把 Activity 结束掉，用到一半按一下就退出 App。
     */
    BackHandler {
        when {
            trayOpen -> trayOpen = false
            voiceMode -> voiceMode = false
            inputFocused || imeVisible -> {
                inputFocused = false
                focusManager.clearFocus()
                keyboard?.hide()
            }
            else -> (context as? Activity)?.moveTaskToBack(true)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { (scrolling, index, offset) ->
            // reverseLayout 下 index=0 + offset=0 才是真正贴底。
            // 只在用户真正滚动时把跟随关掉；新消息插入造成的 index 变化不能误判。
            val atBottom = index == 0 && offset == 0
            if (scrolling) {
                stickToBottom = atBottom
            } else if (atBottom) {
                stickToBottom = true
            }
        }
    }
    fun atBottom(): Boolean =
        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0

    val scrollToLatest: () -> Unit = {
        scope.launch {
            if (!stickToBottom) return@launch
            delay(120)
            if (itemCount > 0 && !listState.isScrollInProgress && !atBottom()) {
                listState.animateScrollToItem(0)
            }
        }
    }
    LaunchedEffect(itemCount) {
        if (itemCount <= 0) return@LaunchedEffect
        if (!initialScrollDone) {
            // reverseLayout 的 index 0 就是底部；首次打开直接定位。
            repeat(4) {
                listState.scrollToItem(0)
                withFrameNanos { }
                delay(40)
            }
            initialScrollDone = true
        } else if (stickToBottom && !listState.isScrollInProgress && !atBottom()) {
            // 已经在底部时不再滚动；新 item 已经在 index 0，额外滚动只会造成闪跳。
            listState.animateScrollToItem(0)
        }
    }

    // 键盘弹出后重新贴底：窗口变矮时反向列表偶尔会停在半空，补一次对齐。
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && stickToBottom && !listState.isScrollInProgress) {
            delay(80)
            listState.scrollToItem(0)
        }
    }

    // 她的生活状态：睡了 / 在忙 · 看电影 / 有空。拉不到就退回"在线 · 已连接"。
    var lifeStatus by remember { mutableStateOf<com.dsmobile.app.net.LifeStatus?>(null) }
    // 顶栏只留三个词：细节在状态页里看，这里越短越好
    val lifeLine = when (lifeStatus?.status) {
        "sleeping" -> "睡了"
        "busy" -> "在忙"
        "free" -> "有空"
        else -> null
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (com.dsmobile.app.service.KeepAliveService.appVisible) {
                lifeStatus = runCatching { api.aiStatus() }.getOrNull()
            }
            delay(180_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ds.colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        ChatTopBar(
            name = "大肥鱼",
            statusText = if (busy) "正在回复…" else (lifeLine ?: "在线 · 已连接"),
            online = lifeStatus?.status != "sleeping",
            busy = busy,
            onAvatar = { StatusPanelState.visible = true },
            onStatus = { StatusPanelState.visible = true },
            onSettings = { SettingsPanelState.visible = true },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!historyReady) {
                ChatLoading()
            } else if (messages.isEmpty() && pending.isEmpty() && streamingText.isEmpty()) {
                EmptyChat(onQuickStart = { text -> scope.launch { sendText(text) } })
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (historyReady) 1f else 0f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // reverseLayout：index 0 在底部，所以“正在输入/未发送”放在最前面。
                if (streamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        StreamingMessage(streamingText, onReveal = scrollToLatest)
                    }
                }
                items(pending.asReversed(), key = { "pending-${it.id}" }) { message ->
                    PendingMessageRow(
                        message = message,
                        api = api,
                        biliCookie = settings.biliCookie.ifBlank {
                            if (settings.biliSessdata.isBlank()) "" else "SESSDATA=" + settings.biliSessdata
                        },
                        card = pendingCards[message.id],
                        busy = busy,
                        failed = failedPendingIds[message.id] == true,
                        onRetry = {
                            scope.launch {
                                busy = true
                                failedPendingIds.remove(message.id)
                                status = "重试发送…"
                                try {
                                    api.chat(message.content, message.clientMessageId)
                                    dao.deletePending(message)
                                    failedPendingIds.remove(message.id)
                                    delay(300)
                                    syncer.syncEvents()
                                    status = ""
                                } catch (e: HeldException) {
                                    runCatching { com.dsmobile.app.service.ProactiveScheduler.scheduleHoldPull(context, e.untilMs) }
                                    dao.deletePending(message)
                                    failedPendingIds.remove(message.id)
                                    status = ""
                                    android.widget.Toast.makeText(
                                        context,
                                        (e.message ?: "她这会儿在忙，等会儿回你"),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                } catch (e: Exception) {
                                    failedPendingIds[message.id] = true
                                    status = "发送失败：${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        onDismiss = {
                            scope.launch {
                                dao.deletePending(message)
                                failedPendingIds.remove(message.id)
                                pendingCards.remove(message.id)
                                status = ""
                            }
                        },
                    )
                }
                itemsIndexed(
                    items = reversedMessages,
                    key = { _, message -> message.id },
                    contentType = { _, _ -> "message" },
                ) { index, message ->
                    val older = reversedMessages.getOrNull(index + 1)
                    val newer = reversedMessages.getOrNull(index - 1)
                    val day = messageDay(message.createdAt)
                    val newDay = older == null || messageDay(older.createdAt) != day
                    if (newDay) {
                        DaySeparator(day)
                    }
                    // 主动消息总是另起一组：它和半小时前的对话不是同一段，
                    // 混在一起就看不出来"这句是几点自己冒出来的"。
                    val gapMinutes = if (older != null && !newDay) {
                        minutesOfDay(message.createdAt) - minutesOfDay(older.createdAt)
                    } else {
                        0
                    }
                    val proactiveBreak = message.proactiveKind != null &&
                        (older == null || older.proactiveKind == null || newDay || gapMinutes >= 10)
                    // 同一个人连着说时贴紧，换人留一口气——全一样间距会糊成一片
                    val sameSpeaker = older != null && !newDay &&
                        older.role == message.role && !proactiveBreak
                    val nextSameSpeaker = newer != null &&
                        messageDay(newer.createdAt) == day &&
                        newer.role == message.role
                    if (!sameSpeaker && older != null) {
                        Spacer(Modifier.height(10.dp))
                    }
                    MessageItem(
                        message = message,
                        api = api,
                        biliCookie = settings.biliCookie.ifBlank {
                            if (settings.biliSessdata.isBlank()) "" else "SESSDATA=${settings.biliSessdata}"
                        },
                        showMeta = !sameSpeaker,
                        grouped = sameSpeaker,
                        // 三档：发出去但没落库（pending，气泡自己显示"发送中"）→
                        // 落库了但还没回（"已发送"）→ 她的回复到了这台手机（"已读"）。
                        receipt = when {
                            message.role != "user" || nextSameSpeaker -> null
                            newer?.role == "assistant" -> Receipt.READ
                            else -> Receipt.SENT
                        },
                        animate = animateSegments,
                        onSpeak = { TtsManager.speak(context, settings, it) },
                        onPrefetch = { TtsManager.prefetch(context, settings, it) },
                        onReveal = scrollToLatest,
                    )
                }
            }
            if (historyReady && !stickToBottom && itemCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 12.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Ds.colors.surface)
                        .border(1.dp, Ds.colors.hairline, CircleShape)
                        .clickable {
                            scope.launch {
                                stickToBottom = true
                                listState.animateScrollToItem(0)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "回到底部",
                        tint = Ds.colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (status.contains("失败") || status.startsWith("录音") || status.startsWith("转写")) {
            StatusStrip(status)
        }

        ChatComposer(
            input = input,
            onInputChange = { input = it },
            canSend = input.isNotBlank() || draft != null,
            busy = busy,
            draft = draft,
            onRemoveDraft = { draft = null },
            trayOpen = trayOpen,
            onTrayChange = { trayOpen = it },
            onSend = {
                if (draft != null) {
                    sendDraft()
                } else {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        input = ""
                        scope.launch { sendText(text) }
                    }
                }
            },
            onPickCamera = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                // 先落一个空文件：部分相机 App 直接往 uri 里写，文件不存在会失败
                runCatching { file.createNewFile() }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                cameraFile = file
                cameraUri = uri
                runCatching { takePicture.launch(uri) }.onFailure {
                    cameraFile = null
                    status = "打不开系统相机：${it.message}"
                }
            },
            onPickImage = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickFile = {
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
            onPickVideo = {
                videoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            },
            onPickLocation = {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    fetchLocation()
                } else {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            voiceMode = voiceMode,
            onEnterVoice = {
                voiceMode = true
                inputFocused = false
            },
            onExitVoice = { voiceMode = false },
            recording = recording,
            onHoldStart = { beginVoiceHold() },
            onHoldEnd = { endVoiceHold() },
            onHoldCancel = { cancelVoiceHold() },
            onFocusGained = { stickToBottom = true },
        )

        if (showLocationDialog && locationLat != null && locationLng != null) {
            AlertDialog(
                onDismissRequest = { showLocationDialog = false },
                containerColor = Ds.colors.surface,
                shape = RoundedCornerShape(24.dp),
                title = { Text("更新当前位置", color = Ds.colors.ink) },
                text = {
                    Column {
                        Text(
                            "纬度 ${"%.5f".format(locationLat)}，经度 ${"%.5f".format(locationLng)}" +
                                if (locationAccuracy > 0) "（精度约 ${locationAccuracy.toInt()}m）" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ds.colors.inkSecondary,
                        )
                        Spacer(Modifier.height(12.dp))
                        DsField(
                            label = "变更原因",
                            value = locationReason,
                            onValueChange = { locationReason = it.take(120) },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            val lat = locationLat ?: return@TextButton
                            val lng = locationLng ?: return@TextButton
                            scope.launch {
                                busy = true
                                try {
                                    api.updateLocation(lat, lng, locationAccuracy, locationReason)
                                    android.widget.Toast.makeText(
                                        context,
                                        "位置已更新并记入记忆",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                    showLocationDialog = false
                                } catch (e: Exception) {
                                    status = "位置更新失败：${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text("确认更新", color = Ds.colors.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { showLocationDialog = false }) {
                        Text("取消", color = Ds.colors.inkSecondary)
                    }
                },
            )
        }
        if (StatusPanelState.visible) {
            StatusScreen(api = api, onClose = { StatusPanelState.visible = false })
        }

        SideEffect {
            SettingsPanelState.onBiliLogin = {
                biliLoginLauncher.launch(Intent(context, BiliLoginActivity::class.java))
            }
            SettingsPanelState.onNotification = {
                SettingsPanelState.visible = false
                openNotificationSettings(context)
            }
            SettingsPanelState.onDebug = {
                context.startActivity(Intent(context, MainActivity::class.java))
            }
            SettingsPanelState.onLogout = {
                SettingsPanelState.visible = false
                onLogout()
            }
        }
    }
}


/**
 * 回复要挂在「被回的那条」下面。
 *
 * 平时 id 递增，回复自然挨着问句；但补发是把几小时前压住的旧消息现在才回，
 * 回复的 id 很大、时间戳是现在，直接按 id 排就会跑到列表最末尾——看起来像
 * 她在自言自语。这里用「父消息 id」当排序锚点：回复排到问句后面，
 * 其余消息仍按自己的 id 排。
 */
internal fun orderForDisplay(messages: List<MessageEntity>): List<MessageEntity> {
    val byId = messages.associateBy { it.id }
    return messages
        .map { m ->
            val parent = m.replyToId?.let { byId[it] }
            val anchor = if (m.role == "assistant" && parent != null && parent.role == "user") {
                parent.id
            } else {
                m.id
            }
            m to anchor
        }
        .sortedWith(compareBy({ it.second }, { it.first.id }))
        .map { it.first }
}
