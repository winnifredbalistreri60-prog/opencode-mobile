package ru.dredd20.opencode.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.serve.ServeCredentials

/**
 * Official OpenCode web UI. Auth is applied only in [onReceivedHttpAuthRequest] (and HTTP
 * auth credentials on the WebView); the password is never injected into JavaScript.
 * Navigation is locked to the loopback origin; everything else goes to the system browser.
 */
class OpenCodeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val webView = WebView(context).also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    var origin: String = ""
        private set
    private var username: String = ServeCredentials.USERNAME
    private var password: String = ""
    var onFileChooser: ((ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean)? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    init {
        configure(webView.settings)
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

    fun bind(origin: String, username: String, password: String) {
        val originChanged = this.origin != origin
        this.origin = origin.trimEnd('/')
        this.username = username
        this.password = password
        // Store HTTP auth for sub-requests without exposing it to JS.
        runCatching {
            val u = Uri.parse(this.origin)
            webView.setHttpAuthUsernamePassword(u.host ?: "127.0.0.1", "", username, password)
        }
        if (originChanged || webView.url.isNullOrBlank()) {
            webView.loadUrl(this.origin + "/")
        }
    }

    fun applyViewport(widthDp: Int) {
        val desktop = ViewportPolicy.useDesktopWebLayout(widthDp)
        // Keep scale at 100% so CSS px matches the view width; 768dp+ gets the official desktop layout.
        webView.settings.loadWithOverviewMode = false
        webView.setInitialScale(100)
        webView.evaluateJavascript(
            "document.querySelector('meta[name=viewport]') || " +
                "document.head.appendChild(Object.assign(document.createElement('meta'),{name:'viewport',content:'width=device-width, initial-scale=1, viewport-fit=cover'}));" +
                "document.querySelector('meta[name=viewport]').setAttribute('content','width=device-width, initial-scale=1, viewport-fit=cover');",
            null,
        )
        // Accessibility hint only — layout itself is driven by CSS min-width: 768px.
        webView.contentDescription = if (desktop) "desktop-layout" else "mobile-layout"
    }

    fun onFileChosen(uris: Array<Uri>?) {
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    fun goBackOrFalse(): Boolean = if (webView.canGoBack()) { webView.goBack(); true } else false

    fun isOurUri(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return isOurHost(host) && (uri.scheme == "http" || uri.scheme == "https")
    }

    private fun isOurHost(host: String): Boolean {
        val ours = Uri.parse(origin).host ?: "127.0.0.1"
        return host == ours || host == "127.0.0.1" || host == "localhost" || host == "[::1]"
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
}
