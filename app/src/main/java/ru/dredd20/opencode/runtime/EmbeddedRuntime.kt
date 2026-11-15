package ru.dredd20.opencode.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.dredd20.opencode.i18n.tr
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Embedded runtime: PRoot from the APK (nativeLibraryDir) + Ubuntu in filesDir/opencode/rootfs.
 * Termux is not required for this path.
 */
object EmbeddedRuntime {
    val SUPPORTED_ABIS = listOf("arm64-v8a", "x86_64")
    const val ROOTFS_MARKER = ".opencode-rootfs-ok"
    const val SETUP_MARKER = ".opencode-setup-ok"
    private const val MAX_OUTPUT = 256 * 1024

    fun abi(): String? = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }

    /** Devices that can install the APK but have no official OpenCode binary (e.g. armeabi-v7a). */
    fun isUnsupportedAbi(): Boolean = abi() == null

    fun baseDir(c: Context) = File(c.filesDir, "opencode")
    fun hostDir(c: Context) = File(baseDir(c), "host")
    fun rootfsDir(c: Context) = File(baseDir(c), "rootfs")
    fun logsDir(c: Context) = File(hostDir(c), "logs")
    fun cacheDir(c: Context) = File(baseDir(c), "dl")
    fun runDir(c: Context) = File(baseDir(c), "run")
    fun stateFile(c: Context) = File(hostDir(c), "serve-state.json")
    /** Survives Stop so the next Start can reuse the same port and password. */
    fun lastServeFile(c: Context) = File(hostDir(c), "last-serve.json")

    fun layout(c: Context): ProotLayout {
        val base = baseDir(c)
        return ProotLayout(
            nativeLibDir = c.applicationInfo.nativeLibraryDir,
            rootfs = File(base, "rootfs").path,
            hostDir = File(base, "host").path,
            tmpDir = File(base, "tmp").path,
            fakeProcDir = File(base, "fakeproc").path,
            runDir = File(base, "run").path,
        )
    }

    fun isSupported(c: Context): Boolean = abi() != null && File(layout(c).proot).canExecute()

    fun rootfsReady(c: Context): Boolean =
        File(rootfsDir(c), ROOTFS_MARKER).isFile && File(rootfsDir(c), "usr/bin/bash").exists()

    fun installed(c: Context): Boolean =
        rootfsReady(c) && File(rootfsDir(c), SETUP_MARKER).isFile && File(rootfsDir(c), "usr/local/bin/opencode").exists()

    /** Like MURF: PROOT_NO_SECCOMP only when host/noseccomp exists (set after a failed self-test). */
    fun noSeccomp(c: Context) = File(hostDir(c), "noseccomp").isFile

    fun noSeccompFlag(c: Context) = File(hostDir(c), "noseccomp")


    fun freeMb(c: Context): Long = runCatching { StatFs(c.filesDir.path).availableBytes / (1024 * 1024) }.getOrDefault(-1)

    fun prepare(c: Context): List<String> {
        val l = layout(c)
        File(l.tmpDir).mkdirs(); File(l.hostDir, "logs").mkdirs(); File(l.runDir).mkdirs()
        File(l.rootfs, "tmp").apply { mkdirs(); runCatching { TarExtractor.setMode(this, 0b111_111_111) } }
        File(l.rootfs, "dev/shm").mkdirs()
        writeResolvConf(c)
        val fp = File(l.fakeProcDir).apply { mkdirs() }
        val kernel = System.getProperty("os.version").orEmpty()
        return ProotCommand.FAKE_PROC.filter { f ->
            val readable = runCatching { File("/proc/$f").readText().isNotEmpty() }.getOrDefault(false)
            if (!readable) File(fp, f).writeText(ProotCommand.fakeProcContent(f, kernel))
            !readable
        }
    }

    fun writeResolvConf(c: Context) {
        val etc = File(rootfsDir(c), "etc")
        if (!etc.isDirectory) return
        val dns = runCatching {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        }.getOrDefault(emptyList())
        val f = File(etc, "resolv.conf")
        runCatching { java.nio.file.Files.deleteIfExists(f.toPath()) }
        f.writeText(ProotCommand.resolvConf(dns))
        val hosts = File(etc, "hosts")
        if (!hosts.isFile || !hosts.readText().contains("localhost")) hosts.appendText("127.0.0.1 localhost\n::1 localhost\n")
    }

    fun processBuilder(c: Context, guest: List<String>, extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        val l = layout(c)
        val fake = prepare(c)
        val pb = ProcessBuilder(ProotCommand.args(l, guest, fake))
        pb.environment().clear()
        pb.environment().putAll(ProotCommand.env(l, noSeccomp(c), File(l.loader32).exists(), extraEnv))
        pb.directory(File(l.hostDir))
        return pb
    }

    fun androidInfo(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"

    suspend fun exec(c: Context, guest: List<String>, timeoutMs: Long, extraEnv: Map<String, String> = emptyMap()): CommandResult = withContext(Dispatchers.IO) {
        if (!isSupported(c)) return@withContext CommandResult("", "", null, tr("Встроенная среда недоступна на ${Build.SUPPORTED_ABIS.joinToString()}", "Embedded runtime is not available on ${Build.SUPPORTED_ABIS.joinToString()}"))
        if (!rootfsReady(c)) return@withContext CommandResult("", "", null, tr("Ubuntu ещё не установлена", "Ubuntu is not installed yet"))
        val p = try { processBuilder(c, guest, extraEnv).start() } catch (e: Exception) {
            return@withContext CommandResult("", "", null, "proot: ${e.message}")
        }
        p.outputStream.close()
        val out = StringBuilder(); val err = StringBuilder()
        val t1 = Thread { drain(p.inputStream, out) }.apply { start() }
        val t2 = Thread { drain(p.errorStream, err) }.apply { start() }
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) { p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
        t1.join(2000); t2.join(2000)
        CommandResult(
            stdout = synchronized(out) { out.toString() },
            stderr = synchronized(err) { err.toString() },
            exitCode = if (done) p.exitValue() else null,
            error = if (done) null else "timeout ${timeoutMs / 1000} s",
        )
    }

    suspend fun bash(c: Context, command: String, timeoutMs: Long, extraEnv: Map<String, String> = emptyMap()): CommandResult =
        exec(c, ProotCommand.bash(command, extraEnv), timeoutMs, extraEnv)

    private fun drain(s: java.io.InputStream, sb: StringBuilder) {
        s.bufferedReader().use { r ->
            val buf = CharArray(8192)
            while (true) {
                val n = r.read(buf); if (n < 0) break
                synchronized(sb) { sb.append(buf, 0, n); if (sb.length > MAX_OUTPUT) sb.delete(0, sb.length - MAX_OUTPUT) }
            }
        }
    }

    fun writeProgress(c: Context, state: String, pct: Int, step: String, msg: String, log: Boolean = true) {
        val host = hostDir(c).apply { mkdirs() }
        val j = JSONObject().put("phase", "setup").put("state", state).put("pct", pct).put("step", step)
            .put("msg", msg).put("ts", System.currentTimeMillis() / 1000)
        val tmp = File(host, "progress.json.tmp")
        tmp.writeText(j.toString() + "\n")
        tmp.renameTo(File(host, "progress.json"))
        if (log) appendLog(c, "install.log", "[$state $pct%] $step: $msg")
    }

    fun readProgress(c: Context): JSONObject? = readJson(File(hostDir(c), "progress.json"))

    fun appendLog(c: Context, name: String, line: String) {
        runCatching {
            val f = File(logsDir(c).apply { mkdirs() }, name)
            if (f.length() > 2_000_000) f.renameTo(File(f.path + ".1"))
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("[$ts] $line\n")
        }
    }

    fun readLog(c: Context, name: String, maxChars: Int = 32_000): String =
        runCatching { File(logsDir(c), name).readText().takeLast(maxChars) }.getOrDefault("")

    fun readJson(f: File): JSONObject? = runCatching { JSONObject(f.readText()) }.getOrNull()

    fun deleteRootfs(c: Context) {
        deleteTree(rootfsDir(c))
        for (n in listOf("progress.json", "disk.txt", "serve-state.json")) File(hostDir(c), n).delete()
    }

    fun deleteTree(dir: File) {
        val root = dir.toPath()
        if (!java.nio.file.Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                runCatching { TarExtractor.setMode(dir.toFile(), 0b111_000_000) }
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                runCatching { java.nio.file.Files.delete(file) }
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException): java.nio.file.FileVisitResult {
                runCatching { java.nio.file.Files.delete(file) }
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                runCatching { java.nio.file.Files.delete(dir) }
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}

internal fun pidOf(p: Process): Int {
    var c: Class<*>? = p.javaClass
    while (c != null) {
        val k = c
        val v = runCatching { k.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }.getOrNull()
        if (v != null && v > 0) return v
        // API 31+ Process.pid()
        val m = runCatching { k.getMethod("pid").invoke(p) as? Long }.getOrNull()
        if (m != null && m > 0) return m.toInt()
        c = c.superclass
    }
    return Regex("pid=(\\d+)").find(p.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
}
