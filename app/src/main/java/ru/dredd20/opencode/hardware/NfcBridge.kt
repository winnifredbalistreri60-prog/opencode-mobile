package ru.dredd20.opencode.hardware

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.os.Build
import ru.dredd20.opencode.MainActivity
import ru.dredd20.opencode.i18n.tr
import java.lang.ref.WeakReference
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * NFC only works in the foreground via [NfcAdapter.enableReaderMode].
 * Phone API calls wait here; [MainActivity] must call [onResume]/[onPause].
 */
object NfcBridge {
    enum class Op { INFO, READ, WRITE, TRANSCEIVE }

    data class Request(
        val op: Op,
        val timeoutMs: Int,
        val writeMessage: NdefMessage? = null,
        val transceive: ByteArray? = null,
        val tech: String? = null,
    )

    private val lock = Any()
    private var activityRef: WeakReference<Activity>? = null
    private var pending: CountDownLatch? = null
    private var request: Request? = null
    private var result: Map<String, Any?>? = null
    private var error: String? = null

    @Volatile var waiting: Boolean = false
        private set

    fun onResume(activity: Activity) {
        synchronized(lock) { activityRef = WeakReference(activity) }
        if (waiting) enable(activity)
    }

    fun onPause(activity: Activity) {
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
        synchronized(lock) {
            if (activityRef?.get() === activity) activityRef = null
        }
    }

