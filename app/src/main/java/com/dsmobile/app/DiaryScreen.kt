package com.dsmobile.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.net.DiaryDetail
import com.dsmobile.app.net.DiaryItem
import com.dsmobile.app.ui.components.DsButton
import com.dsmobile.app.ui.components.DsSheetHandle
import com.dsmobile.app.ui.theme.Ds
import com.dsmobile.app.ui.theme.DsSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 她的日记。
 *
 * 设计上刻意做成「一本纸本子」：米色纸、楷体、日期压在左上，锁着的那些页
 * 只露日期和天气，正文压根不在客户端手里（服务端就不给）。
 *
 * 只算当天的同意：那天过了就要再问她一次。
 */
private val PaperTop = Color(0xFFFDF6E3)
private val PaperBottom = Color(0xFFFFFBF1)
private val InkBrown = Color(0xFF3D2B1F)
private val InkBrownSoft = Color(0xFF6B5344)
private val PaperLine = Color(0xFFEFE3CC)
private val StampRed = Color(0xFFA33A32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(api: ApiClient, onClose: () -> Unit) {
    var items by remember { mutableStateOf<List<DiaryItem>?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<DiaryDetail?>(null) }
    var askedDates by remember { mutableStateOf<Set<String>>(emptySet()) }
    var askDate by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(refreshKey) {
        val result = withTimeoutOrNull(12_000) { api.diaryList() }
        items = result ?: emptyList()
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = PaperTop,
        contentColor = InkBrown,
        dragHandle = { DsSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DsSpace.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(DsSpace.md),
        ) {
            if (detail == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "她的日记",
                            fontSize = 28.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 4.sp,
                            color = InkBrown,
                        )
                        Text(
                            if (items.isNullOrEmpty()) "每天凌晨，写前一天" else "共 ${items!!.size} 篇　每天凌晨写前一天",
                            fontSize = 12.sp,
                            letterSpacing = 1.5.sp,
                            color = InkBrownSoft,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(StampRed),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("记", fontSize = 19.sp, color = Color(0xFFFFF8EA), fontFamily = FontFamily.Serif)
                    }
                }

                when {
                    items == null -> Text("翻开中…", fontSize = 14.sp, color = InkBrownSoft)
                    items!!.isEmpty() -> Text("还没有写过日记", fontSize = 14.sp, color = InkBrownSoft)
                    else -> items!!.forEach { item ->
                        DiaryCard(
                            item = item,
                            asked = askedDates.contains(item.date),
                            onClick = {
                                opening = item.date
                                detail = null
                            },
                        )
                    }
                }

                Text(
                    "看得到的，是她那天愿意给你看的；过了那天，就得再问她一次。",
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = Color(0xFFA08E7E),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = DsSpace.sm),
                )
            } else {
                DiaryPage(
                    api = api,
                    detail = detail!!,
                    onBack = { detail = null },
                    onAsk = { date ->
                        detail = null
                        askedDates = askedDates + date
                        items = items?.map { if (it.date == date) it.copy(asked = true) else it }
                        askDate = date
                    },
                )
            }
            Spacer(Modifier.height(DsSpace.xl))
        }
    }

    // 「问问她」：标成 pending 的动作放到协程里，失败就当没问成，下次再按
    LaunchedEffect(askDate) {
        val date = askDate ?: return@LaunchedEffect
        withTimeoutOrNull(12_000) { api.askDiary(date) }
        askDate = null
    }

    // 打开某一篇：进页面时才取正文（列表响应里没有正文）
    LaunchedEffect(opening) {
        val date = opening ?: return@LaunchedEffect
        detail = withTimeoutOrNull(12_000) { api.diaryDetail(date) }
        if (detail == null) {
            detail = DiaryDetail(date = date, visible = false, reason = "打不开，网络不太好")
        }
        opening = null
    }
}

@Composable
private fun DiaryCard(item: DiaryItem, asked: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(PaperTop, PaperBottom)))
            .border(1.dp, PaperLine, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatDay(item.date),
                fontSize = 20.sp,
                fontFamily = FontFamily.Serif,
                letterSpacing = 1.sp,
                color = InkBrown,
            )
            Spacer(Modifier.size(10.dp))
            Text(item.weekday, fontSize = 12.sp, letterSpacing = 1.sp, color = InkBrownSoft)
            Spacer(Modifier.weight(1f))
            Text(weatherText(item.weather, item.tempC, item.location), fontSize = 12.sp, color = InkBrownSoft)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x66FFFFFF))
                    .border(1.dp, PaperLine, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(stateText(item, asked), fontSize = 10.sp, letterSpacing = 1.sp, color = InkBrownSoft)
            }
            if ((item.health ?: 100) < 80) {
                Spacer(Modifier.size(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, PaperLine, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("那天有点虚", fontSize = 10.sp, letterSpacing = 1.sp, color = InkBrownSoft)
                }
            }
        }
    }
}

