package com.dsmobile.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsmobile.app.data.SettingsStore

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
                setResult(RESULT_OK)
                finish()
            }

            BackHandler {
                if (webView?.canGoBack() == true) webView?.goBack() else finish()
            }

            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("登录 B 站", style = MaterialTheme.typography.titleLarge)
                    Row {
                        OutlinedButton(onClick = { finish() }) { Text("取消") }
                        Button(onClick = { finishLogin() }) { Text("完成") }
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().fillMaxSize(),
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
                                        status = "已打开 B站，完成登录后点「完成」"
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

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
}
