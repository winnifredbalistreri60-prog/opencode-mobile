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
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.dredd20.opencode.phone.PermissionRequests
import ru.dredd20.opencode.prefs.AppVisibility
import ru.dredd20.opencode.runtime.ProcTree
import ru.dredd20.opencode.serve.ServeHealth
import ru.dredd20.opencode.ui.WebViewSupport
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Extension
import ru.dredd20.opencode.ui.PluginsScreen
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
import ru.dredd20.opencode.serve.RemoteOrigin
import ru.dredd20.opencode.serve.ServeCredentials
import ru.dredd20.opencode.serve.ServeState
import ru.dredd20.opencode.ui.AboutScreen
import ru.dredd20.opencode.ui.TermuxActions
import ru.dredd20.opencode.ui.TermuxSetupBlock
import ru.dredd20.opencode.backend.TermuxInstall
import ru.dredd20.opencode.ui.OpenCodeTheme
import ru.dredd20.opencode.ui.OpenCodeWebView
import ru.dredd20.opencode.ui.SettingsScreen
import ru.dredd20.opencode.ui.SetupScreen
import ru.dredd20.opencode.ui.UnsupportedAbiScreen
import ru.dredd20.opencode.ui.ViewportPolicy
import java.io.File

class MainActivity : ComponentActivity() {
    enum class Dest { Setup, Session, Settings, About, Plugins, Unsupported }

