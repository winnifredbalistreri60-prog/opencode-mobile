package ru.dredd20.opencode.runtime

import android.content.Context
import okhttp3.OkHttpClient
import ru.dredd20.opencode.i18n.tr
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class SetupException(msg: String) : Exception(msg)

/**
 * First-run (or repair) setup: ubuntu-base 24.04 → official OpenCode CLI → apt packages
 * → Phone API wrappers / phone-mcp → opencode.json. Progress is written to host/progress.json.
 */
class SetupPipeline(
    private val context: Context,
    private val ubuntuBaseUrl: String = UbuntuBase.DEFAULT_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(true).build(),
    private val updateCliOnly: Boolean = false,
) {
    private fun progress(state: String, pct: Int, step: String, msg: String, log: Boolean = true) =
        EmbeddedRuntime.writeProgress(context, state, pct, step, msg, log)

    fun run(cancelled: () -> Boolean = { false }) {
        val c = context
        val abi = EmbeddedRuntime.abi() ?: fail(1, "abi", tr("Нет официального OpenCode для этого процессора. Используйте удалённый сервер.", "No official OpenCode binary for this CPU. Use a remote server."))
        val arch = UbuntuBase.archFor(abi) ?: fail(1, "abi", tr("ABI $abi не поддерживается", "ABI $abi is not supported"))
        val artifact = OpenCodeRelease.forAbi(abi) ?: fail(1, "abi", tr("Нет сборки OpenCode для $abi", "No OpenCode build for $abi"))
        val free = EmbeddedRuntime.freeMb(c)
        if (free in 0 until MinFreeSpace.MEGABYTES) {
            fail(0, "space", tr("Мало места: $free МБ, нужно ≥ ${MinFreeSpace.MEGABYTES} МБ", "Not enough space: $free MB, need ≥ ${MinFreeSpace.MEGABYTES} MB"))
        }

        if (!updateCliOnly) {
            installUbuntu(c, arch, cancelled)
        } else if (!EmbeddedRuntime.rootfsReady(c)) {
            fail(1, "rootfs", tr("Сначала установите среду", "Install the environment first"))
        }

        selfTestProot(c)
        installOpenCode(c, artifact, cancelled, force = updateCliOnly)
        if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
        installAptAndHelpers(c, cancelled)
        writeConfig(c)
        File(EmbeddedRuntime.rootfsDir(c), EmbeddedRuntime.SETUP_MARKER).writeText(OpenCodeRelease.VERSION + "\n")
        progress("done", 100, "done", tr("Готово: OpenCode ${OpenCodeRelease.VERSION}", "Ready: OpenCode ${OpenCodeRelease.VERSION}"))
    }

    private fun installUbuntu(c: Context, arch: String, cancelled: () -> Boolean) {
        if (EmbeddedRuntime.rootfsReady(c)) {
            progress("running", 15, "rootfs", tr("Ubuntu уже распакована", "Ubuntu is already unpacked"))
            return
        }
        progress("running", 2, "rootfs", tr("Список образов Ubuntu (cdimage.ubuntu.com)", "Ubuntu image list (cdimage.ubuntu.com)"))
        val sums = try {
            client.newCall(okhttp3.Request.Builder().url("$ubuntuBaseUrl/SHA256SUMS").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}"); r.body.string()
            }
        } catch (e: Exception) {
            fail(2, "rootfs", tr("cdimage.ubuntu.com недоступен: ${e.message}", "cdimage.ubuntu.com is unreachable: ${e.message}"))
        }
        val img = UbuntuBase.pick(sums, arch) ?: fail(2, "rootfs", tr("не найден ubuntu-base $arch", "ubuntu-base $arch not found"))
        val tarball = File(EmbeddedRuntime.cacheDir(c), img.name)
        download("ubuntu", tarball, "$ubuntuBaseUrl/${img.name}", img.sha256, 3, 25, cancelled)
        progress("running", 26, "rootfs", tr("Распаковка Ubuntu (${img.name})", "Unpacking Ubuntu (${img.name})"))
        val root = EmbeddedRuntime.rootfsDir(c)
        if (root.exists()) EmbeddedRuntime.deleteRootfs(c)
        root.mkdirs()
        val total = tarball.length().coerceAtLeast(1)
        var lastPct = -1
        try {
            CountingStream(tarball.inputStream().buffered(256 * 1024)).use { counting ->
                val st = TarExtractor(root) { it == "dev" || it.startsWith("dev/") }.extract(GZIPInputStream(counting, 64 * 1024)) {
                    if (cancelled()) throw java.util.concurrent.CancellationException("unpack cancelled")
                    val p = (counting.count * 100 / total).toInt()
                    if (p != lastPct && p % 5 == 0) {
                        lastPct = p
                        progress("running", 26 + (p * 10 / 100), "rootfs", tr("Распаковка Ubuntu: $p%", "Unpacking Ubuntu: $p%"), p % 25 == 0)
                    }
                }
                EmbeddedRuntime.appendLog(c, "install.log", "unpacked: $st")
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            EmbeddedRuntime.deleteRootfs(c)
            fail(30, "rootfs", tr("не удалось распаковать Ubuntu: ${e.message}", "cannot unpack Ubuntu: ${e.message}"))
        }
        if (!File(root, "usr/bin/bash").exists()) fail(30, "rootfs", tr("после распаковки нет /usr/bin/bash", "no /usr/bin/bash after unpacking"))
        File(root, "dev").mkdirs(); File(root, "tmp").mkdirs(); File(root, "root").mkdirs(); File(root, "run").mkdirs()
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/apt/apt.conf.d/99opencode-proot").writeText("APT::Sandbox::User \"root\";\n")
        File(root, EmbeddedRuntime.ROOTFS_MARKER).writeText(img.name + "\n")
        EmbeddedRuntime.writeResolvConf(c)
        progress("running", 38, "rootfs", tr("Ubuntu распакована (${img.name})", "Ubuntu unpacked (${img.name})"))
    }

    /**
     * First proot launch, same as MURF: try with seccomp, and only then write host/noseccomp
     * and retry. Never set PROOT_NO_SECCOMP unconditionally.
     */
    private fun selfTestProot(c: Context) {
        progress("running", 39, "proot", tr("Проверка proot", "Checking proot"))
        val flag = EmbeddedRuntime.noSeccompFlag(c)
        flag.delete()
        var r = kotlinx.coroutines.runBlocking {
            EmbeddedRuntime.exec(c, listOf("/bin/sh", "-c", "uname -a; test -w /tmp"), 60_000)
        }
        if (!r.ok) {
            EmbeddedRuntime.appendLog(c, "install.log", "proot self-test failed: ${r.combined().take(500)}; retry PROOT_NO_SECCOMP=1")
            flag.parentFile?.mkdirs()
            flag.writeText("1\n")
            r = kotlinx.coroutines.runBlocking {
                EmbeddedRuntime.exec(c, listOf("/bin/sh", "-c", "uname -a; test -w /tmp"), 60_000)
            }
            if (!r.ok) {
                flag.delete()
                fail(39, "proot", tr("proot не запускается: ${r.combined().take(300)}", "proot does not start: ${r.combined().take(300)}"))
            }
        }
        EmbeddedRuntime.appendLog(c, "install.log", "proot ok: " + r.stdout.trim().replace('\n', ' '))
    }

    private fun installOpenCode(c: Context, artifact: OpenCodeRelease.Artifact, cancelled: () -> Boolean, force: Boolean = false) {
        val destDir = File(EmbeddedRuntime.rootfsDir(c), "usr/local/bin")
        val dest = File(destDir, "opencode")
        val versionMarker = File(EmbeddedRuntime.rootfsDir(c), SetupSkip.CLI_VERSION_MARKER)
        val installedVer = versionMarker.takeIf { it.isFile }?.readText()?.trim()
        val already = SetupSkip.shouldSkipOpenCodeInstall(
            binaryExists = dest.isFile && dest.canExecute(),
            installedVersion = installedVer,
            wantedVersion = OpenCodeRelease.VERSION,
            force = force,
        )
        if (already) {
            progress("running", 76, "cli", tr("OpenCode ${OpenCodeRelease.VERSION} уже установлен, загрузка пропущена", "OpenCode ${OpenCodeRelease.VERSION} already installed, skipping download"))
            return
        }
        progress("running", 40, "cli", tr("Загрузка OpenCode ${OpenCodeRelease.VERSION} (${artifact.name})", "Downloading OpenCode ${OpenCodeRelease.VERSION} (${artifact.name})"))
        val tarball = File(EmbeddedRuntime.cacheDir(c), artifact.name)
        download("cli", tarball, OpenCodeRelease.url(artifact), artifact.sha256, 40, 70, cancelled)
        progress("running", 72, "cli", tr("Установка бинарника OpenCode", "Installing the OpenCode binary"))
        destDir.mkdirs()
        val extractTo = File(EmbeddedRuntime.cacheDir(c), "cli-unpack").apply { if (exists()) EmbeddedRuntime.deleteTree(this); mkdirs() }
        try {
            TarExtractor(extractTo).extract(GZIPInputStream(tarball.inputStream().buffered(256 * 1024), 64 * 1024))
            val bin = extractTo.walkTopDown().firstOrNull { it.isFile && it.name == "opencode" }
                ?: fail(74, "cli", tr("в архиве нет файла opencode", "no opencode file in the archive"))
            val dest = File(destDir, "opencode")
            bin.copyTo(dest, overwrite = true)
            dest.setExecutable(true, false)
            TarExtractor.setMode(dest, 0b111_101_101)
        } catch (e: SetupException) {
            throw e
        } catch (e: Exception) {
            fail(74, "cli", tr("не удалось распаковать OpenCode: ${e.message}", "cannot unpack OpenCode: ${e.message}"))
        } finally {
            EmbeddedRuntime.deleteTree(extractTo)
        }
        versionMarker.writeText(OpenCodeRelease.VERSION + "\n")
        progress("running", 76, "cli", tr("OpenCode ${OpenCodeRelease.VERSION} установлен", "OpenCode ${OpenCodeRelease.VERSION} installed"))
    }

    private fun installAptAndHelpers(c: Context, cancelled: () -> Boolean) {
        val root = EmbeddedRuntime.rootfsDir(c)
        val aptMarker = File(root, SetupSkip.APT_MARKER)
        val gitOk = File(root, "usr/bin/git").isFile
        val rgOk = File(root, "usr/bin/rg").isFile
        val pyOk = File(root, "usr/bin/python3").isFile
        if (SetupSkip.shouldSkipApt(aptMarker.isFile, gitOk, rgOk, pyOk, force = false)) {
            progress("running", 88, "apt", tr("apt-пакеты уже установлены, шаг пропущен", "apt packages already installed, skipping"))
        } else {
            progress("running", 78, "apt", tr("Установка git, ripgrep, python3 (apt)", "Installing git, ripgrep, python3 (apt)"))
            val cmd = """
                set -e
                export DEBIAN_FRONTEND=noninteractive
                apt-get update -y
                apt-get install -y --no-install-recommends ca-certificates git ripgrep python3 curl
                command -v git >/dev/null
                command -v rg >/dev/null
                command -v python3 >/dev/null
            """.trimIndent()
            val result = kotlinx.coroutines.runBlocking {
                EmbeddedRuntime.bash(c, cmd, 15 * 60 * 1000L)
            }
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            if (!result.ok) {
                EmbeddedRuntime.appendLog(c, "install.log", result.combined().takeLast(4000))
                fail(85, "apt", tr("apt не удался: ${result.error ?: ("code " + result.exitCode)}", "apt failed: ${result.error ?: ("code " + result.exitCode)}"))
            }
            aptMarker.writeText("ok\n")
        }
        progress("running", 90, "phone", tr("Установка Phone API и MCP", "Installing Phone API and MCP"))
        GuestFiles.install(c)
        progress("running", 95, "config", tr("Запись конфигурации OpenCode", "Writing OpenCode config"))
    }

    private fun writeConfig(c: Context) {
        GuestFiles.writeOpenCodeConfig(c)
        File(EmbeddedRuntime.rootfsDir(c), SetupSkip.CONFIG_MARKER).writeText("ok\n")
    }

    private fun download(step: String, target: File, url: String, sha: String, pctFrom: Int, pctTo: Int, cancelled: () -> Boolean) {
        var loggedMb = -1L
        try {
            ResumableDownloader(client).download(url, target, sha, { done, total ->
                val mb = done / (1024 * 1024)
                val msg = if (total > 0) tr("Загрузка ${target.name}: $mb из ${total / (1024 * 1024)} МБ", "Downloading ${target.name}: $mb of ${total / (1024 * 1024)} MB")
                else tr("Загрузка ${target.name}: $mb МБ", "Downloading ${target.name}: $mb MB")
                val span = (pctTo - pctFrom).coerceAtLeast(1)
                val pct = if (total > 0) pctFrom + (done * span / total).toInt() else (pctFrom + pctTo) / 2
                val logIt = mb / 5 != loggedMb / 5 || (total in 1..done)
                if (logIt) loggedMb = mb
                progress("running", pct.coerceIn(pctFrom, pctTo), step, msg, logIt)
            }, cancelled)
        } catch (e: ChecksumMismatchException) {
            fail(pctFrom, step, tr("контрольная сумма ${target.name} не совпала, повторите", "checksum of ${target.name} does not match, retry"))
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(pctFrom, step, tr("не удалось скачать ${target.name} (повтор продолжит загрузку): ${e.message}", "cannot download ${target.name} (retry resumes): ${e.message}"))
        }
    }

    private fun fail(pct: Int, step: String, msg: String): Nothing {
        progress("error", pct, step, msg)
        throw SetupException(msg)
    }

    private class CountingStream(inner: InputStream) : FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}
