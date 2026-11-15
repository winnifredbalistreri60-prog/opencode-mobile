package ru.dredd20.opencode.runtime

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import ru.dredd20.opencode.serve.AgentEventWatcher
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.dredd20.opencode.MainActivity
import ru.dredd20.opencode.R
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.phone.PhoneApiService
import ru.dredd20.opencode.serve.PortAllocator
import ru.dredd20.opencode.serve.ServeCredentials
import ru.dredd20.opencode.serve.ServeHealth
import ru.dredd20.opencode.serve.ServeReuse
import ru.dredd20.opencode.runtime.SecretFiles
import ru.dredd20.opencode.serve.ServeState
import java.io.File
import java.util.concurrent.TimeUnit

class RuntimeService : Service() {
    enum class Phase { IDLE, INSTALLING, STARTING, RUNNING, STOPPING, ERROR }
    data class State(val phase: Phase = Phase.IDLE, val message: String = "", val op: String = "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var installJob: Job? = null
    @Volatile private var stopRequested = false
    private var installOp = OP_INSTALL
    private val agentWatcher = AgentEventWatcher(this)
    /** Start, stop and install never interleave (BUG from run3: first Start after Stopped sometimes lost). */
    private val opLock = Mutex()
    /** Latest startId: stopSelfResult() with it never stops a service that just got a newer Start. */
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(tr("OpenCode Mobile", "OpenCode Mobile"), state.value.message.ifBlank { tr("Подготовка…", "Preparing…") })
        lastStartId = startId
        when (intent?.action) {
            ACTION_INSTALL -> startInstall(update = false)
            ACTION_UPDATE -> startInstall(update = true)
            ACTION_START -> startServer()
            ACTION_STOP -> {
                // Flag now (main thread) so a Start/Install that is still waiting for /api/info gives up;
                // capture the install job now so a later Update intent is not cancelled by this Stop.
                stopRequested = true
                val job = installJob
                scope.launch { stopAll(job); stopSelfIfIdle() }
            }
            else -> if (process?.isAlive != true && installJob?.isActive != true) stopSelfIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun startInstall(update: Boolean) {
        if (installJob?.isActive == true) return
        acquireWake()
        installJob = scope.launch {
            try {
                opLock.withLock { installLocked(update) }
            } finally {
                releaseWake()
                installJob = null
                stopSelfIfIdle(ignoreInstallJob = true)
            }
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.installLocked(update: Boolean) {
        if (process?.isAlive == true) {
            set(Phase.RUNNING, tr("Сначала остановите сервер", "Stop the server first"))
            return
        }
        stopRequested = false
        installOp = if (update) OP_UPDATE else OP_INSTALL
        set(Phase.INSTALLING, if (update) tr("Обновление OpenCode…", "Updating OpenCode…") else tr("Установка среды…", "Installing the environment…"))
        try {
            SetupPipeline(this@RuntimeService, updateCliOnly = update && EmbeddedRuntime.installed(this@RuntimeService))
                .run { !isActive || stopRequested }
            if (stopRequested) set(Phase.IDLE, tr("Прервано", "Cancelled"))
            else set(Phase.IDLE, if (update) tr("Обновление завершено", "Update finished") else tr("Установка завершена", "Installation finished"))
        } catch (e: java.util.concurrent.CancellationException) {
            set(Phase.IDLE, tr("Прервано", "Cancelled"))
        } catch (e: Exception) {
            set(Phase.ERROR, e.message ?: e.toString())
        }
    }

    private fun startServer() {
        if (installJob?.isActive == true) return
        acquireWake()
        scope.launch {
            // Waits for a Stop that is still running instead of racing it (run3 item 8).
            val p = opLock.withLock { startLocked() }
            if (p != null) {
                val rc = p.waitFor()
                if (process === p) {
                    process = null
                    if (state.value.phase != Phase.STOPPING) set(Phase.ERROR, tr("Сервер завершился (код $rc)", "Server exited (code $rc)"))
                    releaseWake(); stopSelfIfIdle()
                }
            } else if (process == null && _state.value.phase != Phase.STOPPING && _state.value.phase != Phase.RUNNING) {
                releaseWake(); stopSelfIfIdle()
            }
        }
    }

    /** Spawns serve and waits until it is healthy. Returns the live process, or null (state already set). */
    private suspend fun startLocked(): Process? {
        if (process?.isAlive == true) {
            set(Phase.RUNNING, tr("Сервер уже работает", "Server already running"))
            return null
        }
        stopRequested = false
        if (!EmbeddedRuntime.installed(this)) {
            set(Phase.ERROR, tr("Среда не установлена", "Environment is not installed"))
            return null
        }
        GuestFiles.installPhoneCli(this)
        // Merge-only: adds our keys if missing, keeps user MCP servers and plugins.
        runCatching { GuestFiles.writeOpenCodeConfig(this) }
            .onFailure { EmbeddedRuntime.appendLog(this, "svc.log", "config merge failed: ${it.javaClass.simpleName}") }
        set(Phase.STARTING, tr("Запуск OpenCode…", "Starting OpenCode…"))
        run {
            try {
                PhoneApiService.ensure(this@RuntimeService)
                val existing = ServeState.read(EmbeddedRuntime.stateFile(this@RuntimeService))
                val last = ServeState.read(EmbeddedRuntime.lastServeFile(this@RuntimeService))
                val pidAlive = existing?.let { ProcTree.isAlive(it.pid) } == true
                val healthOk = existing != null && ServeHealth.check(existing.origin, existing.password).ok
                if (ServeReuse.shouldReuse(existing, pidAlive, healthOk, null)) {
                    set(Phase.RUNNING, tr("Сервер уже запущен на порту ${existing!!.port}", "Server already running on port ${existing!!.port}"))
                    agentWatcher.start(scope, existing.origin, existing.password)
                    ServeState.write(EmbeddedRuntime.lastServeFile(this@RuntimeService), existing)
                    // Keep the FGS up even though we didn't spawn; no process to wait on.
                    return null
                }
                var lastError: String? = null
                for (attempt in 0 until 3) {
                    if (stopRequested) break
                    val preferred = if (attempt == 0) existing?.port ?: last?.port else null
                    val port = PortAllocator.pickFreePort("127.0.0.1", preferred)
                    val reusedPassword = when {
                        existing != null && existing.port == port && existing.password.isNotBlank() -> existing.password
                        last != null && last.port == port && last.password.isNotBlank() -> last.password
                        else -> null
                    }
                    val password = reusedPassword ?: ServeCredentials.randomPassword()
                    val log = File(EmbeddedRuntime.logsDir(this@RuntimeService), "svc.log")
                    if (log.length() > 5_000_000) log.renameTo(File(log.path + ".1"))
                    writeServePasswordFile(password)
                    val guest = ProotCommand.bash(SecretFiles.serveCommand(port))
                    val p = EmbeddedRuntime.processBuilder(
                        this@RuntimeService,
                        guest,
                    ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
                    p.outputStream.close()
                    process = p
                    val pid = pidOf(p)
                    val ok = waitHealthy(port, password)
                    if (ok) {
                        val st = ServeState(port = port, pid = pid, password = password, startedAt = System.currentTimeMillis())
                        ServeState.write(EmbeddedRuntime.stateFile(this@RuntimeService), st)
                        ServeState.write(EmbeddedRuntime.lastServeFile(this@RuntimeService), st)
                        set(Phase.RUNNING, tr("Сервер слушает 127.0.0.1:$port", "Server listening on 127.0.0.1:$port"))
                        agentWatcher.start(scope, st.origin, password)
                        return p
                    }
                    lastError = tr("не дождались /api/info на порту $port", "timed out waiting for /api/info on port $port")
                    killTree(p, graceSec = 2)
                    process = null
                }
                if (stopRequested) set(Phase.IDLE, tr("Остановлено", "Stopped"))
                else set(Phase.ERROR, lastError ?: tr("не удалось запустить сервер", "failed to start the server"))
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                set(Phase.ERROR, "proot: ${e.message}")
            }
        }
        return null
    }

    private suspend fun waitHealthy(port: Int, password: String): Boolean {
        val origin = "http://127.0.0.1:$port"
        repeat(40) {
            if (stopRequested) return false
            if (process?.isAlive != true) return false
            if (ServeHealth.check(origin, password).ok) return true
            delay(500)
        }
        return false
    }

    private suspend fun stopAll(job: Job?) {
        set(Phase.STOPPING, tr("Остановка…", "Stopping…"))
        stopRequested = true
        job?.cancel()
        opLock.withLock { stopLocked() }
    }

    private fun stopLocked() {
        agentWatcher.stop()
        val p = process
        if (p != null) killTree(p, graceSec = 8)
        process = null
        runCatching { PhoneApiService.stop(this) }
        EmbeddedRuntime.stateFile(this).delete()
        servePasswordFile().delete()
        releaseWake()
        set(Phase.IDLE, tr("Остановлено", "Stopped"))
    }

    /**
     * proot ignores SIGTERM while traced processes live. Process.destroyForcibly() is also SIGTERM
     * on Android, so we SIGKILL the tree ourselves (MURF-proven approach).
     */
    private fun killTree(p: Process, graceSec: Long = 3) {
        p.destroy()
        if (p.waitFor(graceSec, TimeUnit.SECONDS)) return
        val pid = pidOf(p)
        if (pid > 0) {
            val kids = ProcTree.descendants(pid)
            (kids + pid).forEach { runCatching { Os.kill(it, OsConstants.SIGKILL) } }
        } else p.destroyForcibly()
        if (!p.waitFor(5, TimeUnit.SECONDS)) EmbeddedRuntime.appendLog(this, "install.log", "proot (pid $pid) did not exit after SIGKILL")
    }

    private fun stopSelfIfIdle(ignoreInstallJob: Boolean = false) {
        if (process?.isAlive == true || (!ignoreInstallJob && installJob?.isActive == true)) return
        val ph = _state.value.phase
        if (ph == Phase.RUNNING || ph == Phase.STARTING || ph == Phase.INSTALLING) return
        if (opLock.isLocked) return // a Start/Install is queued right behind us
        // stopSelfResult(lastStartId) is a no-op if a newer Start arrived meanwhile (run3 item 8).
        if (stopSelfResult(lastStartId)) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:runtime").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWake() { runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null }

    private fun set(phase: Phase, msg: String) {
        _state.value = State(phase, msg, installOp)
        // Do not append passwords: callers must pass redacted messages.
        EmbeddedRuntime.appendLog(this, "runtime-state.log", "$phase: $msg")
        if (phase != Phase.IDLE || process?.isAlive == true || installJob?.isActive == true) {
            goForeground(tr("OpenCode Mobile", "OpenCode Mobile"), msg)
        }
    }

    private fun goForeground(title: String, text: String) {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, tr("Остановить", "Stop"), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    override fun onDestroy() {
        process?.let { killTree(it, graceSec = 3) }
        process = null
        releaseWake()
        val st = _state.value
        if (st.phase == Phase.STOPPING) _state.value = State(Phase.IDLE, tr("Остановлено", "Stopped"), st.op)
        else if (st.phase != Phase.ERROR) _state.value = State(Phase.IDLE, st.message, st.op)
        scope.cancel()
        super.onDestroy()
    }


    private fun servePasswordFile() = File(EmbeddedRuntime.runDir(this), "opencode-password")

    private fun writeServePasswordFile(password: String) {
        val f = servePasswordFile()
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(SecretFiles.envFileBody(SecretFiles.SERVE_PASSWORD_ENV, password))
        if (!tmp.renameTo(f)) {
            f.writeText(SecretFiles.envFileBody(SecretFiles.SERVE_PASSWORD_ENV, password))
            tmp.delete()
        }
        runCatching { Os.chmod(f.absolutePath, 384) } // 0600
        f.setReadable(false, false)
        f.setReadable(true, true)
        f.setWritable(false, false)
        f.setWritable(true, true)
        f.setExecutable(false, false)
    }

    companion object {
        const val ACTION_INSTALL = "ru.dredd20.opencode.INSTALL"
        const val ACTION_UPDATE = "ru.dredd20.opencode.UPDATE"
        const val ACTION_START = "ru.dredd20.opencode.START"
        const val ACTION_STOP = "ru.dredd20.opencode.STOP"
        const val CHANNEL = "opencode_runtime"
        const val NOTIFICATION_ID = 8101
        const val OP_INSTALL = "install"
        const val OP_UPDATE = "update"

        @Volatile private var process: Process? = null
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state
        val running: Boolean get() = process?.isAlive == true || _state.value.phase == Phase.RUNNING
        val installing: Boolean get() = _state.value.phase == Phase.INSTALLING

        fun send(c: Context, action: String) {
            ContextCompat.startForegroundService(c, Intent(c, RuntimeService::class.java).setAction(action))
        }

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, c.getString(R.string.notification_channel_runtime), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        fun currentServe(c: Context): ServeState? = ServeState.read(EmbeddedRuntime.stateFile(c))
    }
}