    /** Retained across configuration changes (Activity is not recreated). */
    private var webHost: OpenCodeWebView? = null
    @Volatile private var currentDest: Dest = Dest.Session
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile private var liveConn: ru.dredd20.opencode.plugins.ServerConn? = null
    private val plugins by lazy {
        ru.dredd20.opencode.plugins.PluginsController(
            cacheDir = File(filesDir, "plugins"),
            scope = lifecycleScope,
            configFile = {
                if (AppPrefs.mode(this) == RuntimeMode.EMBEDDED && EmbeddedRuntime.rootfsReady(this))
                    ru.dredd20.opencode.runtime.OpenCodeConfig.configFile(EmbeddedRuntime.rootfsDir(this))
                else null
            },
            conn = { liveConn },
            log = { EmbeddedRuntime.appendLog(this, "svc.log", it) },
        )
    }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = result.data?.let { intent ->
            val clip = intent.clipData
            if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri }
            else intent.data?.let { arrayOf(it) }
        }
        // OpenCodeWebView owns the ValueCallback; calling it twice crashes
        // ("showFileChooser result was already called") on both pick and cancel.
        webHost?.onFileChosen(uris)
        fileCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    /** Bumped after a permission answer and on resume so Termux readiness is re-read. */
    private var permTick by mutableIntStateOf(0)
    /** Termux mode: last start error (stderr from Termux) and whether a start is in progress. */
    private var termuxError by mutableStateOf<String?>(null)
    private var termuxBusy by mutableStateOf(false)
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
                // Settings/About: never steal Back for the hidden WebView (BUG-19).
                if (currentDest == Dest.Settings || currentDest == Dest.About || currentDest == Dest.Plugins) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                    return
                }
                if (currentDest == Dest.Session && webHost?.goBackOrFalse() == true) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        handleSpecialIntent(intent)
        setContent { App() }
        maybeAskNotifications()
        ru.dredd20.opencode.update.AppUpdater.autoCheck(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpecialIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.resumed = true
        permTick++
        ru.dredd20.opencode.update.AppUpdater.onResume(this)
    }

    override fun onPause() {
        AppVisibility.resumed = false
        super.onPause()
    }

    private fun handleSpecialIntent(intent: Intent?) {
        val perm = intent?.getStringExtra("request_permission")
        if (perm != null) {
            permissionLauncher.launch(PermissionRequests.expand(listOf(perm)))
            intent.removeExtra("request_permission")
        }
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
            currentDest = dest
            androidx.compose.runtime.SideEffect { currentDest = dest }
            var tick by remember { mutableIntStateOf(0) }
            var confirmPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
            var userMessage by remember { mutableStateOf<String?>(null) }
            var askStopForUpdate by remember { mutableStateOf(false) }
            var pendingMode by remember { mutableStateOf<RuntimeMode?>(null) }
            var liveServe by remember { mutableStateOf<ServeState?>(null) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(800)
                    tick++
                    PhonePending.asks.entries.firstOrNull()?.let { confirmPrompt = it.key to it.value.prompt }
                    val pending = PhonePending.permissionQueue.toList()
                    if (pending.isNotEmpty()) {
                        PhonePending.permissionQueue.removeAll(pending.toSet())
                        permissionLauncher.launch(PermissionRequests.expand(pending))
                    }
                }
            }
            BackHandler(enabled = dest == Dest.Settings || dest == Dest.About || dest == Dest.Plugins) {
                dest = if (EmbeddedRuntime.installed(this@MainActivity) || AppPrefs.mode(this@MainActivity) != RuntimeMode.EMBEDDED) Dest.Session else Dest.Setup
            }
            // `tick` is read so prefs/files/progress recompose after install, start, or a mode change.
            val mode = remember(tick, runtimeState) { AppPrefs.mode(this) }
            val installed = remember(tick, runtimeState) { EmbeddedRuntime.installed(this) }
            val rawServe = remember(tick, runtimeState, mode) {
                when (mode) {
                    RuntimeMode.EMBEDDED -> RuntimeService.currentServe(this)
                    RuntimeMode.TERMUX -> ru.dredd20.opencode.serve.ServeState.read(TermuxServe.stateFile(this))
                    RuntimeMode.REMOTE -> remoteState()
                }
            }
            LaunchedEffect(rawServe?.port, rawServe?.pid, rawServe?.password, runtimeState.phase, mode) {
                if (mode == RuntimeMode.REMOTE) {
                    liveServe = rawServe
                    return@LaunchedEffect
                }
                if (rawServe == null) {
                    liveServe = null
                    return@LaunchedEffect
                }
                if (mode == RuntimeMode.EMBEDDED && RuntimeService.running && runtimeState.phase == RuntimeService.Phase.RUNNING) {
                    liveServe = rawServe
                    return@LaunchedEffect
                }
                val ok = withContext(Dispatchers.IO) {
                    val pidOk = rawServe.pid <= 0 || ProcTree.isAlive(rawServe.pid)
                    val healthOk = pidOk && ServeHealth.check(rawServe.origin, rawServe.password).ok
                    if (!healthOk) {
                        when (mode) {
                            RuntimeMode.EMBEDDED -> EmbeddedRuntime.stateFile(this@MainActivity).delete()
                            RuntimeMode.TERMUX -> TermuxServe.stateFile(this@MainActivity).delete()
                            else -> Unit
                        }
                    }
                    healthOk
                }
                liveServe = if (ok) rawServe else null
            }
            val serve = liveServe
            liveConn = when {
                mode == RuntimeMode.REMOTE && serve != null -> ru.dredd20.opencode.plugins.ServerConn(AppPrefs.remoteUrl(this).trimEnd('/'), AppPrefs.remoteUser(this), AppPrefs.remotePassword(this))
                serve != null -> ru.dredd20.opencode.plugins.ServerConn(serve.origin, ServeCredentials.USERNAME, serve.password)
                else -> null
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
                                IconButton(onClick = { dest = Dest.Plugins }) {
                                    Icon(Icons.Default.Extension, contentDescription = tr("Плагины", "Plugins"))
                                }
                                IconButton(onClick = { dest = Dest.Settings }) {
                                    Icon(Icons.Default.Settings, contentDescription = tr("Настройки приложения", "App settings"))
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                val mod = Modifier.fillMaxSize().padding(padding)
                Box(mod) {
                    when (dest) {
                        Dest.Unsupported -> UnsupportedAbiScreen(onRemote = { switchMode(RuntimeMode.REMOTE); dest = Dest.Settings }, modifier = Modifier.fillMaxSize())
                        Dest.Setup -> {
                            val p = EmbeddedRuntime.readProgress(this@MainActivity)
                            SetupScreen(
                                progressPct = p?.optInt("pct") ?: 0,
                                message = p?.optString("msg").orEmpty().ifBlank { runtimeState.message },
                                error = if (runtimeState.phase == RuntimeService.Phase.ERROR) runtimeState.message else p?.takeIf { it.optString("state") == "error" }?.optString("msg"),
                                installing = RuntimeService.installing || runtimeState.phase == RuntimeService.Phase.INSTALLING,
                                onInstall = { RuntimeService.send(this@MainActivity, RuntimeService.ACTION_INSTALL) },
                                onRetry = { RuntimeService.send(this@MainActivity, RuntimeService.ACTION_INSTALL) },
                                onRemote = { switchMode(RuntimeMode.REMOTE); dest = Dest.Settings },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Dest.Session, Dest.Settings, Dest.About, Dest.Plugins -> {
                            // Keep the WebView composed when visiting Settings/About so it is not recreated (BUG-20).
                            SessionPane(
                                serve, widthDp,
                                modifier = if (dest == Dest.Session) Modifier.fillMaxSize() else Modifier.size(0.dp),
                            )
                            if (dest == Dest.Settings) {
                                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    SettingsPane(mode, runtimeState, expanded, serve, userMessage, onStart = {
                                        userMessage = null
                                        when (AppPrefs.mode(this@MainActivity)) {
                                            RuntimeMode.EMBEDDED -> RuntimeService.send(this@MainActivity, RuntimeService.ACTION_START)
                                            RuntimeMode.TERMUX -> startTermux(
                                                onOk = { userMessage = null; dest = Dest.Session },
                                                onFail = { userMessage = it },
                                            )
                                            RuntimeMode.REMOTE -> {
                                                if (RemoteOrigin.parse(AppPrefs.remoteUrl(this@MainActivity)) == null) {
                                                    userMessage = tr("Укажите URL удалённого сервера", "Enter the remote server URL")
                                                } else {
                                                    dest = Dest.Session
                                                }
                                            }
                                        }
                                    }, onStop = {
                                        when (AppPrefs.mode(this@MainActivity)) {
                                            RuntimeMode.EMBEDDED -> RuntimeService.send(this@MainActivity, RuntimeService.ACTION_STOP)
                                            RuntimeMode.TERMUX -> lifecycleScope.launch { TermuxServe.stop(this@MainActivity); tick++ }
                                            RuntimeMode.REMOTE -> userMessage = tr("Удалённый сервер не управляется из приложения", "The remote server is not stopped from the app")
                                        }
                                    }, onUpdate = {
                                        if (RuntimeService.running) askStopForUpdate = true
                                        else RuntimeService.send(this@MainActivity, RuntimeService.ACTION_UPDATE)
                                    }, onAbout = { dest = Dest.About }, onPlugins = { dest = Dest.Plugins }, onRequestMode = { next ->
                                        if (next != mode) {
                                            if (modeHasRunningServer(mode, serve)) pendingMode = next
                                            else switchMode(next)
                                        }
                                    }, modifier = Modifier.fillMaxSize())
                                }
                            }
                            if (dest == Dest.Plugins) {
                                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    PluginsScreen(
                                        plugins,
                                        twoPane = expanded,
                                        onSignIn = {
                                            dest = Dest.Session
                                            webHost?.openPath("/settings?tab=extensions&subtab=mcps")
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            if (dest == Dest.About) {
                                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    AboutScreen(aboutText(), modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
                confirmPrompt?.let { (id, prompt) ->
                    AlertDialog(
                        onDismissRequest = { PhonePending.complete(id, false); confirmPrompt = null },
                        title = { Text(tr("Подтверждение", "Confirm")) },
                        text = { Text(prompt) },
                        confirmButton = { TextButton(onClick = { PhonePending.complete(id, true); confirmPrompt = null }) { Text(tr("Да", "Yes")) } },
                        dismissButton = { TextButton(onClick = { PhonePending.complete(id, false); confirmPrompt = null }) { Text(tr("Нет", "No")) } },
                    )
                }
                if (askStopForUpdate) {
                    AlertDialog(
                        onDismissRequest = { askStopForUpdate = false },
                        title = { Text(tr("Обновить OpenCode", "Update OpenCode")) },
                        text = { Text(tr("Сначала нужно остановить сервер. Остановить и обновить?", "The server must be stopped first. Stop it and update?")) },
                        confirmButton = {
                            TextButton(onClick = {
                                askStopForUpdate = false
                                RuntimeService.send(this@MainActivity, RuntimeService.ACTION_STOP)
                                RuntimeService.send(this@MainActivity, RuntimeService.ACTION_UPDATE)
                            }) { Text(tr("Остановить и обновить", "Stop and update")) }
                        },
                        dismissButton = { TextButton(onClick = { askStopForUpdate = false }) { Text(tr("Отмена", "Cancel")) } },
                    )
                }
                ru.dredd20.opencode.update.UpdateDialog()
                pendingMode?.let { next ->
                    AlertDialog(
                        onDismissRequest = { pendingMode = null },
                        title = { Text(tr("Сменить режим", "Switch mode")) },
                        text = { Text(tr("Остановить текущий сервер и переключиться?", "Stop the current server and switch mode?")) },
                        confirmButton = {
                            TextButton(onClick = {
                                val target = next
                                pendingMode = null
                                switchMode(target)
                            }) { Text(tr("Остановить и сменить", "Stop and switch")) }
                        },
                        dismissButton = { TextButton(onClick = { pendingMode = null }) { Text(tr("Отмена", "Cancel")) } },
                    )
                }
            }
        }
    }

    @Composable
    private fun SessionPane(serve: ServeState?, widthDp: Int, modifier: Modifier) {
        val mode = AppPrefs.mode(this)
        val host = webHost ?: return
        val ru = java.util.Locale.getDefault().language == "ru"
        val wvMajor = remember { host.webViewMajor() }
        val canPolyfill = remember { host.canInjectDocumentStart() }
        val block = WebViewSupport.blockingMessage(wvMajor, canPolyfill, ru)
        val advice = WebViewSupport.advisoryMessage(wvMajor, ru)
        if (mode == RuntimeMode.EMBEDDED && serve == null) {
            Column(modifier.padding(24.dp)) {
                Text(tr("Сервер не запущен.", "The server is not running."), style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Button(onClick = { RuntimeService.send(this@MainActivity, RuntimeService.ACTION_START) }) {
                    Text(tr("Запустить", "Start"))
                }
            }
            return
        }
        if (mode == RuntimeMode.TERMUX && serve == null) {
            @Suppress("UNUSED_VARIABLE") val permissionsVersion = permTick
            Column(modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text(
                    Termux.notReadyReason(this@MainActivity)
                        ?: tr("Сервер Termux не запущен.", "The Termux server is not running."),
                    style = MaterialTheme.typography.bodyLarge,
                )
                androidx.compose.material3.Button(
                    onClick = { startTermux(onOk = {}, onFail = {}) },
                    enabled = !termuxBusy,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(if (termuxBusy) tr("Запуск в Termux…", "Starting in Termux…") else tr("Запустить", "Start")) }
                TermuxSetupBlock(
                    Termux.instructions(), termuxActions(), termuxError,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            return
        }
        if (mode == RuntimeMode.REMOTE && RemoteOrigin.parse(AppPrefs.remoteUrl(this)) == null) {
            Column(modifier.padding(24.dp)) {
                Text(tr("Укажите URL удалённого сервера в настройках приложения.", "Enter the remote server URL in app settings."))
            }
            return
        }
        if (block != null) {
            Column(modifier.padding(24.dp)) {
                Text(block, color = MaterialTheme.colorScheme.error)
            }
            return
        }
        val origin = if (mode == RuntimeMode.REMOTE) AppPrefs.remoteUrl(this).trimEnd('/') else serve!!.origin
        val user = if (mode == RuntimeMode.REMOTE) AppPrefs.remoteUser(this) else ServeCredentials.USERNAME
        val pass = if (mode == RuntimeMode.REMOTE) AppPrefs.remotePassword(this) else serve!!.password
        Column(modifier.fillMaxSize()) {
            if (advice != null) {
                Text(advice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
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
    }

    @Composable
    private fun SettingsPane(
        mode: RuntimeMode,
        runtimeState: RuntimeService.State,
        expanded: Boolean,
        serve: ServeState?,
        userMessage: String?,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onUpdate: () -> Unit,
        onAbout: () -> Unit,
        onRequestMode: (RuntimeMode) -> Unit,
        onPlugins: () -> Unit,
        modifier: Modifier,
    ) {
        @Suppress("UNUSED_VARIABLE") val permissionsVersion = permTick // re-read Termux readiness
        var url by remember { mutableStateOf(AppPrefs.remoteUrl(this)) }
        var user by remember { mutableStateOf(AppPrefs.remoteUser(this)) }
        var pass by remember { mutableStateOf(AppPrefs.remotePassword(this)) }
        val cats = listOf(
            AppPrefs.CAT_SENSORS, AppPrefs.CAT_LOCATION, AppPrefs.CAT_CAMERA, AppPrefs.CAT_CLIPBOARD,
            AppPrefs.CAT_NOTIFY, AppPrefs.CAT_SYSTEM, AppPrefs.CAT_USB, AppPrefs.CAT_COMMS,
        ).associateWith { AppPrefs.categoryEnabled(this, it) }
        var catState by remember { mutableStateOf(cats) }
        var autoUpdate by remember { mutableStateOf(AppPrefs.updateAutoCheck(this)) }
        val logs = listOf("runtime-state.log", "install.log", "svc.log", "termux.log", "phone.log", "update.log").joinToString("\n\n") {
            val body = EmbeddedRuntime.readLog(this, it, 6_000)
            if (body.isBlank()) "" else "--- $it ---\n$body"
        }
        SettingsScreen(
            mode = mode,
            onMode = onRequestMode,
            remoteUrl = url,
            onRemoteUrl = { url = it; AppPrefs.setRemoteUrl(this, it) },
            remoteUser = user,
            onRemoteUser = { user = it; AppPrefs.setRemoteUser(this, it) },
            remotePassword = pass,
            onRemotePassword = { pass = it; AppPrefs.setRemotePassword(this, it) },
            running = when (mode) {
                RuntimeMode.EMBEDDED -> RuntimeService.running || runtimeState.phase == RuntimeService.Phase.RUNNING
                RuntimeMode.TERMUX -> serve != null
                RuntimeMode.REMOTE -> false
            },
            phase = when (mode) {
                RuntimeMode.EMBEDDED -> runtimeState.phase.name
                RuntimeMode.TERMUX -> if (serve != null) "RUNNING" else "IDLE"
                RuntimeMode.REMOTE -> if (serve != null) "READY" else "IDLE"
            },
            message = listOfNotNull(
                userMessage,
                when (mode) {
                    RuntimeMode.EMBEDDED -> ru.dredd20.opencode.ui.StatusText.embedded(runtimeState.message, serve?.port) { p -> tr("порт $p", "port $p") }
                    RuntimeMode.TERMUX -> serve?.let { tr("Termux 127.0.0.1:${it.port}", "Termux 127.0.0.1:${it.port}") }
                        ?: Termux.notReadyReason(this)
                    RuntimeMode.REMOTE -> serve?.let { tr("Удалённый ${it.origin}", "Remote ${it.origin}") }
                        ?: tr("Укажите URL удалённого сервера", "Enter the remote server URL")
                },
            ).distinct().joinToString(" · "),
            categories = catState,
            onCategory = { k, v -> AppPrefs.setCategoryEnabled(this, k, v); catState = catState + (k to v) },
            logs = logs,
            twoPane = expanded,
            onStart = onStart,
            onStop = onStop,
            onUpdate = onUpdate,
            onAbout = onAbout,
            termuxHint = if (mode == RuntimeMode.TERMUX) Termux.instructions() else null,
            updateAutoCheck = autoUpdate,
            onUpdateAutoCheck = { autoUpdate = it; AppPrefs.setUpdateAutoCheck(this, it) },
            onCheckUpdates = { ru.dredd20.opencode.update.AppUpdater.checkNow(this) },
            updateSource = BuildConfig.UPDATE_SOURCE_LABEL,
            onPlugins = onPlugins,
            termuxActions = if (mode == RuntimeMode.TERMUX) termuxActions() else null,
            termuxBusy = mode == RuntimeMode.TERMUX && termuxBusy,
            modifier = modifier,
        )
    }

    private fun termuxActions() = TermuxActions(
        termuxInstalled = Termux.isInstalled(this),
        hasPermission = Termux.hasPermission(this),
        onCopyInstall = { copyTermuxInstallCommand() },
        onRequestPermission = { permissionLauncher.launch(arrayOf(Termux.PERMISSION)) },
        onOpenTermux = {
            packageManager.getLaunchIntentForPackage(Termux.PACKAGE)?.let { runCatching { startActivity(it) } }
        },
    )

    private fun copyTermuxInstallCommand() {
        val text = runCatching { TermuxInstall.pasteCommand(TermuxInstall.script(this)) }
            .getOrElse {
                android.widget.Toast.makeText(this, it.message ?: it.toString(), android.widget.Toast.LENGTH_LONG).show()
                return
            }
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("OpenCode Mobile: Termux install", text))
        android.widget.Toast.makeText(
            this,
            tr("Команда скопирована. Вставьте её в Termux и нажмите Enter.", "Command copied. Paste it into Termux and press Enter."),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    /** One Termux start at a time; the error (stderr from Termux) is shown in the session pane and settings. */
    private fun startTermux(onOk: () -> Unit, onFail: (String) -> Unit) {
        if (termuxBusy) return
        termuxBusy = true
        termuxError = null
        lifecycleScope.launch {
            try {
                TermuxServe.ensureRunning(this@MainActivity).fold(
                    onSuccess = { termuxError = null; onOk() },
                    onFailure = {
                        val msg = it.message ?: it.toString()
                        termuxError = msg
                        onFail(msg)
                    },
                )
            } finally {
                termuxBusy = false
            }
        }
    }

    private fun initialDest(): Dest = when {
        EmbeddedRuntime.isUnsupportedAbi() && AppPrefs.mode(this) != RuntimeMode.REMOTE -> Dest.Unsupported
        AppPrefs.mode(this) == RuntimeMode.EMBEDDED && !EmbeddedRuntime.installed(this) -> Dest.Setup
        else -> Dest.Session
    }

    private fun titleFor(d: Dest) = when (d) {
        Dest.Setup -> tr("Установка", "Setup")
        Dest.Session -> "OpenCode Mobile"
        Dest.Settings -> tr("Настройки приложения", "App settings")
        Dest.About -> tr("О приложении", "About")
        Dest.Plugins -> tr("Плагины", "Plugins")
        Dest.Unsupported -> tr("Не поддерживается", "Unsupported")
    }

    private fun remoteState(): ServeState? {
        val parsed = RemoteOrigin.parse(AppPrefs.remoteUrl(this)) ?: return null
        return ServeState(parsed.port, -1, AppPrefs.remotePassword(this), 0L, parsed.host)
    }

    private fun modeHasRunningServer(mode: RuntimeMode, serve: ServeState?): Boolean = when (mode) {
        RuntimeMode.EMBEDDED -> RuntimeService.running
        RuntimeMode.TERMUX -> serve != null
        RuntimeMode.REMOTE -> false
    }

    private fun switchMode(next: RuntimeMode) {
        val prev = AppPrefs.mode(this)
        if (prev == next) return
        when (prev) {
            RuntimeMode.EMBEDDED -> if (RuntimeService.running) RuntimeService.send(this, RuntimeService.ACTION_STOP)
            RuntimeMode.TERMUX -> lifecycleScope.launch { TermuxServe.stop(this@MainActivity) }
            RuntimeMode.REMOTE -> Unit
        }
        AppPrefs.setMode(this, next)
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

}
