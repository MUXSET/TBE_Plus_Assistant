package com.muxset.tbeplus.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.muxset.tbeplus.data.AuthResult
import com.muxset.tbeplus.data.ChannelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val LOGIN_URL = "https://ejia.tbea.com/"
private const val TBEANEWS_URL = "https://tbeanews.tbea.com/"
private const val FALLBACK_CHANNEL_ID = "XT-2bb8a866-d2a3-47da-bbad-8c63db21e9b6"
private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val EJIA_COOKIE_NAMES = setOf(
    "JSESSIONID", "toweibologin", "gl", "ERPPRD", "cd", "cn", "cu",
    "at", "__loginType", "uuid", "webLappToken", "sync_networkid",
    "sync_userid", "redirectIndexUrl",
)

private fun parseCookieString(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val map = linkedMapOf<String, String>()
    for (part in raw.split(";")) {
        val idx = part.indexOf('=')
        if (idx > 0) {
            val k = part.substring(0, idx).trim()
            val v = part.substring(idx + 1).trim()
            if (k.isNotEmpty()) map[k] = v
        }
    }
    return map
}

private val EJIA_COOKIE_URLS = listOf(
    "https://ejia.tbea.com/",
    "https://ejia.tbea.com/space/",
    "https://ejia.tbea.com/im/",
    "https://ejia.tbea.com/pubacc_v2/",
    "https://ejia.tbea.com/pubacc-front/",
)

private fun collectEjiaCookies(cm: CookieManager): Map<String, String> {
    val merged = linkedMapOf<String, String>()
    for (url in EJIA_COOKIE_URLS) {
        merged.putAll(parseCookieString(cm.getCookie(url)))
    }
    return merged
}

private fun collectTbeaNewsCookies(cm: CookieManager): Map<String, String> {
    val merged = linkedMapOf<String, String>()
    merged.putAll(parseCookieString(cm.getCookie(TBEANEWS_URL)))
    merged.putAll(parseCookieString(cm.getCookie("${TBEANEWS_URL}api/")))
    return merged
}

private fun isEjiaAuthenticated(cookies: Map<String, String>): Boolean =
    cookies["cu"].orEmpty().isNotBlank() && (
        cookies["at"].orEmpty().isNotBlank() ||
            cookies["gl"].orEmpty().isNotBlank() ||
            cookies["webLappToken"].orEmpty().isNotBlank()
        )

private suspend fun WebView.evalJs(script: String): String = suspendCancellableCoroutine { cont ->
    Handler(Looper.getMainLooper()).post {
        if (!cont.isActive) return@post
        evaluateJavascript(script) { value ->
            if (cont.isActive) {
                cont.resume(value ?: "null")
            }
        }
    }
}