@Composable
private fun DiaryCover(api: ApiClient, key: String) {
    var bitmap by remember(key) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        val bytes = withTimeoutOrNull(12_000) { api.diaryImage(key) }
        bitmap = bytes?.let {
            runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
        failed = bitmap == null
    }
    val bmp = bitmap
    when {
        bmp != null -> Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp)),
            contentScale = ContentScale.FillWidth,
        )
        failed -> Unit
        else -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x22A33A32)),
        )
    }
}

@Composable
private fun DiaryPage(api: ApiClient, detail: DiaryDetail, onBack: () -> Unit, onAsk: (String) -> Unit) {
    Text(
        "← 回去",
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        color = InkBrownSoft,
        modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(PaperTop, PaperBottom)))
            .border(1.dp, PaperLine, RoundedCornerShape(6.dp))
            .padding(24.dp),
    ) {
        if (detail.visible && detail.text != null) {
            Text(
                "${detail.date}　${detail.weekday}",
                fontSize = 22.sp,
                fontFamily = FontFamily.Serif,
                letterSpacing = 1.sp,
                color = InkBrown,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                weatherText(detail.weather, detail.tempC, detail.location) + "　·　${detail.text.length} 字",
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = InkBrownSoft,
            )
            if (detail.images.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                DiaryCover(api = api, key = detail.images.first())
            }
            if (detail.peeked) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "她只给了你看这些，剩下的在她那儿",
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = StampRed,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                detail.text,
                fontSize = 16.sp,
                lineHeight = 32.sp,
                fontFamily = FontFamily.Serif,
                color = InkBrown,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textIndent = TextIndent(firstLine = 32.sp),
                ),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "——她",
                fontSize = 13.sp,
                color = InkBrownSoft,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color(0x66A33A32), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("锁", fontSize = 24.sp, fontFamily = FontFamily.Serif, color = StampRed)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "${detail.date}　${detail.weekday}",
                    fontSize = 19.sp,
                    fontFamily = FontFamily.Serif,
                    color = InkBrown,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    weatherText(detail.weather, detail.tempC, detail.location),
                    fontSize = 12.sp,
                    color = InkBrownSoft,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    lockText(detail),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Serif,
                    color = InkBrownSoft,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "只能看到日期和天气。正文在她那儿。",
                    fontSize = 11.sp,
                    color = Color(0xCCA08E7E),
                )
                Spacer(Modifier.height(18.dp))
                if (detail.asked) {
                    Text("等她说", fontSize = 13.sp, letterSpacing = 2.sp, color = Color(0x88A33A32))
                } else {
                    DsButton("问问她", onClick = { onAsk(detail.date) }, fillWidth = false)
                }
            }
        }
    }
}

private fun stateText(item: DiaryItem, asked: Boolean): String = when {
    item.visible -> "今天能看到"
    asked -> "问过了，她还没答"
    item.expired -> "收起来了，要再问"
    else -> "锁着"
}

private fun lockText(detail: DiaryDetail): String = when {
    detail.asked -> "问过了，正在等她回话"
    detail.expired -> "那天给你看过，已经收起来了"
    detail.reason.isNotBlank() -> detail.reason
    else -> "这篇她还没说要不要给你看"
}

private fun weatherText(weather: String, tempC: Int?, location: String): String {
    val icon = when (weather) {
        "sunny" -> "☀️"
        "cloudy" -> "☁️"
        "rain" -> "🌧️"
        "snow" -> "❄️"
        else -> ""
    }
    val parts = mutableListOf<String>()
    if (icon.isNotEmpty() || tempC != null) {
        parts.add(listOf(icon, tempC?.let { "${it}℃" } ?: "").filter { it.isNotEmpty() }.joinToString(" "))
    }
    if (location.isNotBlank()) parts.add(location)
    return parts.joinToString(" · ")
}

/** 2026-11-01 → 11月1日 */
private fun formatDay(date: String): String {
    if (date.length < 10) return date
    val m = date.substring(5, 7).trimStart('0')
    val d = date.substring(8, 10).trimStart('0')
    return m + "月" + d + "日"
}
