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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PhoneApiService : Service() {
    private val running = AtomicBoolean(false)
    private var server: UnixSocketServer? = null
    private val pool = Executors.newCachedThreadPool()
    private lateinit var handlers: PhoneHandlers
    private lateinit var token: String

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handlers = PhoneHandlers(this, UsbController(this), NotificationConfirmer(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Must not be started via startForegroundService; stop() uses startService.
            stopSelf(); return START_NOT_STICKY
        }
        // startForeground must be called before any early return or stopSelf
        // (Android throws RemoteServiceException otherwise).
        goForeground()
        if (running.compareAndSet(false, true)) startLoop()
        goForeground() // refresh text after the socket is bound
        return START_STICKY
    }

    private fun startLoop() {
        val run = EmbeddedRuntime.runDir(this).apply { mkdirs() }
        val sock = File(run, "phone.sock")
        val tokenFile = File(run, "phone.token")
        token = tokenFile.let { f ->
            if (f.isFile && f.length() >= 16) f.readText().trim()
            else ServeCredentials.randomPassword().also { f.writeText(it); f.setReadable(false, false); f.setReadable(true, true); f.setWritable(true, true) }
        }
        try {
            server = UnixSocketServer(sock).also { it.start() }
        } catch (e: Exception) {
            EmbeddedRuntime.appendLog(this, "phone.log", "socket bind failed: ${e.message}")
            running.set(false)
            stopSelf()
            return
        }
        pool.execute {
            while (running.get()) {
                val client = try { server?.accept() } catch (_: Exception) { break } ?: break
                pool.execute {
                    try {
                        val line = client.input.bufferedReader().readLine() ?: return@execute
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
            }
        }
    }

    private fun dispatch(req: PhoneRequest?): PhoneResponse {
        if (req == null) return PhoneResponse.error("0", "bad request")
        if (!PhoneProtocol.tokenOk(req.token, token)) return PhoneResponse.error(req.id, "unauthorized")
        val cat = PhoneProtocol.categoryFor(req.cmd)
        if (cat != null) {
            if (!AppPrefs.categoryEnabled(this, cat)) {
                return PhoneResponse.error(req.id, tr("категория $cat выключена в настройках", "category $cat is disabled in settings"))
            }
        }
        return handlers.handle(req)
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
        running.set(false)
        runCatching { server?.close() }
        pool.shutdownNow()
        super.onDestroy()
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
        const val CHANNEL = "opencode_phone"
        const val NOTIFY_CHANNEL = "opencode_phone_events"
        const val NOTIFICATION_ID = 8102

        fun ensure(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, PhoneApiService::class.java))
        }

        fun stop(c: Context) {
            c.startService(Intent(c, PhoneApiService::class.java).setAction(ACTION_STOP))
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
