package ru.dredd20.opencode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dredd20.opencode.backend.Termux
import ru.dredd20.opencode.backend.TermuxServe
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.phone.PhonePending
import ru.dredd20.opencode.prefs.AppPrefs
import ru.dredd20.opencode.prefs.RuntimeMode
import ru.dredd20.opencode.runtime.EmbeddedRuntime
import ru.dredd20.opencode.runtime.RuntimeService
import ru.dredd20.opencode.serve.ServeCredentials
import ru.dredd20.opencode.serve.ServeState
import ru.dredd20.opencode.ui.AboutScreen
import ru.dredd20.opencode.ui.OpenCodeTheme
import ru.dredd20.opencode.ui.OpenCodeWebView
import ru.dredd20.opencode.ui.SettingsScreen
import ru.dredd20.opencode.ui.SetupScreen
import ru.dredd20.opencode.ui.UnsupportedAbiScreen
import ru.dredd20.opencode.ui.ViewportPolicy
import java.io.File

class MainActivity : ComponentActivity() {
    enum class Dest { Setup, Session, Settings, About, Unsupported }

    /** Retained across configuration changes (Activity is not recreated). */
    private var webHost: OpenCodeWebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = result.data?.let { intent ->
            val clip = intent.clipData
            if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri }
            else intent.data?.let { arrayOf(it) }
        }
        webHost?.onFileChosen(uris)
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val dest = PhonePending.pictureDest
        if (ok && dest != null) PhonePending.pictureRequest?.complete(dest)
        else PhonePending.pictureRequest?.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (webHost == null) {
            webHost = OpenCodeWebView(this).also { host ->
                host.onFileChooser = { cb, params ->
                    fileCallback = cb
                    val intent = params?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    fileChooser.launch(intent)
                    true
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webHost?.goBackOrFalse() == true) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        handleSpecialIntent(intent)
        setContent { App() }
        maybeAskNotifications()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpecialIntent(intent)
    }

    private fun handleSpecialIntent(intent: Intent?) {
        val perm = intent?.getStringExtra("request_permission")
        if (perm != null) permissionLauncher.launch(arrayOf(perm))
        val picture = intent?.getStringExtra("take_picture")
        if (picture != null) {
            val dest = File(picture)
            dest.parentFile?.mkdirs()
            val uri = FileProvider.getUriForFile(this, "$packageName.files", dest)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
            PhonePending.pictureDest = dest
            cameraLauncher.launch(uri)
        }
        val confirmId = intent?.getStringExtra("confirm_id")
        if (confirmId != null) {
            // Dialog is shown in composition via PhonePending.asks
        }
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
    @Composable
    private fun App() {
        OpenCodeTheme {
            val windowSize = calculateWindowSizeClass(this)
            val config = LocalConfiguration.current
            val widthDp = config.screenWidthDp
            val expanded = windowSize.widthSizeClass == WindowWidthSizeClass.Expanded ||
                ViewportPolicy.widthClass(widthDp) == ViewportPolicy.WidthClass.Expanded
            val runtimeState by RuntimeService.state.collectAsState()
            var dest by remember { mutableStateOf(initialDest()) }
            var tick by remember { mutableIntStateOf(0) }
            var confirmPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(800)
                    tick++
                    PhonePending.asks.entries.firstOrNull()?.let { confirmPrompt = it.key to it.value.prompt }
                }
            }
            val mode = AppPrefs.mode(this)
            val installed = EmbeddedRuntime.installed(this)
            val serve = when (mode) {
                RuntimeMode.EMBEDDED -> RuntimeService.currentServe(this)
                RuntimeMode.TERMUX -> ru.dredd20.opencode.serve.ServeState.read(TermuxServe.stateFile(this))
                RuntimeMode.REMOTE -> remoteState()
            }
            if (dest == Dest.Setup && installed && mode == RuntimeMode.EMBEDDED && runtimeState.phase != RuntimeService.Phase.INSTALLING) {
                dest = Dest.Session
            }
            Scaffold(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                topBar = {
                    TopAppBar(
                        title = { Text(titleFor(dest)) },
                        navigationIcon = {
                            if (dest != Dest.Session && dest != Dest.Setup && dest != Dest.Unsupported) {
                                IconButton(onClick = { dest = if (installed || mode != RuntimeMode.EMBEDDED) Dest.Session else Dest.Setup }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        actions = {
                            if (dest == Dest.Session) {
                                IconButton(onClick = { dest = Dest.Settings }) {
                                    Icon(Icons.Default.Settings, contentDescription = tr("Настройки", "Settings"))
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                val mod = Modifier.fillMaxSize().padding(padding)
                when (dest) {
                    Dest.Unsupported -> UnsupportedAbiScreen(onRemote = { AppPrefs.setMode(this, RuntimeMode.REMOTE); dest = Dest.Settings }, modifier = mod)
                    Dest.Setup -> {
                        val p = EmbeddedRuntime.readProgress(this)
                        SetupScreen(
                            progressPct = p?.optInt("pct") ?: 0,
                            message = p?.optString("msg").orEmpty().ifBlank { runtimeState.message },
                            error = if (runtimeState.phase == RuntimeService.Phase.ERROR) runtimeState.message else p?.takeIf { it.optString("state") == "error" }?.optString("msg"),
                            installing = RuntimeService.installing || runtimeState.phase == RuntimeService.Phase.INSTALLING,
                            onInstall = { RuntimeService.send(this, RuntimeService.ACTION_INSTALL) },
                            onRetry = { RuntimeService.send(this, RuntimeService.ACTION_INSTALL) },
                            onRemote = { AppPrefs.setMode(this, RuntimeMode.REMOTE); dest = Dest.Settings },
                            modifier = mod,
                        )
                    }
                    Dest.Session -> SessionPane(serve, widthDp, modifier = mod)
                    Dest.Settings -> SettingsPane(mode, runtimeState, expanded, serve, onStart = {
                        when (AppPrefs.mode(this)) {
                            RuntimeMode.EMBEDDED -> RuntimeService.send(this, RuntimeService.ACTION_START)
                            RuntimeMode.TERMUX -> lifecycleScope.launch { TermuxServe.ensureRunning(this@MainActivity) }
                            RuntimeMode.REMOTE -> Unit
                        }
                    }, onStop = {
                        when (AppPrefs.mode(this)) {
                            RuntimeMode.EMBEDDED -> RuntimeService.send(this, RuntimeService.ACTION_STOP)
                            RuntimeMode.TERMUX -> lifecycleScope.launch { TermuxServe.stop(this@MainActivity) }
                            RuntimeMode.REMOTE -> Unit
                        }
                    }, onUpdate = { RuntimeService.send(this, RuntimeService.ACTION_UPDATE) }, onAbout = { dest = Dest.About }, modifier = mod)
                    Dest.About -> AboutScreen(aboutText(), modifier = mod)
                }
                confirmPrompt?.let { (id, prompt) ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { PhonePending.complete(id, false); confirmPrompt = null },
                        title = { Text(tr("Подтверждение", "Confirm")) },
                        text = { Text(prompt) },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { PhonePending.complete(id, true); confirmPrompt = null }) { Text(tr("Да", "Yes")) } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { PhonePending.complete(id, false); confirmPrompt = null }) { Text(tr("Нет", "No")) } },
                    )
                }
            }
        }
    }

    @Composable
    private fun SessionPane(serve: ServeState?, widthDp: Int, modifier: Modifier) {
        val mode = AppPrefs.mode(this)
        val host = webHost ?: return
        if (mode == RuntimeMode.EMBEDDED && !RuntimeService.running && serve == null) {
            Column(modifier.padding(24.dp)) {
                Text(tr("Сервер не запущен.", "The server is not running."), style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Button(onClick = { RuntimeService.send(this@MainActivity, RuntimeService.ACTION_START) }) {
                    Text(tr("Запустить", "Start"))
                }
            }
            return
        }
        if (serve == null && mode != RuntimeMode.REMOTE) {
            Column(modifier.padding(24.dp)) {
                Text(tr("Ожидание сервера…", "Waiting for the server…"))
            }
            return
        }
        val origin = if (mode == RuntimeMode.REMOTE) AppPrefs.remoteUrl(this).trimEnd('/') else serve!!.origin
        val user = if (mode == RuntimeMode.REMOTE) AppPrefs.remoteUser(this) else ServeCredentials.USERNAME
        val pass = if (mode == RuntimeMode.REMOTE) AppPrefs.remotePassword(this) else serve!!.password
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = {
                host.detachFromParent()
                host.applyViewport(widthDp)
                if (origin.isNotBlank()) host.bind(origin, user, pass)
                host
            },
            update = {
                it.applyViewport(widthDp)
                if (origin.isNotBlank()) it.bind(origin, user, pass)
            },
        )
    }

    @Composable
    private fun SettingsPane(
        mode: RuntimeMode,
        runtimeState: RuntimeService.State,
        expanded: Boolean,
        serve: ServeState?,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onUpdate: () -> Unit,
        onAbout: () -> Unit,
        modifier: Modifier,
    ) {
        var url by remember { mutableStateOf(AppPrefs.remoteUrl(this)) }
        var user by remember { mutableStateOf(AppPrefs.remoteUser(this)) }
        var pass by remember { mutableStateOf(AppPrefs.remotePassword(this)) }
        val cats = listOf(
            AppPrefs.CAT_SENSORS, AppPrefs.CAT_LOCATION, AppPrefs.CAT_CAMERA, AppPrefs.CAT_CLIPBOARD,
            AppPrefs.CAT_NOTIFY, AppPrefs.CAT_SYSTEM, AppPrefs.CAT_USB, AppPrefs.CAT_COMMS,
        ).associateWith { AppPrefs.categoryEnabled(this, it, it != AppPrefs.CAT_COMMS) }
        var catState by remember { mutableStateOf(cats) }
        val logs = listOf("runtime-state.log", "install.log", "svc.log", "phone.log").joinToString("\n\n") {
            val body = EmbeddedRuntime.readLog(this, it, 6_000)
            if (body.isBlank()) "" else "--- $it ---\n$body"
        }
        SettingsScreen(
            mode = mode,
            onMode = { AppPrefs.setMode(this, it) },
            remoteUrl = url,
            onRemoteUrl = { url = it; AppPrefs.setRemoteUrl(this, it) },
            remoteUser = user,
            onRemoteUser = { user = it; AppPrefs.setRemoteUser(this, it) },
            remotePassword = pass,
            onRemotePassword = { pass = it; AppPrefs.setRemotePassword(this, it) },
            running = RuntimeService.running || (serve != null && mode != RuntimeMode.EMBEDDED),
            phase = runtimeState.phase.name,
            message = runtimeState.message + (serve?.let { tr("  порт ${it.port}", "  port ${it.port}") } ?: ""),
            categories = catState,
            onCategory = { k, v -> AppPrefs.setCategoryEnabled(this, k, v); catState = catState + (k to v) },
            logs = logs,
            twoPane = expanded,
            onStart = onStart,
            onStop = onStop,
            onUpdate = onUpdate,
            onAbout = onAbout,
            modifier = modifier,
        )
    }

    private fun initialDest(): Dest = when {
        EmbeddedRuntime.isUnsupportedAbi() && AppPrefs.mode(this) != RuntimeMode.REMOTE -> Dest.Unsupported
        AppPrefs.mode(this) == RuntimeMode.EMBEDDED && !EmbeddedRuntime.installed(this) -> Dest.Setup
        else -> Dest.Session
    }

    private fun titleFor(d: Dest) = when (d) {
        Dest.Setup -> tr("Установка", "Setup")
        Dest.Session -> "OpenCode Mobile"
        Dest.Settings -> tr("Настройки", "Settings")
        Dest.About -> tr("О приложении", "About")
        Dest.Unsupported -> tr("Не поддерживается", "Unsupported")
    }

    private fun remoteState(): ServeState? {
        val url = AppPrefs.remoteUrl(this)
        if (url.isBlank()) return null
        val u = Uri.parse(url)
        return ServeState(u.port.takeIf { it > 0 } ?: if (u.scheme == "https") 443 else 80, -1, AppPrefs.remotePassword(this), 0L, u.host ?: "127.0.0.1")
    }

    private fun aboutText(): String {
        val notice = runCatching { assets.open("../../NOTICE") }.getOrNull()
        val fromAssets = runCatching { assets.open("licenses/README.txt").bufferedReader().readText() }.getOrDefault("")
        val bundled = runCatching { resources.assets.open("licenses/proot-GPL-2.0.txt") }
        return buildString {
            appendLine(tr("Неофициальный клиент, не продукт команды OpenCode.", "Unofficial client, not a product of the OpenCode team."))
            appendLine()
            appendLine(runCatching { File("/workspace/opencode-android/NOTICE").takeIf { false }?.readText() }.getOrNull() ?: "")
            append(runCatching { assets.open("NOTICE").bufferedReader().readText() }.getOrDefault(
                """
                OpenCode (MIT) — https://github.com/anomalyco/opencode
                PRoot, Termux fork (GPL-2.0-or-later) — https://github.com/termux/proot (commit 52d65a4d)
                talloc 2.5.0 (LGPL-3.0-or-later) — https://talloc.samba.org
                usb-serial-for-android (MIT) — https://github.com/mik3y/usb-serial-for-android
                termux-api-package CLI names (MIT). Termux:API (GPLv3) is not included.
                Runtime adapted from MURF / Hermex (MIT).
                Full license texts: assets/licenses/
                """.trimIndent(),
            ))
            if (fromAssets.isNotBlank()) { appendLine(); append(fromAssets) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webHost?.goBackOrFalse() == true) return true
        return super.onKeyDown(keyCode, event)
    }
}
