package ru.dredd20.opencode.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.serve.ServeCredentials
import ru.dredd20.opencode.serve.SessionAuth
import java.util.concurrent.Executors

/**
 * Official OpenCode web UI.
 *
 * Login follows the desktop/serve pairing flow: the app (not JS) calls POST /api/pair
 * with Basic auth, then loads /auth/connect/{code}. The server sets an HttpOnly
 * session cookie and redirects to /. The password is never injected into JavaScript
 * and is not placed in the page URL.
 *
 * [onReceivedHttpAuthRequest] remains as a fallback for top-level navigations.
 * A URL.parse / URL.canParse polyfill is injected at document start for WebView < 126.
 */
class OpenCodeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val webView = WebView(context).also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    private val errorMessageView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(24, 12, 24, 12)
    }
    private val errorPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(24, 24, 24, 24)
        setBackgroundColor(Color.rgb(40, 43, 48))
        addView(errorMessageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(Button(context).apply {
            text = tr("Повторить", "Retry")
            setOnClickListener { retryConnection() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    var origin: String = ""
        private set
    private var username: String = ServeCredentials.USERNAME
    private var password: String = ""
    var onFileChooser: ((ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean)? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastError: Boolean = false
    private var pendingClearHistory: Boolean = false
    private var polyfillInstalledFor: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var lastErrorDescription: String? = null
        private set

    init {
        addView(errorPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configure(webView.settings)
        if (ru.dredd20.opencode.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                if (isOurHost(host) && password.isNotEmpty()) handler.proceed(username, password)
                else handler.cancel()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return if (isOurUri(uri)) false else {
                    openExternal(uri)
                    true
                }
            }

            @Deprecated("deprecated")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val uri = Uri.parse(url)
                return if (isOurUri(uri)) false else {
                    openExternal(uri)
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                lastError = false
                lastErrorDescription = null
                errorPanel.visibility = GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showConnectionError(networkErrorMessage(error))
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    val reason = response.reasonPhrase?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    showConnectionError(tr(
                        "Сервер ответил HTTP ${response.statusCode}$reason. Проверьте адрес и учётные данные.",
                        "Server returned HTTP ${response.statusCode}$reason. Check the server URL and credentials.",
                    ))
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                val host = Uri.parse(error.url).host.orEmpty()
                showConnectionError(tr(
                    "Не удалось проверить TLS-сертификат ${host.ifBlank { "сервера" }}. Проверьте сертификат и системное время.",
                    "Could not verify the TLS certificate for ${host.ifBlank { "the server" }}. Check the certificate and device clock.",
                ))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!lastError) {
                    lastErrorDescription = null
                    errorPanel.visibility = GONE
                }
                if (pendingClearHistory) {
                    view.clearHistory()
                    pendingClearHistory = false
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val handled = onFileChooser?.invoke(filePathCallback, fileChooserParams) == true
                if (!handled) {
                    fileCallback = null
                    filePathCallback?.onReceiveValue(null)
                }
                return handled
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
            if (!isOurUri(Uri.parse(url))) {
                openExternal(Uri.parse(url)); return@setDownloadListener
            }
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime)
                    .addRequestHeader("User-Agent", userAgent)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    .setTitle(name)
                if (password.isNotEmpty()) req.addRequestHeader("Authorization", ServeCredentials.basicHeaderValue(password))
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(context, tr("Загрузка: $name", "Downloading: $name"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(s: WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            s.isAlgorithmicDarkeningAllowed = true
        }
    }

    private fun showConnectionError(message: String) {
        lastError = true
        lastErrorDescription = message
        errorMessageView.text = message
        errorPanel.visibility = VISIBLE
    }

    private fun retryConnection() {
        lastError = false
        lastErrorDescription = null
        errorMessageView.text = tr("Повторное подключение…", "Reconnecting…")
        errorPanel.visibility = VISIBLE
        bind(origin, username, password, forceReload = true)
    }

    private fun networkErrorMessage(error: WebResourceError): String {
        val host = Uri.parse(origin).host.orEmpty()
        val target = host.ifBlank { tr("сервер", "server") }
        return when (error.errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> tr(
                "Не удалось найти адрес $target. Проверьте URL, DNS и VPN.",
                "Could not resolve $target. Check the URL, DNS, and VPN.",
            )
            WebViewClient.ERROR_CONNECT -> tr(
                "Не удалось подключиться к $target. Проверьте, что сервер запущен и доступен нужный сетевой маршрут/VPN.",
                "Could not connect to $target. Check that the server is running and the required network route/VPN is available.",
            )
            WebViewClient.ERROR_TIMEOUT -> tr(
                "Сервер $target не ответил вовремя. Проверьте соединение и повторите попытку.",
                "$target did not respond in time. Check the connection and retry.",
            )
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> tr(
                "Не удалось установить защищённое TLS-соединение с $target. Проверьте сертификат и системное время.",
                "Could not establish a secure TLS connection to $target. Check the certificate and device clock.",
            )
            else -> {
                val detail = error.description?.toString()?.takeIf { it.isNotBlank() }
                tr(
                    "Не удалось загрузить OpenCode${detail?.let { ": $it" }.orEmpty()} (код ${error.errorCode}). Проверьте сеть и адрес сервера.",
                    "Could not load OpenCode${detail?.let { ": $it" }.orEmpty()} (error ${error.errorCode}). Check the network and server URL.",
                )
            }
        }
    }

    fun canInjectDocumentStart(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun webViewMajor(): Int? {
        val pkg = if (android.os.Build.VERSION.SDK_INT >= 26) {
            WebView.getCurrentWebViewPackage()
        } else {
            runCatching { context.packageManager.getPackageInfo("com.google.android.webview", 0) }.getOrNull()
        }
        return WebViewSupport.majorVersion(pkg?.versionName)
    }

    fun installPolyfill(targetOrigin: String) {
        if (polyfillInstalledFor == targetOrigin) return
        if (!canInjectDocumentStart()) return
        val origins = linkedSetOf(targetOrigin, "http://127.0.0.1", "http://localhost")
        // Allow any port on loopback so a pairing redirect is also covered.
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                WebViewSupport.URL_PARSE_POLYFILL,
                origins,
            )
            polyfillInstalledFor = targetOrigin
        }
    }

    fun bind(origin: String, username: String, password: String, forceReload: Boolean = false) {
        val newOrigin = origin.trimEnd('/')
        val originChanged = this.origin != newOrigin
        val passwordChanged = this.password != password || this.username != username
        this.origin = newOrigin
        this.username = username
        this.password = password
        runCatching {
            val u = Uri.parse(this.origin)
            webView.setHttpAuthUsernamePassword(u.host ?: "127.0.0.1", "", username, password)
        }
        if (newOrigin.isNotBlank()) installPolyfill(newOrigin)
        val alreadyOnOrigin = !webView.url.isNullOrBlank() && !lastError && !forceReload &&
            !originChanged && !passwordChanged && hasSessionCookie()
        if (alreadyOnOrigin) return
        if (newOrigin.isBlank()) return
        lastError = false
        if (originChanged) {
            // Drop history that points at a previous port/origin (BUG-18).
            pendingClearHistory = true
            runCatching { webView.clearHistory() }
        }
        if (password.isBlank()) {
            webView.loadUrl("$newOrigin/")
            return
        }
        io.execute {
            val code = SessionAuth.issuePairingCode(newOrigin, username, password)
            main.post {
                if (this.origin != newOrigin) return@post
                if (code != null) webView.loadUrl(SessionAuth.connectUrl(newOrigin, code))
                else webView.loadUrl("$newOrigin/")
            }
        }
    }

    /** Navigate the UI to a path on the bound origin (e.g. Settings → Extensions for MCP sign-in). */
    fun openPath(path: String) {
        if (origin.isBlank()) return
        require(path.startsWith("/"))
        main.post { webView.loadUrl(origin + path) }
    }

    fun hasSessionCookie(): Boolean {
        if (origin.isBlank()) return false
        val host = Uri.parse(origin).let { listOfNotNull(it.host, it.port.takeIf { p -> p > 0 }?.let { p -> "${it.host}:$p" }).lastOrNull() }
        val cookies = CookieManager.getInstance().getCookie(origin) ?: return false
        val header = Uri.parse(origin).let { h ->
            val hostName = h.host ?: return cookies.contains("opencode_session")
            val port = h.port
            if (port > 0) "$hostName:$port" else hostName
        }
        return SessionAuth.cookieLooksLikeSession(cookies, header)
    }

    fun applyViewport(widthDp: Int) {
        val desktop = ViewportPolicy.useDesktopWebLayout(widthDp)
        webView.settings.loadWithOverviewMode = false
        webView.setInitialScale(100)
        webView.evaluateJavascript(
            "document.querySelector('meta[name=viewport]') || " +
                "document.head.appendChild(Object.assign(document.createElement('meta'),{name:'viewport',content:'width=device-width, initial-scale=1, viewport-fit=cover'}));" +
                "document.querySelector('meta[name=viewport]').setAttribute('content','width=device-width, initial-scale=1, viewport-fit=cover');",
            null,
        )
        webView.contentDescription = if (desktop) "desktop-layout" else "mobile-layout"
    }

    fun onFileChosen(uris: Array<Uri>?) {
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(uris)
    }

    fun goBackOrFalse(): Boolean {
        if (!webView.canGoBack()) return false
        val list = webView.copyBackForwardList()
        val cur = origin.ifBlank { list.currentItem?.url }
        val idx = list.currentIndex
        if (idx <= 0) return false
        val prev = list.getItemAtIndex(idx - 1)?.url
        if (!WebHistoryPolicy.shouldGoBack(cur, prev)) {
            // Leave the dead previous origin out of the stack.
            runCatching { webView.clearHistory() }
            return false
        }
        webView.goBack()
        return true
    }

    fun isOurUri(uri: Uri): Boolean {
        if (uri.scheme != "http" && uri.scheme != "https") return false
        return WebHistoryPolicy.sameOrigin(origin, uri.toString())
    }

    private fun isOurHost(host: String): Boolean {
        val ours = Uri.parse(origin).host ?: "127.0.0.1"
        return host.equals(ours, ignoreCase = true)
    }

    private fun openExternal(uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, tr("Нечем открыть ссылку", "No app can open this link"), Toast.LENGTH_SHORT).show()
        }
    }

    fun detachFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    companion object {
        fun packageWebViewVersion(context: Context): String? {
            val pkg = if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
            return pkg?.versionName
        }
    }
}
