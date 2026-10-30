package com.dsmobile.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.net.StatusSnapshot

/**
 * 她的状态：点顶栏头像或聊天里她的头像打开。
 *
 * 状态本身是模糊的一句话（服务端就不给数值），所以这一页不做进度条、不做百分比，
 * 只把「她现在什么样子」用卡片和图标传达出来；唯一给数字的是余额。
 */
@Composable
fun StatusScreen(api: ApiClient, onClose: () -> Unit) {
    var snapshot by remember { mutableStateOf<StatusSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        failed = false
        val result = runCatching { api.status() }.getOrNull()
        snapshot = result
        failed = result == null
        loading = false
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F5FB)) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color(0xFF6C5CE7))
                    }
                    Text(
                        "她的状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF5B5470),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { refreshKey++ }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = Color(0xFF8D84A8))
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    HeroCard(snapshot)
                    if (loading && snapshot == null) {
                        Text(
                            "正在看她现在的样子…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF8F89A0),
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    if (failed && snapshot == null) {
                        PanelCard {
                            Text(
                                "拿不到她的状态",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF26232E),
                            )
                            Text(
                                "检查一下网络，或者后端还没更新到这一版。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8F89A0),
                            )
                            Button(onClick = { refreshKey++ }, shape = RoundedCornerShape(14.dp)) {
                                Text("再试一次")
                            }
                        }
                    }
                    snapshot?.let { s ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile(
                                icon = Icons.Filled.Favorite,
                                label = "关系",
                                value = s.relation,
                                hint = s.relationHint,
                                tint = Color(0xFFFFE8EF),
                                iconTint = Color(0xFFE0608A),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                icon = Icons.Filled.Star,
                                label = "记忆",
                                value = s.memory,
                                hint = s.memoryHint,
                                tint = Color(0xFFFFF4DE),
                                iconTint = Color(0xFFD79A2B),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        StatTile(
                            icon = Icons.Filled.Chat,
                            label = "今天",
                            value = s.today,
                            hint = s.todayHint,
                            tint = Color(0xFFE9F3FF),
                            iconTint = Color(0xFF3F7FD1),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (s.balance.isNotEmpty()) {
                            BalanceCard(s)
                        }
                        Text(
                            "状态是模糊的，只能看个大概；想让她高兴，还是得靠聊天。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA9A3B8),
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 顶部：头像 + 现在的心情，一眼看到她是什么状态。 */
@Composable
private fun HeroCard(s: StatusSnapshot?) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(26.dp),
        color = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(Color(0xFFF2ECFF), Color(0xFFFFF1F7))),
                    RoundedCornerShape(26.dp),
                )
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.avatar),
                        contentDescription = "她的头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "现在的心情",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF8F89A0),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        s?.mood?.ifBlank { "还不清楚" } ?: "加载中…",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2C2838),
                    )
                    if (!s?.moodHint.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            s!!.moodHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6F6A7D),
                        )
                    }
                }
            }
        }
    }
}

/** 图标 + 标签 + 一句话，统一的小卡片。 */
@Composable
private fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    hint: String,
    tint: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF9A94A6))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2C2838),
            )
            if (hint.isNotBlank()) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9A3B8))
            }
        }
    }
}

/** 余额是唯一给具体数字的：那是要花掉的钱。 */
@Composable
private fun BalanceCard(s: StatusSnapshot) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF2C2838),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x22FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = Color(0xFFD9D2FF),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("账户余额", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB8B2C8))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (s.balanceCurrency == "USD") "$" else "¥",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Text(
                        "%.2f".format(s.balanceTotal),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            Text(s.balance, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8B2C8))
        }
    }
}

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}