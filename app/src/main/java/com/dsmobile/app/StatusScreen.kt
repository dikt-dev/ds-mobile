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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dsmobile.app.net.ApiClient
import com.dsmobile.app.net.StatusSnapshot

/**
 * 她的状态：点 AI 头像或顶栏头像打开。
 *
 * 全部是模糊的一句话——服务端就不返回数值，这里也没有「进度条」可画。
 * 余额是唯一给具体数字的，因为那是要花掉的钱。
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
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color(0xFF6C5CE7))
                    }
                    Text(
                        "她的状态",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF26232E),
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
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (loading && snapshot == null) {
                        Text(
                            "正在看她现在的样子…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF8F89A0),
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                    if (failed && snapshot == null) {
                        StatusCard {
                            Text(
                                "拿不到她的状态",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF26232E),
                            )
                            Text(
                                "检查一下网络，或者后端是不是还没更新到这一版。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8F89A0),
                            )
                            Button(onClick = { refreshKey++ }, shape = RoundedCornerShape(14.dp)) {
                                Text("再试一次")
                            }
                        }
                    }
                    snapshot?.let { s ->
                        MoodCard(s)
                        StatusCard {
                            StatRow("关系", s.relation, s.relationHint)
                            StatRow("记忆", s.memory, s.memoryHint)
                            StatRow("今天", s.today, s.todayHint)
                        }
                        if (s.balance.isNotEmpty()) {
                            StatusCard {
                                Text(
                                    "账户余额",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF8F89A0),
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        if (s.balanceCurrency == "USD") "$" else "¥",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF26232E),
                                        modifier = Modifier.padding(bottom = 3.dp),
                                    )
                                    Text(
                                        "%.2f".format(s.balanceTotal),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF26232E),
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        s.balance,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF8F89A0),
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            "状态是模糊的，只能看个大概；想让她高兴，还是得靠聊天。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA9A3B8),
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun MoodCard(s: StatusSnapshot) {
    StatusCard(tint = Color(0xFFF3EEFF)) {
        Text(
            "现在的心情",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8F89A0),
        )
        Text(
            s.mood,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF26232E),
        )
        if (s.moodHint.isNotEmpty()) {
            Text(
                s.moodHint,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6F6A7D),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8F89A0),
            modifier = Modifier.size(width = 44.dp, height = 20.dp),
        )
        Column {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF26232E),
            )
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA9A3B8),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    tint: Color = Color.White,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = tint,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.background(tint).padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}