package ru.dredd20.opencode.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.provider.Telephony
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import ru.dredd20.opencode.R
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.runtime.EmbeddedRuntime
import ru.dredd20.opencode.usb.UsbController
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneHandlers(
    private val context: Context,
    private val usb: UsbController,
    private val confirm: PhoneConfirmer,
) {
    fun handle(req: PhoneRequest): PhoneResponse {
        if (req.cmd !in PhoneProtocol.COMMANDS) {
            return PhoneResponse.error(req.id, tr("неизвестная команда ${req.cmd}", "unknown command ${req.cmd}"))
        }
        return try {
            when (req.cmd) {
                "battery-status" -> PhoneResponse.ok(req.id, battery())
                "sensor" -> sensor(req)
                "location" -> location(req)
                "camera-photo" -> cameraPhoto(req)
                "clipboard-get" -> clipboardGet(req)
                "clipboard-set" -> clipboardSet(req)
                "notification" -> notify(req)
                "toast" -> toast(req)
                "vibrate" -> vibrate(req)
                "torch" -> torch(req)
                "tts-speak" -> tts(req)
                "volume" -> volume(req)
                "wifi-connectioninfo" -> wifi(req)
                "share" -> share(req)
                "open-url" -> openUrl(req)
                "telephony-call" -> dial(req)
                "usb" -> usb(req)
                "sms-send" -> smsSend(req)
                "sms-list" -> smsList(req)
                "contact-list" -> contacts(req)
                else -> PhoneResponse.error(req.id, "not implemented")
            }
        } catch (e: SecurityException) {
            PhoneResponse.error(req.id, e.message ?: "permission denied", needsPermission = e.message)
        } catch (e: Exception) {
            PhoneResponse.error(req.id, e.message ?: e.toString())
        }
    }

    private fun battery(): Map<String, Any?> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        return mapOf(
            "health" to healthName(health),
            "percentage" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "plugged" to when (plugged) {
                BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"
                else -> "UNPLUGGED"
            },
            "status" to when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                else -> "UNKNOWN"
            },
            "temperature" to temp,
            "current" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            "voltage" to voltage,
        )
    }

    private fun healthName(h: Int) = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
        else -> "UNKNOWN"
    }

    private fun sensor(req: PhoneRequest): PhoneResponse {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (req.argBool("list") || req.argString("sensor") == null) {
            val list = sm.getSensorList(Sensor.TYPE_ALL).map { s ->
                mapOf("name" to s.name, "type" to s.stringType, "vendor" to s.vendor, "maxRange" to s.maximumRange)
            }
            return PhoneResponse.ok(req.id, list)
        }
        val name = req.argString("sensor")!!
        val count = req.argInt("count", 1).coerceIn(1, 100)
        val found = sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.name == name || it.stringType == name }
            ?: return PhoneResponse.error(req.id, tr("сенсор не найден: $name", "sensor not found: $name"))
        val latch = CountDownLatch(count)
        val samples = mutableListOf<Map<String, Any?>>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(samples) {
                    if (samples.size >= count) return
                    samples += mapOf(
                        "name" to found.name,
                        "values" to event.values.map { it.toDouble() },
                        "accuracy" to event.accuracy,
                        "timestamp" to event.timestamp,
                    )
                    latch.countDown()
                    if (samples.size >= count) sm.unregisterListener(this)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, found, SensorManager.SENSOR_DELAY_NORMAL)
        val ok = latch.await(8, TimeUnit.SECONDS)
        sm.unregisterListener(listener)
        return if (ok || samples.isNotEmpty()) PhoneResponse.ok(req.id, samples)
        else PhoneResponse.error(req.id, tr("нет сэмплов с сенсора", "no samples from sensor"))
    }

    @SuppressLint("MissingPermission")
    private fun location(req: PhoneRequest): PhoneResponse {
        need(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc: Location? = if (Build.VERSION.SDK_INT >= 30) {
            lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } else {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }
        if (loc == null) return PhoneResponse.error(req.id, tr("нет текущей точки (включите GPS и откройте приложение)", "no last location (enable GPS and open the app)"))
        return PhoneResponse.ok(req.id, mapOf(
            "latitude" to loc.latitude, "longitude" to loc.longitude,
            "altitude" to loc.altitude, "accuracy" to loc.accuracy,
            "bearing" to loc.bearing, "speed" to loc.speed,
            "provider" to loc.provider, "time" to loc.time,
        ))
    }

    private fun cameraPhoto(req: PhoneRequest): PhoneResponse {
        need(Manifest.permission.CAMERA)
        if (!confirm.await(req, tr("Сделать фото с камеры?", "Take a photo with the camera?"))) {
            return PhoneResponse.error(req.id, tr("отменено пользователем", "cancelled by the user"), needsConfirm = true)
        }
        // Minimal v0.1: launch the system camera into a file under the host dir (visible in rootfs as /opencode-host).
        val dest = File(EmbeddedRuntime.hostDir(context), "photos").apply { mkdirs() }
        val file = File(dest, "photo-${System.currentTimeMillis()}.jpg")
        val launched = confirm.takePicture(file)
        return if (launched && file.isFile && file.length() > 0)
            PhoneResponse.ok(req.id, mapOf("file" to "/opencode-host/photos/${file.name}", "bytes" to file.length()))
        else PhoneResponse.error(req.id, tr("фото не получено", "no photo captured"))
    }

    private fun clipboardGet(req: PhoneRequest): PhoneResponse {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
        return PhoneResponse.ok(req.id, text)
    }

    private fun clipboardSet(req: PhoneRequest): PhoneResponse {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = req.text()
        Handler(Looper.getMainLooper()).post {
            cm.setPrimaryClip(ClipData.newPlainText("opencode", text))
        }
        return PhoneResponse.ok(req.id, mapOf("length" to text.length))
    }

    private fun notify(req: PhoneRequest): PhoneResponse {
        PhoneApiService.ensureChannel(context)
        val title = req.argString("title") ?: "OpenCode"
        val text = req.text()
        val n = NotificationCompat.Builder(context, PhoneApiService.NOTIFY_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 100000).toInt(), n)
        return PhoneResponse.ok(req.id, mapOf("posted" to true))
    }

    private fun toast(req: PhoneRequest): PhoneResponse {
        val text = req.text()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return PhoneResponse.ok(req.id, mapOf("shown" to true))
    }

    private fun vibrate(req: PhoneRequest): PhoneResponse {
        val ms = req.argInt("duration_ms", 200).coerceIn(1, 5_000).toLong()
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
        return PhoneResponse.ok(req.id, mapOf("ms" to ms))
    }

    private fun torch(req: PhoneRequest): PhoneResponse {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cam ->
            cm.getCameraCharacteristics(cam).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return PhoneResponse.error(req.id, tr("нет вспышки", "no flashlight"))
        val on = if (req.args.has("on")) req.argBool("on") else true
        cm.setTorchMode(id, on)
        return PhoneResponse.ok(req.id, mapOf("on" to on))
    }

    private fun tts(req: PhoneRequest): PhoneResponse {
        val text = req.text()
        if (text.isBlank()) return PhoneResponse.error(req.id, "empty text")
        val latch = CountDownLatch(1)
        var error: String? = null
        Handler(Looper.getMainLooper()).post {
            lateinit var tts: TextToSpeech
            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    error = "tts init failed"
                    latch.countDown()
                } else {
                    tts.language = Locale.getDefault()
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, req.id)
                    Handler(Looper.getMainLooper()).postDelayed({ runCatching { tts.shutdown() }; latch.countDown() }, 50)
                }
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        return if (error != null) PhoneResponse.error(req.id, error!!) else PhoneResponse.ok(req.id, mapOf("spoken" to true))
    }

    private fun volume(req: PhoneRequest): PhoneResponse {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (req.argString("stream")?.lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        val current = am.getStreamVolume(stream)
        if (req.args.has("volume")) {
            val v = req.argInt("volume", current).coerceIn(0, max)
            am.setStreamVolume(stream, v, 0)
            return PhoneResponse.ok(req.id, mapOf("stream" to streamName(stream), "volume" to v, "max" to max))
        }
        return PhoneResponse.ok(req.id, mapOf("stream" to streamName(stream), "volume" to current, "max" to max))
    }

    private fun streamName(s: Int) = when (s) {
        AudioManager.STREAM_RING -> "ring"
        AudioManager.STREAM_ALARM -> "alarm"
        AudioManager.STREAM_NOTIFICATION -> "notification"
        AudioManager.STREAM_VOICE_CALL -> "call"
        else -> "music"
    }

    @SuppressLint("MissingPermission")
    private fun wifi(req: PhoneRequest): PhoneResponse {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val ssid = info?.ssid?.trim('"')
        return PhoneResponse.ok(req.id, mapOf(
            "ssid" to ssid,
            "bssid" to info?.bssid,
            "rssi" to info?.rssi,
            "link_speed" to info?.linkSpeed,
            "ip" to info?.ipAddress,
            "hidden" to info?.hiddenSSID,
        ))
    }

    private fun share(req: PhoneRequest): PhoneResponse {
        val text = req.text()
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return PhoneResponse.ok(req.id, mapOf("shared" to true))
    }

    private fun openUrl(req: PhoneRequest): PhoneResponse {
        val raw = req.argString("url") ?: req.text()
        val uri = Uri.parse(raw)
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme !in setOf("http", "https")) {
            if (!confirm.await(req, tr("Открыть ссылку $raw ?", "Open $raw ?"))) {
                return PhoneResponse.error(req.id, tr("отменено", "cancelled"), needsConfirm = true)
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return PhoneResponse.ok(req.id, mapOf("opened" to raw))
    }

    private fun dial(req: PhoneRequest): PhoneResponse {
        val number = req.argString("number") ?: req.text()
        if (number.isBlank()) return PhoneResponse.error(req.id, "empty number")
        if (!confirm.await(req, tr("Открыть набор номера $number ?", "Open the dialer for $number ?"))) {
            return PhoneResponse.error(req.id, tr("отменено", "cancelled"), needsConfirm = true)
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it in "+#*" }))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return PhoneResponse.ok(req.id, mapOf("dialer" to true, "number" to number))
    }

    private fun usb(req: PhoneRequest): PhoneResponse {
        val sub = req.argString("sub") ?: "list"
        return when (sub) {
            "list", "l" -> PhoneResponse.ok(req.id, usb.list())
            "open" -> {
                val dev = req.argString("device") ?: return PhoneResponse.error(req.id, "device required")
                PhoneResponse.ok(req.id, usb.open(dev, req.argInt("baud", 9600)))
            }
            "write" -> PhoneResponse.ok(req.id, usb.write(req.text()))
            "read" -> PhoneResponse.ok(req.id, usb.read(req.argInt("timeout_ms", 1000)))
            "close" -> PhoneResponse.ok(req.id, usb.close())
            else -> PhoneResponse.error(req.id, "unknown usb subcommand $sub")
        }
    }

    private fun smsSend(req: PhoneRequest): PhoneResponse {
        need(Manifest.permission.SEND_SMS)
        val number = req.argString("number") ?: req.argString("to") ?: return PhoneResponse.error(req.id, "number required")
        val text = req.text()
        if (!confirm.await(req, tr("Отправить SMS на $number ?", "Send an SMS to $number ?"))) {
            return PhoneResponse.error(req.id, tr("отменено", "cancelled"), needsConfirm = true)
        }
        SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
        return PhoneResponse.ok(req.id, mapOf("sent" to true))
    }

    private fun smsList(req: PhoneRequest): PhoneResponse {
        need(Manifest.permission.READ_SMS)
        if (!confirm.await(req, tr("Показать входящие SMS?", "Show SMS inbox?"))) {
            return PhoneResponse.error(req.id, tr("отменено", "cancelled"), needsConfirm = true)
        }
        val out = JSONArray()
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE), null, null, Telephony.Sms.DEFAULT_SORT_ORDER)?.use { cur ->
            var n = 0
            while (cur.moveToNext() && n < 50) {
                out.put(JSONObject().put("address", cur.getString(0)).put("body", cur.getString(1)).put("date", cur.getLong(2)))
                n++
            }
        }
        return PhoneResponse.ok(req.id, out)
    }

    private fun contacts(req: PhoneRequest): PhoneResponse {
        need(Manifest.permission.READ_CONTACTS)
        if (!confirm.await(req, tr("Показать список контактов?", "Show the contact list?"))) {
            return PhoneResponse.error(req.id, tr("отменено", "cancelled"), needsConfirm = true)
        }
        val out = JSONArray()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)?.use { cur ->
            var n = 0
            while (cur.moveToNext() && n < 200) {
                out.put(JSONObject().put("name", cur.getString(0)).put("number", cur.getString(1)))
                n++
            }
        }
        return PhoneResponse.ok(req.id, out)
    }

    private fun need(permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            confirm.requestPermission(permission)
            throw SecurityException(permission)
        }
    }
}