    fun await(context: android.content.Context, req: Request): Map<String, Any?> {
        val adapter = NfcAdapter.getDefaultAdapter(context)
            ?: error(tr("NFC на этом устройстве нет", "NFC is not available on this device"))
        if (!adapter.isEnabled) error(tr("NFC выключен", "NFC is turned off"))
        val latch = CountDownLatch(1)
        synchronized(lock) {
            if (waiting) error(tr("уже идёт ожидание NFC-метки", "an NFC wait is already in progress"))
            waiting = true
            pending = latch
            request = req
            result = null
            error = null
        }
        val act = synchronized(lock) { activityRef?.get() }
        if (act != null) {
            act.runOnUiThread { enable(act) }
        } else {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra("nfc_wait", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        val ok = try {
            latch.await(req.timeoutMs.coerceIn(1, 90_000).toLong(), TimeUnit.MILLISECONDS)
        } finally {
            val a = synchronized(lock) { activityRef?.get() }
            if (a != null) a.runOnUiThread { runCatching { NfcAdapter.getDefaultAdapter(a)?.disableReaderMode(a) } }
            synchronized(lock) {
                waiting = false
                pending = null
                request = null
            }
        }
        error?.let { throw IllegalStateException(it) }
        if (!ok) error(tr(
            "Метка не поднесена за ${req.timeoutMs} мс. Команда работает только когда приложение на экране.",
            "No tag was presented within ${req.timeoutMs} ms. This command only works while the app is in the foreground.",
        ))
        return result ?: error(tr("метка не прочитана", "tag was not read"))
    }

    fun onTag(tag: Tag) {
        val req = synchronized(lock) { request } ?: return
        try {
            val data = when (req.op) {
                Op.INFO -> info(tag)
                Op.READ -> info(tag) + mapOf("ndef" to ndefRecords(tag))
                Op.WRITE -> write(tag, req.writeMessage ?: error("NDEF message required"))
                Op.TRANSCEIVE -> transceive(tag, req.transceive ?: error("payload required"), req.tech)
            }
            synchronized(lock) { result = data; error = null }
        } catch (e: Exception) {
            synchronized(lock) { error = e.message ?: e.toString() }
        } finally {
            pending?.countDown()
        }
    }

    private fun enable(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        val req = synchronized(lock) { request }
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            (if (req?.op == Op.TRANSCEIVE) NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK else 0)
        adapter.enableReaderMode(activity, { tag -> onTag(tag) }, flags, null)
    }

    private fun info(tag: Tag): Map<String, Any?> = mapOf(
        "id" to HardwareArgs.formatHex(tag.id ?: ByteArray(0)),
        "tech" to (tag.techList?.toList() ?: emptyList()),
        "atqa" to runCatching { NfcA.get(tag)?.atqa?.let { HardwareArgs.formatHex(it) } }.getOrNull(),
        "sak" to runCatching { NfcA.get(tag)?.sak?.toInt()?.and(0xff) }.getOrNull(),
    )

    private fun ndefRecords(tag: Tag): List<Map<String, Any?>> {
        val ndef = Ndef.get(tag) ?: return emptyList()
        ndef.connect()
        return try {
            val msg = ndef.ndefMessage ?: return emptyList()
            msg.records.map { rec -> describe(rec) }
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun write(tag: Tag, message: NdefMessage): Map<String, Any?> {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) error(tr("метка только для чтения", "tag is read-only"))
                if (ndef.maxSize < message.byteArrayLength) {
                    error(tr("сообщение больше метки (${message.byteArrayLength} > ${ndef.maxSize})", "message is larger than the tag (${message.byteArrayLength} > ${ndef.maxSize})"))
                }
                ndef.writeNdefMessage(message)
            } finally {
                runCatching { ndef.close() }
            }
            return mapOf("written" to true, "bytes" to message.byteArrayLength) + info(tag)
        }
        val fmt = NdefFormatable.get(tag) ?: error(tr("метка не поддерживает NDEF", "tag does not support NDEF"))
        fmt.connect()
        try {
            fmt.format(message)
        } finally {
            runCatching { fmt.close() }
        }
        return mapOf("written" to true, "formatted" to true, "bytes" to message.byteArrayLength) + info(tag)
    }

    private fun transceive(tag: Tag, payload: ByteArray, tech: String?): Map<String, Any?> {
        val want = tech?.lowercase()
        if (want == "isodep" || (want == null && tag.techList.contains(IsoDep::class.java.name))) {
            val iso = IsoDep.get(tag) ?: error("ISO-DEP is not available on this tag")
            iso.connect()
            return try {
                iso.timeout = 5_000
                val out = iso.transceive(payload)
                mapOf("tech" to "IsoDep", "hex" to HardwareArgs.formatHex(out), "bytes" to out.size, "text" to HardwareArgs.utf8(out)) + info(tag)
            } finally {
                runCatching { iso.close() }
            }
        }
        val nfcA = NfcA.get(tag) ?: error(tr("ISO-DEP/NfcA на этой метке нет", "ISO-DEP/NfcA is not available on this tag"))
        nfcA.connect()
        return try {
            nfcA.timeout = 5_000
            val out = nfcA.transceive(payload)
            mapOf("tech" to "NfcA", "hex" to HardwareArgs.formatHex(out), "bytes" to out.size, "text" to HardwareArgs.utf8(out)) + info(tag)
        } finally {
            runCatching { nfcA.close() }
        }
    }

    fun describe(rec: NdefRecord): Map<String, Any?> {
        val tnf = rec.tnf.toInt()
        val type = String(rec.type, Charset.forName("US-ASCII"))
        val payload = rec.payload ?: ByteArray(0)
        val text = when {
            tnf == NdefRecord.TNF_WELL_KNOWN.toInt() && rec.type.contentEquals(NdefRecord.RTD_TEXT) -> decodeText(payload)
            tnf == NdefRecord.TNF_WELL_KNOWN.toInt() && rec.type.contentEquals(NdefRecord.RTD_URI) -> decodeUri(payload)
            else -> HardwareArgs.utf8(payload)
        }
        return mapOf(
            "tnf" to tnf,
            "type" to type,
            "hex" to HardwareArgs.formatHex(payload),
            "text" to text,
        )
    }

    fun textRecord(text: String, lang: String = "en"): NdefRecord {
        val langBytes = lang.toByteArray(Charset.forName("US-ASCII"))
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + langBytes.size + textBytes.size)
        payload[0] = langBytes.size.toByte()
        System.arraycopy(langBytes, 0, payload, 1, langBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.size, textBytes.size)
        return NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), payload)
    }

    fun uriRecord(uri: String): NdefRecord =
        if (Build.VERSION.SDK_INT >= 16) NdefRecord.createUri(uri)
        else NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_URI, ByteArray(0), byteArrayOf(0) + uri.toByteArray(Charsets.UTF_8))

    fun messageFrom(text: String?, uri: String?, hex: String?): NdefMessage {
        val records = mutableListOf<NdefRecord>()
        if (!uri.isNullOrBlank()) records += uriRecord(uri)
        if (!text.isNullOrBlank()) records += textRecord(text)
        if (!hex.isNullOrBlank()) {
            records += NdefRecord(NdefRecord.TNF_UNKNOWN, ByteArray(0), ByteArray(0), HardwareArgs.parseHex(hex))
        }
        if (records.isEmpty()) error("NDEF payload required (text, uri or hex)")
        return NdefMessage(records.toTypedArray())
    }

    private fun decodeText(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val langLen = payload[0].toInt() and 0x3F
        val start = 1 + langLen
        if (start > payload.size) return HardwareArgs.utf8(payload)
        return String(payload, start, payload.size - start, Charsets.UTF_8)
    }

    private fun decodeUri(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val prefix = URI_PREFIX.getOrElse(payload[0].toInt() and 0xff) { "" }
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    private val URI_PREFIX = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://",
        "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:",
        "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )
}
