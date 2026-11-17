package ru.dredd20.opencode.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import ru.dredd20.opencode.MainActivity
import ru.dredd20.opencode.R
import ru.dredd20.opencode.i18n.tr
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface PhoneConfirmer {
    fun await(req: PhoneRequest, prompt: String, timeoutMs: Long = 60_000): Boolean
    fun requestPermission(permission: String)
    fun takePicture(dest: File): Boolean
}

object PhonePending {
    data class Ask(val prompt: String, val deferred: CompletableDeferred<Boolean>)
    val asks = ConcurrentHashMap<String, Ask>()
    val permissionQueue = ConcurrentHashMap.newKeySet<String>()
    @Volatile var pictureRequest: CompletableDeferred<File>? = null
    @Volatile var pictureDest: File? = null

    fun complete(id: String, ok: Boolean) { asks.remove(id)?.deferred?.complete(ok) }
}

class NotificationConfirmer(private val context: Context) : PhoneConfirmer {
    private val ids = AtomicInteger(2000)

    override fun await(req: PhoneRequest, prompt: String, timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        PhonePending.asks[req.id] = PhonePending.Ask(prompt, deferred)
        // Also surface in the activity if it is visible.
        context.sendBroadcast(Intent(ACTION_PROMPT).setPackage(context.packageName).putExtra("id", req.id).putExtra("prompt", prompt))
        val nid = ids.incrementAndGet()
        val yes = pending("yes", req.id, nid * 2)
        val no = pending("no", req.id, nid * 2 + 1)
        PhoneApiService.ensureChannel(context)
        val n = NotificationCompat.Builder(context, PhoneApiService.NOTIFY_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(tr("Подтверждение Phone API", "Phone API confirmation"))
            .setContentText(prompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .addAction(0, tr("Да", "Yes"), yes)
            .addAction(0, tr("Нет", "No"), no)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, nid,
                    Intent(context, MainActivity::class.java).putExtra("confirm_id", req.id).putExtra("confirm_prompt", prompt),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { NotificationManagerCompat.from(context).notify(nid, n) }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(timeoutMs) { deferred.await() }
            }
        } catch (_: Exception) {
            false
        } finally {
            PhonePending.asks.remove(req.id)
            NotificationManagerCompat.from(context).cancel(nid)
        }
    }

    override fun requestPermission(permission: String) {
        PhonePending.permissionQueue.add(permission)
        context.sendBroadcast(Intent(ACTION_PERMISSION).setPackage(context.packageName).putExtra("permission", permission))
        val launch = Intent(context, MainActivity::class.java)
            .putExtra("request_permission", permission)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val open = PendingIntent.getActivity(
            context, permission.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // If the activity is already visible, bring it up immediately so the system dialog can show.
        runCatching { open.send() }
        PhoneApiService.ensureChannel(context)
        val n = NotificationCompat.Builder(context, PhoneApiService.NOTIFY_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(tr("Нужно разрешение", "Permission needed"))
            .setContentText(permission)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { NotificationManagerCompat.from(context).notify(permission.hashCode() and 0xffff, n) }
        }
    }

    override fun takePicture(dest: File): Boolean {
        val d = CompletableDeferred<File>()
        PhonePending.pictureDest = dest
        PhonePending.pictureRequest = d
        val open = PendingIntent.getActivity(
            context, 42,
            Intent(context, MainActivity::class.java).putExtra("take_picture", dest.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try { open.send() } catch (_: Exception) {
            context.startActivity(Intent(context, MainActivity::class.java).putExtra("take_picture", dest.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(90_000) { d.await() }
            }.isFile
        } catch (_: Exception) { false }
        finally {
            PhonePending.pictureRequest = null
            PhonePending.pictureDest = null
        }
    }

    private fun pending(answer: String, id: String, reqCode: Int): PendingIntent {
        val i = Intent(context, PhoneConfirmReceiver::class.java).setAction(ACTION_ANSWER)
            .putExtra("id", id).putExtra("ok", answer == "yes")
        return PendingIntent.getBroadcast(context, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_PROMPT = "ru.dredd20.opencode.PHONE_PROMPT"
        const val ACTION_PERMISSION = "ru.dredd20.opencode.PHONE_PERMISSION"
        const val ACTION_ANSWER = "ru.dredd20.opencode.PHONE_ANSWER"
    }
}

class PhoneConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationConfirmer.ACTION_ANSWER) return
        val id = intent.getStringExtra("id") ?: return
        val ok = intent.getBooleanExtra("ok", false)
        PhonePending.complete(id, ok)
    }
}
