package ru.dredd20.opencode.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ru.dredd20.opencode.MainActivity
import ru.dredd20.opencode.R
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.prefs.AppPrefs
import ru.dredd20.opencode.runtime.EmbeddedRuntime
import ru.dredd20.opencode.serve.ServeCredentials
import ru.dredd20.opencode.usb.UsbController
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PhoneApiService : Service() {
    private val running = AtomicBoolean(false)
    private val lock = Any()
    private var server: UnixSocketServer? = null
    private var pool: ExecutorService? = null
    private var acceptThread: Thread? = null
    private lateinit var handlers: PhoneHandlers
    private lateinit var token: String

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handlers = PhoneHandlers(this, UsbController(this), NotificationConfirmer(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = intent?.getLongExtra(EXTRA_EPOCH, 0L) ?: 0L
        val stale = n != 0L && n < epoch.get()
        if (intent?.action == ACTION_STOP) {
            // Must not be started via startForegroundService; stop() uses startService.
            // A Stop/Start race can deliver this after a newer ensure(); ignore it.
            if (stale) return START_NOT_STICKY
            tearDown()
            stopSelf()
            return START_NOT_STICKY
        }
        // startForeground must be called before any early return or stopSelf
        // (Android throws RemoteServiceException otherwise).
        goForeground()
        if (stale) return START_STICKY
        synchronized(lock) {
            if (!running.get()) startLoop()
        }
        goForeground() // refresh text after the socket is bound
        return START_STICKY
    }

    private fun startLoop() {
        tearDownLocked()
        val run = EmbeddedRuntime.runDir(this).apply { mkdirs() }
        val sock = File(run, "phone.sock")
        val tokenFile = File(run, "phone.token")
        token = tokenFile.let { f ->
            if (f.isFile && f.length() >= 16) f.readText().trim()
            else ServeCredentials.randomPassword().also {
                f.writeText(it)
                f.setReadable(false, false)
                f.setReadable(true, true)
                f.setWritable(true, true)
            }
        }
        val workers = newPool()
        pool = workers
        running.set(true)
        try {
            server = UnixSocketServer(sock).also { it.start() }
        } catch (e: Exception) {
            // One more attempt after a forced close/unlink/wakeup.
            EmbeddedRuntime.appendLog(this, "phone.log", "socket bind failed: ${e.message}; retrying")
            runCatching { server?.close() }
            try {
                Thread.sleep(80)
                server = UnixSocketServer(sock).also { it.start() }
            } catch (e2: Exception) {
                EmbeddedRuntime.appendLog(this, "phone.log", "socket bind failed: ${e2.message}")
                running.set(false)
                workers.shutdownNow()
                if (pool === workers) pool = null
                stopSelf()
                return
            }
        }
        val listen = server
        val thread = Thread({
            try {
                while (running.get()) {
                    val client = try {
                        listen?.accept()
                    } catch (_: Exception) {
                        break
                    } ?: break
                    try {
                        workers.execute { handleClient(client) }
                    } catch (_: RejectedExecutionException) {
                        runCatching { client.close() }
                        if (!running.get()) break
                        // A closed pool must never crash the process.
                        EmbeddedRuntime.appendLog(this, "phone.log", "executor rejected a client; dropping")
                    } catch (t: Throwable) {
                        runCatching { client.close() }
                        EmbeddedRuntime.appendLog(this, "phone.log", "accept dispatch failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                EmbeddedRuntime.appendLog(this, "phone.log", "accept loop failed: ${t.message}")
            }
        }, "phone-accept").also {
            it.isDaemon = true
            it.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t ->
                EmbeddedRuntime.appendLog(this, "phone.log", "accept thread crash suppressed: ${t.message}")
            }
            it.start()
        }
        acceptThread = thread
    }

    private fun handleClient(client: ClientConn) {
        try {
            val line = client.input.bufferedReader().readLine() ?: return
            val req = PhoneProtocol.parse(line)
            val resp = dispatch(req)
            client.output.write((resp.toJson().toString() + "\n").toByteArray())
            client.output.flush()
        } catch (e: Exception) {
            EmbeddedRuntime.appendLog(this, "phone.log", "request failed: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun dispatch(req: PhoneRequest?): PhoneResponse {
        return try {
            if (req == null) return PhoneResponse.error("0", "bad request")
            if (!PhoneProtocol.tokenOk(req.token, token)) return PhoneResponse.error(req.id, "unauthorized")
            val cat = PhoneProtocol.categoryFor(req.cmd)
            if (cat != null && !AppPrefs.categoryEnabled(this, cat)) {
                return PhoneResponse.error(req.id, tr("категория $cat выключена в настройках", "category $cat is disabled in settings"))
            }
            handlers.handle(req)
        } catch (t: Throwable) {
            EmbeddedRuntime.appendLog(this, "phone.log", "dispatch failed: ${t.message}")
            PhoneResponse.error(req?.id ?: "0", t.message ?: "internal error")
        }
    }

    private fun goForeground() {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(tr("Phone API", "Phone API"))
            .setContentText(socketDescription())
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun tearDown() {
        synchronized(lock) { tearDownLocked() }
    }

    private fun tearDownLocked() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        val th = acceptThread
        acceptThread = null
        th?.interrupt()
        runCatching { th?.join(1_000) }
        val p = pool
        pool = null
        p?.shutdownNow()
        runCatching { p?.awaitTermination(1, TimeUnit.SECONDS) }
    }

    private fun newPool(): ExecutorService {
        val n = AtomicInteger(0)
        val factory = ThreadFactory { r ->
            Thread(r, "phone-pool-" + n.incrementAndGet()).apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t ->
                    EmbeddedRuntime.appendLog(this@PhoneApiService, "phone.log", "worker crash suppressed: ${t.message}")
                }
            }
        }
        return Executors.newCachedThreadPool(factory)
    }

    private fun socketDescription(): String {
        val s = server
        return when {
            s == null -> tr("Запуск сокета…", "Starting socket…")
            s.usesFilesystem -> tr("Сокет /run/phone.sock", "Socket /run/phone.sock")
            else -> tr("Абстрактный сокет @${UnixSocketServer.ABSTRACT_NAME}", "Abstract socket @${UnixSocketServer.ABSTRACT_NAME}")
        }
    }

    companion object {

        const val ACTION_STOP = "ru.dredd20.opencode.PHONE_STOP"
        const val EXTRA_EPOCH = "ru.dredd20.opencode.PHONE_EPOCH"
        const val CHANNEL = "opencode_phone"
        const val NOTIFY_CHANNEL = "opencode_phone_events"
        const val NOTIFICATION_ID = 8102

        private val epoch = AtomicLong(0)

        fun ensure(c: Context) {
            val n = epoch.incrementAndGet()
            val i = Intent(c, PhoneApiService::class.java).putExtra(EXTRA_EPOCH, n)
            ContextCompat.startForegroundService(c, i)
        }

        fun stop(c: Context) {
            val n = epoch.incrementAndGet()
            c.startService(Intent(c, PhoneApiService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_EPOCH, n))
        }

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.notification_channel_phone), NotificationManager.IMPORTANCE_LOW))
            }
            if (nm.getNotificationChannel(NOTIFY_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(NOTIFY_CHANNEL, "Phone API events", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
    }
}