private suspend fun WebView.loadUrlOnMain(url: String) = Handler(Looper.getMainLooper()).post {
    loadUrl(url)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthWebViewSheet(
    username: String,
    password: String,
    channels: List<ChannelConfig>,
    onSuccess: (AuthResult) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var stageText by remember { mutableStateOf("正在初始化内置浏览器会话...") }
    var progress by remember { mutableFloatStateOf(0.08f) }
    var expanded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    val completed = remember { AtomicBoolean(false) }

    val webView = remember {
        @SuppressLint("SetJavaScriptEnabled")
        WebView(context).apply {
            setBackgroundColor(AndroidColor.WHITE)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                userAgentString = DESKTOP_CHROME_UA
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame != true || completed.get()) return
                    val desc = error?.description?.toString().orEmpty()
                    if (desc.contains("ERR_ABORTED", ignoreCase = true)) return
                    val hint = when {
                        desc.contains("ERR_NAME_NOT_RESOLVED", ignoreCase = true) ->
                            "无法解析门户域名 (ERR_NAME_NOT_RESOLVED)，请检查设备网络或 DNS 设置"
                        desc.contains("ERR_INTERNET_DISCONNECTED", ignoreCase = true) ->
                            "当前设备未联网 (ERR_INTERNET_DISCONNECTED)"
                        desc.contains("ERR_CONNECTION_TIMED_OUT", ignoreCase = true) ||
                            desc.contains("ERR_TIMED_OUT", ignoreCase = true) ->
                            "连接门户超时 ($desc)，请检查网络或代理设置"
                        desc.isNotBlank() -> "网页加载失败 ($desc)"
                        else -> "网页加载失败，请检查网络连接"
                    }
                    Handler(Looper.getMainLooper()).post {
                        loadError = hint
                        stageText = hint
                        expanded = true
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.cancel()
                    if (completed.compareAndSet(false, true)) {
                        Handler(Looper.getMainLooper()).post {
                            onError("SSL 证书校验失败，请检查当前网络环境")
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    LaunchedEffect(username, password, retryKey) {
        loadError = null
        progress = 0.08f
        stageText = "正在初始化内置浏览器会话..."

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        suspendCancellableCoroutine { cont ->
            cm.removeAllCookies { _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }
        cm.flush()

        stageText = "正在加载 E+ 门户登录页..."
        progress = 0.18f
        webView.loadUrlOnMain(LOGIN_URL)

        val userLit = JSONObject.quote(username)
        val passLit = JSONObject.quote(password)

        // 1. 等待登录表单并自动填写提交
        var submitted = false
        for (i in 0 until 45) {
            if (completed.get() || loadError != null) return@LaunchedEffect
            val state = webView.evalJs(
                """
                (function() {
                  var unBtn = document.querySelector('.user-name');
                  var email = document.querySelector('#email');
                  var pwd = document.querySelector('#password');
                  var btn = document.querySelector('#log-btn');
                  if (unBtn && (!email || email.offsetParent === null)) {
                    unBtn.click();
                    return 'SWITCHED';
                  }
                  if (email && pwd && btn) {
                    if (unBtn) unBtn.click();
                    var setterDesc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                    var nativeSet = setterDesc && setterDesc.set;
                    if (nativeSet) {
                      nativeSet.call(email, $userLit);
                      nativeSet.call(pwd, $passLit);
                    } else {
                      email.value = $userLit;
                      pwd.value = $passLit;
                    }
                    email.dispatchEvent(new Event('input', {bubbles: true}));
                    email.dispatchEvent(new Event('change', {bubbles: true}));
                    pwd.dispatchEvent(new Event('input', {bubbles: true}));
                    pwd.dispatchEvent(new Event('change', {bubbles: true}));
                    btn.click();
                    return 'SUBMITTED';
                  }
                  return 'WAIT';
                })();
                """.trimIndent(),
            )
            if (state.contains("SUBMITTED")) {
                submitted = true
                break
            }
            delay(450)
        }

        if (!submitted) {
            if (loadError != null) return@LaunchedEffect
            if (completed.compareAndSet(false, true)) {
                onError("登录页加载超时，请展开网页检查网络或门户状态")
            }
            return@LaunchedEffect
        }

        stageText = "已提交账号密码，等待会话建立..."
        progress = 0.38f
        delay(800)

        // 2. 等待登录跳转并建立真实已认证 ejia 会话 Cookie
        var sessionReady = false
        for (i in 0 until 110) {
            if (completed.get() || loadError != null) return@LaunchedEffect
            val ejiaMap = collectEjiaCookies(cm)
            if (isEjiaAuthenticated(ejiaMap)) {
                delay(1200)
                cm.flush()
                sessionReady = true
                break
            }
            if (i == 14 && !expanded) {
                expanded = true
                stageText = "等待登录完成，如遇验证码或提示请直接在下方网页操作..."
            }
            delay(450)
        }
        if (!sessionReady) {
            if (loadError != null) return@LaunchedEffect
            if (completed.compareAndSet(false, true)) {
                onError("登录未完成，请确认工号密码是否正确或检查网页提示")
            }
            return@LaunchedEffect
        }

        // 3. 遍历频道入口寻找可用文章并触发 tbeanews SSO 跳转
        val candidateIds = buildList {
            channels.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }.forEach { add(it) }
            if (!contains(FALLBACK_CHANNEL_ID)) add(FALLBACK_CHANNEL_ID)
        }

        stageText = "正在打开公众号列表触发认证跳转..."
        progress = 0.58f

        var clickedArticle = false
        for (chId in candidateIds) {
            if (completed.get() || loadError != null) return@LaunchedEffect
            val rawSrc = """[{"title":"","ids":["$chId"]}]"""
            val encoded = URLEncoder.encode(rawSrc, "UTF-8")
            webView.loadUrlOnMain("https://ejia.tbea.com/pubacc-front/article/list?contentSource=$encoded")

            for (attempt in 0 until 24) {
                if (completed.get() || loadError != null) return@LaunchedEffect
                val res = webView.evalJs(
                    """
                    (function() {
                      var item = document.querySelector('li.article-item');
                      if (!item) return 'WAIT';
                      window.open = function(u) { if (u) window.location.href = u; return window; };
                      var a = item.querySelector('a');
                      if (a) a.removeAttribute('target');
                      item.click();
                      return 'CLICKED';
                    })();
                    """.trimIndent(),
                )
                if (res.contains("CLICKED")) {
                    clickedArticle = true
                    break
                }
                delay(500)
            }
            if (clickedArticle) break
        }

        if (!clickedArticle) {
            if (loadError != null) return@LaunchedEffect
            if (completed.compareAndSet(false, true)) {
                onError("未能定位频道文章入口，请检查工号密码或网络连接")
            }
            return@LaunchedEffect
        }

        stageText = "正在提取新闻 Token 与会话凭据..."
        progress = 0.80f

        // 4. 轮询 CookieManager 获取 tbea_art_token 与 ejia Cookies
        for (i in 0 until 50) {
            if (completed.get() || loadError != null) return@LaunchedEffect
            val newsCookies = collectTbeaNewsCookies(cm)
            val ejiaAll = collectEjiaCookies(cm)
            val tokenVal = newsCookies["tbea_art_token"].orEmpty()

            if (tokenVal.isNotBlank() && ejiaAll.isNotEmpty()) {
                delay(450)
                cm.flush()
                val finalEjia = collectEjiaCookies(cm)
                    .filterKeys { it in EJIA_COOKIE_NAMES || it.isNotBlank() }
                val uid = finalEjia["cu"].orEmpty()
                progress = 1.0f
                stageText = "凭据捕获完成！"
                if (completed.compareAndSet(false, true)) {
                    onSuccess(
                        AuthResult(
                            token = tokenVal,
                            ejiaCookies = finalEjia,
                            ejiaUserId = uid,
                        ),
                    )
                }
                return@LaunchedEffect
            }
            delay(400)
        }

        if (loadError != null) return@LaunchedEffect
        if (completed.compareAndSet(false, true)) {
            onError("未能在 Cookie 中捕获完整凭据，请展开网页确认登录状态")
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (completed.compareAndSet(false, true)) {
                onDismiss()
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .animateContentSize(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "验证账号凭据 ($username)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = stageText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loadError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(
                    onClick = {
                        if (completed.compareAndSet(false, true)) {
                            onDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) "如遇验证码可直接点击下方网页操作" else "内置 WebView 静默认证中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loadError != null) {
                        OutlinedButton(
                            onClick = {
                                loadError = null
                                progress = 0.08f
                                retryKey++
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "重试")
                        }
                    }
                    FilledTonalButton(
                        onClick = { expanded = !expanded },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                        )
                        Text(text = if (expanded) "收起网页" else "展开网页")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (expanded) 420.dp else 1.dp)
                    .alpha(if (expanded) 1f else 0f)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxWidth().height(if (expanded) 420.dp else 1.dp),
                )
            }
        }
    }
}
