package com.dsmobile.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.net.StatusSnapshot
import com.dsmobile.app.ui.components.DsButton
import com.dsmobile.app.ui.components.DsCard
import com.dsmobile.app.ui.components.DsDivider
import com.dsmobile.app.ui.components.DsIconButton
import com.dsmobile.app.ui.components.DsSectionLabel
import com.dsmobile.app.ui.components.DsSheetHandle
import com.dsmobile.app.ui.theme.Ds
import com.dsmobile.app.ui.theme.DsSpace
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 她的状态：点顶栏头像打开。
 *
 * 状态本身是模糊的一句话（服务端就不给数值），所以这一页不做进度条、不做百分比，
 * 只把「她现在什么样子」用文字和留白传达出来；唯一给数字的是余额。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(api: ApiClient, onClose: () -> Unit) {
    var snapshot by remember { mutableStateOf<StatusSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showDiary by remember { mutableStateOf(false) }
    var life by remember { mutableStateOf<com.dsmobile.app.net.LifeStatus?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(refreshKey) {
        loading = true
        failed = false
        // 网络卡住时不要让她一直显示「加载中」：10 秒没结果就当失败，给一个重试入口
        life = withTimeoutOrNull(8_000) { runCatching { api.aiStatus() }.getOrNull() }
        val result = withTimeoutOrNull(10_000) { runCatching { api.status() }.getOrNull() }
        snapshot = result
        failed = result == null
        loading = false
    }

    if (!showDiary) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Ds.colors.canvas,
        contentColor = Ds.colors.ink,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.avatar),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(CircleShape),
                )
                Spacer(Modifier.size(DsSpace.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        "大肥鱼",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ds.colors.ink,
                    )
                    Text(
                        if (loading) "正在看她现在的样子…" else "她现在的状态",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkTertiary,
                    )
                }
                DsIconButton(Icons.Filled.Refresh, "刷新", onClick = { refreshKey++ })
            }

            if (failed && snapshot == null) {
                // 失败时不要同时出现「心情」和「错误」两张卡，重复且吵
                DsCard {
                    Text(
                        "拿不到她的状态",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ds.colors.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "检查一下网络，或者后端还没更新到这一版。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkTertiary,
                    )
                    Spacer(Modifier.height(DsSpace.md))
                    DsButton("再试一次", onClick = { refreshKey++ }, fillWidth = false)
                }
            } else {
                // 心情：一句话，大字
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Ds.colors.accentSoft)
                        .padding(DsSpace.lg),
                ) {
                    Text(
                        "现在的心情",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ds.colors.accent,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        snapshot?.mood?.ifBlank { "还不清楚" } ?: "正在看…",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Ds.colors.ink,
                    )
                    if (!snapshot?.moodHint.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            snapshot!!.moodHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ds.colors.inkSecondary,
                        )
                    }
                }
            }

            snapshot?.let { s ->
                DsSectionLabel("近况")
                DsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    StatRow("现在", nowLine(life), nowHint(life))
                    DsDivider(Modifier.padding(horizontal = DsSpace.md))
                    StatRow("关系", s.relation, s.relationHint)
                    DsDivider(Modifier.padding(horizontal = DsSpace.md))
                    StatRow("记忆", s.memory, s.memoryHint)
                    DsDivider(Modifier.padding(horizontal = DsSpace.md))
                    StatRow("今天", s.today, s.todayHint)
                }

                // 日记入口：她自己的一本本子，锁着的页只露日期和天气
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showDiary = true }
                        .background(Ds.colors.surface)
                        .padding(DsSpace.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "她的日记",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ds.colors.ink,
                        )
                        Text(
                            "每天凌晨写前一晚，想看得先问她",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ds.colors.inkTertiary,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = Ds.colors.inkTertiary)
                }

                if (s.balance.isNotEmpty()) {
                    DsSectionLabel("账户")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Ds.colors.ink)
                            .padding(DsSpace.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "余额",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ds.colors.canvas.copy(alpha = 0.7f),
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    if (s.balanceCurrency == "USD") "$" else "¥",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Ds.colors.canvas,
                                    modifier = Modifier.padding(bottom = 3.dp),
                                )
                                Text(
                                    "%.2f".format(s.balanceTotal),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = Ds.colors.canvas,
                                )
                            }
                        }
                        Text(
                            s.balance,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ds.colors.canvas.copy(alpha = 0.7f),
                        )
                    }
                }

                Text(
                    "状态是模糊的，只能看个大概；想让她高兴，还是得靠聊天。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ds.colors.inkTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(DsSpace.xl))
        }
    }
    }

    if (showDiary) {
        DiaryScreen(api = api) { showDiary = false }
    }
}

/** 她现在在干嘛：睡了 / 在忙某件事 / 有空。 */
private fun nowLine(life: com.dsmobile.app.net.LifeStatus?): String = when (life?.status) {
    "sleeping" -> "睡了"
    "busy" -> life?.currentTitle?.takeIf { it.isNotBlank() }?.let { "在忙：$it" } ?: "在忙"
    "free" -> life?.currentTitle?.takeIf { it.isNotBlank() }?.let { "有空（$it）" } ?: "有空"
    else -> "还不清楚"
}

private fun nowHint(life: com.dsmobile.app.net.LifeStatus?): String {
    val next = life?.nextTitle.orEmpty()
    val at = life?.nextStart.orEmpty()
    val planned = when {
        next.isNotBlank() && at.isNotBlank() -> "$at 打算$next"
        life?.status == "sleeping" -> "明早八点起"
        else -> ""
    }
    // 今天是大扫除日时先说这个：不然她安静一天，看着像没人。
    // 身体不舒服也先说：health < 80 才提，平时不拿健康刷存在感。
    val body = if ((life?.health ?: 100) < 80) "身子还没好利索" else ""
    return listOf(body, life?.dayType.orEmpty(), planned)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

@Composable
private fun StatRow(label: String, value: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpace.md, vertical = DsSpace.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Ds.colors.inkTertiary,
            modifier = Modifier.width(52.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Ds.colors.ink)
            if (hint.isNotBlank()) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = Ds.colors.inkTertiary)
            }
        }
    }
}
