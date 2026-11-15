package ru.dredd20.opencode.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.dredd20.opencode.BuildConfig
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.prefs.AppPrefs
import ru.dredd20.opencode.runtime.EmbeddedRuntime
import ru.dredd20.opencode.runtime.RuntimeService
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-app updates from GitHub Releases (public URLs only, no tokens).
 *
 * 1. GET releases/latest/download/opencode-mobile-version.json (BuildConfig.UPDATE_MANIFEST_URL);
 *    on failure GET api.github.com/repos/…/releases/latest and take the manifest asset URL.
 * 2. Compare versionCode with the installed one.
 * 3. Download the APK with progress, check size + sha256, then package name, versionCode and
 *    that the signing certificate matches the installed app. Any mismatch deletes the file.
 * 4. Install through PackageInstaller (Android shows its own confirmation).
 */
object AppUpdater {
    private const val TAG = "OCM-update"
    private const val LOG = "update.log"

    sealed interface Phase {
        data object Idle : Phase
        data object Checking : Phase
        data class UpToDate(val remote: UpdateManifest.Info?) : Phase
        data class Available(val info: UpdateManifest.Info) : Phase
        data class Downloading(val info: UpdateManifest.Info, val progress: Float) : Phase
        /** Downloaded and fully verified; waiting for install permission or the user's tap. */
        data class Ready(val info: UpdateManifest.Info, val file: File) : Phase
        data class Installing(val info: UpdateManifest.Info) : Phase
        data class Failed(val message: String, val info: UpdateManifest.Info? = null) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase
    private val _dialog = MutableStateFlow(false)
    val dialog: StateFlow<Boolean> = _dialog
    /** Live "Install unknown apps" permission. Refreshed on every resume (MURF "Open settings" bug). */
    private val _canInstall = MutableStateFlow(false)
    val canInstall: StateFlow<Boolean> = _canInstall

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0)
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false) // never downgrade https -> http
            .build()
    }

    val currentCode: Int get() = BuildConfig.VERSION_CODE
    val usesTestServer: Boolean get() = BuildConfig.UPDATE_TEST_SERVER

    fun canInstallPackages(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.packageManager.canRequestPackageInstalls()

    /** Call from Activity.onResume: re-read the permission so the dialog switches to "Install". */
    fun onResume(c: Context) {
        _canInstall.value = canInstallPackages(c)
        val p = _phase.value
        if (p is Phase.Ready) {
            val app = c.applicationContext
            scope.launch {
                if (!readyFileValid(p)) {
                    log(app, "resume: cached update file is gone or changed")
                    _phase.compareAndSet(p, Phase.Failed(tr("Файл обновления пропал, скачайте ещё раз.", "The update file is gone, download it again."), p.info))
                } else if (_canInstall.value) log(app, "resume: install permission granted, ready to install ${p.info.versionName}")
            }
        }
    }

    private fun readyFileValid(p: Phase.Ready): Boolean = runCatching {
        p.file.isFile && (p.info.size <= 0 || p.file.length() == p.info.size) &&
            UpdateManifest.sameDigest(p.file.inputStream().use { UpdateManifest.sha256Hex(it) }, p.info.sha256)
    }.getOrDefault(false)

    fun openDialog() { _dialog.value = true }

    fun dismiss(c: Context) {
        val p = _phase.value
        if (p is Phase.Downloading || p is Phase.Installing) return
        _dialog.value = false
        val info = infoOf(p)
        if (info != null) AppPrefs.setUpdatePostponedCode(c, info.versionCode)
        if (p is Phase.UpToDate || p is Phase.Failed) _phase.value = Phase.Idle
    }

    fun infoOf(p: Phase): UpdateManifest.Info? = when (p) {
        is Phase.Available -> p.info
        is Phase.Downloading -> p.info
        is Phase.Ready -> p.info
        is Phase.Installing -> p.info
        is Phase.Failed -> p.info
        is Phase.UpToDate -> null
        else -> null
    }

    /** Startup check: respects the switch and the 6 h throttle, silent on errors. */
    fun autoCheck(c: Context) {
        val app = c.applicationContext
        _canInstall.value = canInstallPackages(app)
        if (!UpdateManifest.shouldAutoCheck(AppPrefs.updateAutoCheck(app), System.currentTimeMillis(), AppPrefs.updateLastCheck(app))) return
        check(app, auto = true)
    }

    /** Manual check from settings: always runs and always shows the dialog with the result. */
    fun checkNow(c: Context) {
        // Ready: the dialog opens only after the cached file is re-verified (run4 item 1).
        if (_phase.value !is Phase.Ready) _dialog.value = true
        check(c.applicationContext, auto = false)
    }

    private fun check(app: Context, auto: Boolean) {
        val busy = _phase.value
        if (busy is Phase.Ready) {
            // run4 item 1: only say "File verified" if the file is still there and its sha256 still matches.
            scope.launch {
                if (readyFileValid(busy)) {
                    if (!auto) _dialog.value = true
                } else {
                    log(app, "cached update file is gone or changed, checking again")
                    if (_phase.compareAndSet(busy, Phase.Idle)) check(app, auto)
                }
            }
            return
        }
        if (busy is Phase.Downloading || busy is Phase.Installing) {
            if (!auto) _dialog.value = true
            return
        }
        val gen = generation.incrementAndGet()
        _phase.value = Phase.Checking
        scope.launch {
            val result = runCatching { fetchManifest(app) }
            if (gen != generation.get()) return@launch
            AppPrefs.setUpdateLastCheck(app, System.currentTimeMillis())
            result.onFailure { t ->
                log(app, "check failed: ${t.message}")
                _phase.value = if (auto) Phase.Idle else Phase.Failed(tr("Не удалось проверить обновления: ", "Could not check for updates: ") + (t.message ?: t.javaClass.simpleName))
            }.onSuccess { info ->
                log(app, "check ok: remote ${info.versionName} (${info.versionCode}), installed ${BuildConfig.VERSION_NAME} ($currentCode)")
                if (info.minSdk > Build.VERSION.SDK_INT) {
                    _phase.value = if (auto) Phase.Idle else Phase.Failed(tr("Новая версия ${info.versionName} требует Android API ${info.minSdk}.", "Version ${info.versionName} needs Android API ${info.minSdk}."))
                    return@onSuccess
                }
                if (!UpdateManifest.isNewer(info, currentCode)) {
                    _phase.value = if (auto) Phase.Idle else Phase.UpToDate(info)
                    return@onSuccess
                }
                _phase.value = Phase.Available(info)
                if (UpdateManifest.shouldPrompt(info, currentCode, AppPrefs.updatePostponedCode(app), auto)) _dialog.value = true
                else if (auto) _phase.value = Phase.Idle
            }
        }
    }

    private fun get(url: String, accept: String? = null): okhttp3.Response {
        val b = Request.Builder().url(url)
            .header("User-Agent", "OpenCodeMobile/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .cacheControl(CacheControl.FORCE_NETWORK)
        if (accept != null) b.header("Accept", accept)
        return http.newCall(b.build()).execute()
    }

    private fun readSmall(url: String, accept: String? = null): String = get(url, accept).use { r ->
        if (r.code == 404) throw IOException(tr("HTTP 404: релиз или файл версии не найден (репозиторий ещё не опубликован?)", "HTTP 404: release or version file not found (repository not published yet?)"))
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        val body = r.body
        if (body.contentLength() > UpdateManifest.MAX_MANIFEST_BYTES) throw IOException("response too large")
        val src = body.source()
        src.request(UpdateManifest.MAX_MANIFEST_BYTES + 1L)
        if (src.buffer.size > UpdateManifest.MAX_MANIFEST_BYTES) throw IOException("response too large")
        src.buffer.readUtf8()
    }

    private fun fetchManifest(app: Context): UpdateManifest.Info {
        val prefix = BuildConfig.UPDATE_ALLOWED_PREFIX
        val direct = runCatching { readSmall(BuildConfig.UPDATE_MANIFEST_URL) }
        direct.getOrNull()?.let { text ->
            log(app, "manifest via releases/latest/download")
            return UpdateManifest.parse(text, prefix) ?: throw IllegalStateException(tr("файл версии на сервере некорректен", "the version file on the server is invalid"))
        }
        log(app, "direct manifest failed (${direct.exceptionOrNull()?.message}), trying GitHub API")
        val api = runCatching { readSmall(BuildConfig.UPDATE_API_URL, "application/vnd.github+json") }
        val apiJson = api.getOrElse { throw direct.exceptionOrNull() ?: it }
        val url = UpdateManifest.manifestUrlFromApi(apiJson, prefix)
            ?: throw IllegalStateException(tr("в последнем релизе нет ${UpdateManifest.MANIFEST_NAME}", "the latest release has no ${UpdateManifest.MANIFEST_NAME}"))
        log(app, "manifest via GitHub API asset")
        return UpdateManifest.parse(readSmall(url), prefix)
            ?: throw IllegalStateException(tr("файл версии на сервере некорректен", "the version file on the server is invalid"))
    }

    fun download(c: Context, info: UpdateManifest.Info) {
        val app = c.applicationContext
        val p = _phase.value
        if (p is Phase.Downloading || p is Phase.Installing) return
        _dialog.value = true
        _phase.value = Phase.Downloading(info, 0f)
        scope.launch {
            val r = runCatching { downloadVerified(app, info) }
            r.onFailure { t ->
                log(app, "download/verify failed: ${t.javaClass.simpleName}: ${t.message}")
                val why = t.message ?: t.javaClass.simpleName
                _phase.value = Phase.Failed(
                    if (UpdateDownload.isNetworkError(t)) tr("Загрузка не удалась: $why. «Повторить» продолжит с места обрыва.", "Download failed: $why. Retry resumes where it stopped.")
                    else tr("Обновление отклонено: ", "Update rejected: ") + why,
                    info,
                )
            }.onSuccess { file ->
                log(app, "verified ${file.name}: sha256, size, package, versionCode, signer OK")
                _canInstall.value = canInstallPackages(app)
                _phase.value = Phase.Ready(info, file)
                if (_canInstall.value) install(app)
            }
        }
    }

    private fun updateDir(c: Context) = File(c.cacheDir, "app-update")

    private fun verifyFail(msg: String): Nothing = throw UpdateDownload.VerifyException(msg)

    /**
     * Download into a .part file and resume it with `Range` after a network drop (run4 item 3).
     * sha256 covers the whole file (the existing part is hashed first). Verification failures delete
     * the part; network errors keep it for Retry.
     */
    private fun downloadVerified(app: Context, info: UpdateManifest.Info): File {
        val dir = updateDir(app).apply { mkdirs() }
        val part = File(dir, UpdateDownload.partName(info.versionCode, info.sha256))
        val file = File(dir, UpdateDownload.apkName(info.versionCode))
        dir.listFiles()?.forEach { if (it.name != part.name) it.delete() }
        if (!UpdateManifest.isAllowedUrl(info.url, BuildConfig.UPDATE_ALLOWED_PREFIX)) verifyFail(tr("адрес APK вне репозитория обновлений", "APK URL is outside the update repository"))
        try {
            val start = UpdateDownload.rangeStart(part.length(), info.size)
            if (start == null) part.delete()
            val b = Request.Builder().url(info.url)
                .header("User-Agent", "OpenCodeMobile/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .cacheControl(CacheControl.FORCE_NETWORK)
            if (start != null) b.header("Range", "bytes=$start-").header("Accept-Encoding", "identity")
            http.newCall(b.build()).execute().use { r ->
                var next = UpdateDownload.next(r.code, start, info.size, part.length())
                if (next == UpdateDownload.Next.APPEND && UpdateDownload.contentRangeStart(r.header("Content-Range")) != start) next = UpdateDownload.Next.RESTART
                if (next != UpdateDownload.Next.ALREADY_COMPLETE && !r.isSuccessful) {
                    if (r.code == 416) part.delete()
                    throw IOException("HTTP ${r.code}")
                }
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                when (next) {
                    UpdateDownload.Next.RESTART -> part.delete()
                    else -> {
                        part.inputStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); done += n }
                        }
                        log(app, if (next == UpdateDownload.Next.APPEND) "resuming download at $done bytes" else "part file already complete ($done bytes)")
                    }
                }
                if (next != UpdateDownload.Next.ALREADY_COMPLETE) {
                    val total = info.size.takeIf { it > 0 } ?: (r.body.contentLength().takeIf { it > 0 }?.plus(done) ?: -1L)
                    var lastEmit = 0L
                    r.body.byteStream().use { input ->
                        java.io.FileOutputStream(part, next == UpdateDownload.Next.APPEND).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                done += n
                                if (done > UpdateManifest.MAX_APK_BYTES || (info.size > 0 && done > info.size)) {
                                    part.delete(); verifyFail(tr("файл больше заявленного", "file is larger than declared"))
                                }
                                if (total > 0 && done - lastEmit >= 128 * 1024) {
                                    lastEmit = done
                                    _phase.value = Phase.Downloading(info, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
                _phase.value = Phase.Downloading(info, 1f)
                if (info.size > 0 && done != info.size) {
                    if (done < info.size) throw IOException(tr("соединение оборвалось", "connection closed early"))
                    part.delete(); verifyFail(tr("размер не совпадает", "size mismatch"))
                }
                val sha = UpdateManifest.hex(md.digest())
                if (!UpdateManifest.sameDigest(sha, info.sha256)) {
                    part.delete(); verifyFail(tr("sha256 не совпадает, файл повреждён или подменён", "sha256 mismatch, the file is damaged or tampered with"))
                }
            }
            if (!part.renameTo(file)) { part.copyTo(file, overwrite = true); part.delete() }
            try {
                verifyApk(app, file, info)
            } catch (t: Throwable) {
                file.delete()
                throw if (t is IllegalStateException) UpdateDownload.VerifyException(t.message ?: "verify failed") else t
            }
            return file
        } catch (t: UpdateDownload.VerifyException) {
            part.delete(); file.delete()
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(c: Context, file: File, info: UpdateManifest.Info) {
        val pm = c.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: error(tr("это не APK", "not an APK"))
        if (archive.packageName != c.packageName) error(tr("APK другого приложения (${archive.packageName})", "APK of another app (${archive.packageName})"))
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode.toInt() else archive.versionCode
        if (code != info.versionCode || code <= currentCode) error(tr("versionCode APK ($code) не совпадает с манифестом", "APK versionCode ($code) does not match the manifest"))
        val installed = certDigests(pm.getPackageInfo(c.packageName, flags))
            .ifEmpty { certDigests(pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES)) }
        var fromApk = certDigests(archive)
        if (fromApk.isEmpty()) fromApk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)?.let { certDigests(it) }.orEmpty()
        log(c, "signers apk=${fromApk.map { it.take(8) }} installed=${installed.map { it.take(8) }}")
        if (fromApk.isEmpty() || installed.isEmpty()) error(tr("не удалось прочитать сертификаты подписи", "could not read signing certificates"))
        if (!UpdateManifest.signersMatch(fromApk, installed)) error(tr("APK подписан другим ключом", "APK is signed with a different key"))
    }

    @Suppress("DEPRECATION")
    private fun certDigests(pi: PackageInfo): Set<String> {
        val sigs = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else null)?.takeIf { it.isNotEmpty() } ?: pi.signatures
        return sigs.orEmpty().map { UpdateManifest.sha256Hex(it.toByteArray()) }.toSet()
    }

    fun openInstallPermissionSettings(c: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { c.startActivity(i) }.onFailure {
            runCatching { c.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Main dialog button in Ready: install when allowed, otherwise open the permission page. */
    fun onReadyAction(c: Context) {
        val app = c.applicationContext
        _canInstall.value = canInstallPackages(app)
        when (UpdateManifest.readyAction(_canInstall.value)) {
            UpdateManifest.ReadyAction.INSTALL -> install(app)
            UpdateManifest.ReadyAction.OPEN_SETTINGS -> openInstallPermissionSettings(c)
        }
    }

    private fun install(app: Context) {
        val ready = _phase.value as? Phase.Ready ?: return
        if (!ready.file.isFile) {
            _phase.value = Phase.Failed(tr("Файл обновления пропал, скачайте ещё раз.", "The update file is gone, download it again."), ready.info)
            return
        }
        _phase.value = Phase.Installing(ready.info)
        scope.launch {
            runCatching {
                // The update replaces the running process; stop the local server cleanly first.
                if (RuntimeService.running) {
                    log(app, "stopping local server before install")
                    RuntimeService.send(app, RuntimeService.ACTION_STOP)
                    var waited = 0
                    while (RuntimeService.running && waited < 10_000) { delay(250); waited += 250 }
                }
                // Re-hash right before install: the file sits in our private cache, but be strict.
                val sha = ready.file.inputStream().use { UpdateManifest.sha256Hex(it) }
                if (!UpdateManifest.sameDigest(sha, ready.info.sha256)) error(tr("sha256 изменился перед установкой", "sha256 changed before install"))
                commitSession(app, ready.file)
                log(app, "session committed for ${ready.info.versionName}")
            }.onFailure { t ->
                log(app, "install failed: ${t.message}")
                _phase.value = Phase.Failed(tr("Не удалось начать установку: ", "Could not start installation: ") + (t.message ?: ""), ready.info)
            }
        }
    }

    private fun commitSession(app: Context, file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("opencode-mobile.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(app, UpdateInstallReceiver::class.java).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
        }
    }

    internal fun onInstallStatus(c: Context, status: Int, message: String?, confirm: Intent?) {
        log(c, "install status=$status ${message.orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { c.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                _phase.value = Phase.Idle
                _dialog.value = false
                updateDir(c).listFiles()?.forEach { it.delete() }
            }
            else -> {
                val info = (_phase.value as? Phase.Installing)?.info
                val file = info?.let { File(updateDir(c), "opencode-mobile-${it.versionCode}.apk") }
                // Keep the verified file so "Install" can be retried without downloading again.
                _phase.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED && info != null && file?.isFile == true) {
                    Phase.Ready(info, file)
                } else {
                    Phase.Failed(tr("Android не установил обновление: ", "Android did not install the update: ") + (message ?: status.toString()), info)
                }
            }
        }
    }

    private fun log(c: Context, line: String) {
        Log.i(TAG, line)
        runCatching { EmbeddedRuntime.appendLog(c, LOG, line) }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        AppUpdater.onInstallStatus(context, status, msg, confirm)
    }
}
