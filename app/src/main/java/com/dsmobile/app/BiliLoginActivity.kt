package com.dsmobile.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsmobile.app.data.SettingsStore
import com.dsmobile.app.ui.components.DsTextButton
import com.dsmobile.app.ui.components.DsTopBar
import com.dsmobile.app.ui.theme.Ds
import com.dsmobile.app.ui.theme.DsSpace
import com.dsmobile.app.ui.theme.DsTheme
import com.dsmobile.app.ui.theme.rememberDarkTheme

/**
 * 在 App 内用 WebView 完成 B 站登录，登录后从 CookieManager 取 SESSDATA。
 * 不保存账号密码，只保存 SESSDATA。
 */
class BiliLoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        setContent {
            var status by remember { mutableStateOf("登录成功后点右上角「完成」") }
            var webView by remember { mutableStateOf<WebView?>(null) }

            fun finishLogin() {
                CookieManager.getInstance().flush()
                val cookie = CookieManager.getInstance()
                    .getCookie("https://www.bilibili.com")
                    .orEmpty()
                val sess = cookie.split(';')
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("SESSDATA=", ignoreCase = true) }
                    ?.substringAfter('=')
                    .orEmpty()
                if (sess.isBlank()) {
                    status = "还没拿到 SESSDATA，请先在网页里完成登录"
                    return
                }
                store.biliSessdata = sess
                store.biliCookie = cookie
                setResult(RESULT_OK)
                finish()
            }

            BackHandler {
                if (webView?.canGoBack() == true) webView?.goBack() else finish()
            }

            DsTheme(darkTheme = rememberDarkTheme(store.themeMode)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Ds.colors.canvas)
                        .statusBarsPadding(),
                ) {
                    DsTopBar(
                        title = "登录 B 站",
                        onBack = { finish() },
                        backIcon = Icons.Filled.Close,
                        actions = {
                            DsTextButton("完成", onClick = { finishLogin() })
                        },
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkTertiary,
                        modifier = Modifier.padding(horizontal = DsSpace.md, vertical = DsSpace.xs),
                    )
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(DsSpace.md)
                            .clip(RoundedCornerShape(16.dp)),
                        factory = { context ->
                            WebView(context).apply {
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        if (url?.contains("bilibili.com") == true) {
                                            status = "已打开 B 站，完成登录后点右上角「完成」"
                                        }
                                    }
                                }
                                loadUrl("https://passport.bilibili.com/login")
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
}
