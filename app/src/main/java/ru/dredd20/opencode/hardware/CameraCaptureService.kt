package ru.dredd20.opencode.hardware

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import ru.dredd20.opencode.MainActivity
import ru.dredd20.opencode.R
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.runtime.EmbeddedRuntime
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraCaptureService : LifecycleService() {
    private val handler = Handler(Looper.getMainLooper())
    private var capture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var intervalMs: Int = 500
    private var ring: Int = 8
    private var outDir: File? = null
    private val runningTick = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SNAP -> {
                // A one-shot still uses the already-bound capture if present.
                snap("photo")
                return START_STICKY
            }
        }
        val interval = HardwareArgs.coerceIntervalMs(intent?.getIntExtra(EXTRA_INTERVAL, 500) ?: 500)
        val ringSize = HardwareArgs.coerceRing(intent?.getIntExtra(EXTRA_RING, 8) ?: 8)
        val width = intent?.getIntExtra(EXTRA_WIDTH, 0) ?: 0
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 0) ?: 0
        val dirPath = intent?.getStringExtra(EXTRA_DIR)
        intervalMs = interval
        ring = ringSize
        outDir = (dirPath?.let { File(it) } ?: defaultDir(this)).apply { mkdirs() }
        bindCamera(if (width > 0 && height > 0) Size(width, height) else null)
        return START_STICKY
    }

    private fun bindCamera(size: Size?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            lastError.set(tr("нет разрешения CAMERA", "CAMERA permission is missing"))
            readyLatch.countDown()
            stopSelf()
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                val builder = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                if (size != null) {
                    @Suppress("DEPRECATION")
                    builder.setTargetResolution(size)
                }
                val imageCapture = builder.build()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
                capture = imageCapture
                lastError.set(null)
                running.set(true)
                latestDir.set(outDir)
                readyLatch.countDown()
                schedule()
            } catch (e: Exception) {
                lastError.set(e.message ?: e.toString())
                readyLatch.countDown()
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun schedule() {
        runningTick.set(true)
        handler.post(object : Runnable {
            override fun run() {
                if (!runningTick.get() || !running.get()) return
                snap("frame")
                handler.postDelayed(this, intervalMs.toLong())
            }
        })
    }

    private fun snap(prefix: String) {
        val cap = capture ?: return
        val dir = outDir ?: return
        dir.mkdirs()
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        cap.takePicture(
            opts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    latestFile.set(file)
                    CameraFiles.prune(dir, ring)
                }
                override fun onError(exception: ImageCaptureException) {
                    lastError.set(exception.message ?: exception.toString())
                    EmbeddedRuntime.appendLog(this@CameraCaptureService, "phone.log", "camera capture failed: ${exception.message}")
                }
            },
        )
    }

    private fun stopCapture() {
        runningTick.set(false)
        handler.removeCallbacksAndMessages(null)
        runCatching { provider?.unbindAll() }
        capture = null
        provider = null
        running.set(false)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun goForeground() {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(tr("Камера OpenCode", "OpenCode camera"))
            .setContentText(tr("Идёт захват кадров для агента.", "Capturing frames for the agent."))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    companion object {
        const val ACTION_STOP = "ru.dredd20.opencode.CAMERA_STOP"
        const val ACTION_SNAP = "ru.dredd20.opencode.CAMERA_SNAP"
        const val EXTRA_INTERVAL = "interval_ms"
        const val EXTRA_RING = "ring"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DIR = "dir"
        const val CHANNEL = "opencode_camera"
        const val NOTIFICATION_ID = 8103

        val running = AtomicBoolean(false)
        val latestFile = AtomicReference<File?>(null)
        val latestDir = AtomicReference<File?>(null)
        val lastError = AtomicReference<String?>(null)
        @Volatile var readyLatch = CountDownLatch(1)

        fun defaultDir(c: Context): File = File(EmbeddedRuntime.hostDir(c), "camera").apply { mkdirs() }

        fun guestPath(c: Context, file: File): String {
            val root = EmbeddedRuntime.hostDir(c)
            val rel = file.relativeToOrNull(root)?.invariantSeparatorsPath
            return if (rel != null) "/opencode-host/$rel" else file.absolutePath
        }

        fun start(c: Context, intervalMs: Int, ring: Int, width: Int?, height: Int?, dir: File?): Map<String, Any?> {
            readyLatch = CountDownLatch(1)
            lastError.set(null)
            val i = Intent(c, CameraCaptureService::class.java)
                .putExtra(EXTRA_INTERVAL, intervalMs)
                .putExtra(EXTRA_RING, ring)
            if (width != null && height != null) {
                i.putExtra(EXTRA_WIDTH, width)
                i.putExtra(EXTRA_HEIGHT, height)
            }
            if (dir != null) i.putExtra(EXTRA_DIR, dir.absolutePath)
            ContextCompat.startForegroundService(c, i)
            val ok = readyLatch.await(15, TimeUnit.SECONDS)
            lastError.get()?.let { error(it) }
            if (!ok || !running.get()) error(tr("камера не запустилась", "camera did not start"))
            val out = dir ?: defaultDir(c)
            return mapOf(
                "started" to true,
                "interval_ms" to intervalMs,
                "ring" to ring,
                "dir" to guestPath(c, out),
                "width" to width,
                "height" to height,
            )
        }

        fun snapWait(c: Context, timeoutMs: Int = 8_000): File {
            if (!running.get()) error(tr("захват камеры не запущен (сначала camera-start или camera-capture)", "camera capture is not running (start it with camera-start or camera-capture)"))
            val before = latestFile.get()
            ContextCompat.startForegroundService(c, Intent(c, CameraCaptureService::class.java).setAction(ACTION_SNAP))
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val f = latestFile.get()
                if (f != null && f != before && f.isFile && f.length() > 0) return f
                Thread.sleep(40)
            }
            val f = latestFile.get()
            if (f != null && f.isFile && f.length() > 0) return f
            error(tr("кадр не получен", "no frame captured"))
        }

        fun startAndSnap(c: Context, width: Int?, height: Int?, dir: File?): File {
            if (!running.get()) {
                start(c, intervalMs = 60_000, ring = 4, width = width, height = height, dir = dir)
            }
            return snapWait(c)
        }

        fun stop(c: Context): Map<String, Any?> {
            c.startService(Intent(c, CameraCaptureService::class.java).setAction(ACTION_STOP))
            val deadline = System.currentTimeMillis() + 3_000
            while (running.get() && System.currentTimeMillis() < deadline) Thread.sleep(40)
            return mapOf("stopped" to true, "latest" to latestFile.get()?.let { guestPath(c, it) })
        }

        fun latest(c: Context): Map<String, Any?> {
            val f = latestFile.get()
            if (f == null || !f.isFile) error(tr("кадров ещё нет", "no frames yet"))
            return mapOf(
                "file" to guestPath(c, f),
                "bytes" to f.length(),
                "running" to running.get(),
            )
        }

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, tr("Захват камеры", "Camera capture"), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
