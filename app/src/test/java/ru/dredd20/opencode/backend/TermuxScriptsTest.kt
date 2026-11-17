package ru.dredd20.opencode.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.dredd20.opencode.runtime.CommandResult
import ru.dredd20.opencode.runtime.OpenCodeRelease
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class TermuxScriptsTest {
    private val secret = "s3cr3t-Pa55word_xyz"

    @Test
    fun serveScriptHasNoSecretAndExecs() {
        val s = TermuxScripts.serve(43123)
        assertFalse(s.contains("pkill"))
        assertTrue(s.contains("IFS= read -r OCM_PW"))
        assertTrue(s.contains("chmod 600"))
        assertTrue(s.contains("exec \"\$BIN\" serve --hostname 127.0.0.1 --port 43123"))
        assertFalse(s.contains("OPENCODE_PASSWORD=\$(printf"))
    }

    @Test
    fun stopScriptOnlyKillsOurPort() {
        val s = TermuxScripts.stop(4096)
        assertFalse(s.contains("pkill"))
        assertFalse(s.contains("killall"))
        assertTrue(s.contains("port=\"4096\""))
        assertTrue(s.contains("\"--port \$port \"*"))
        assertTrue(TermuxScripts.stop(null).contains("serve.port"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun badPortRejected() { TermuxScripts.serve(0) }

    private fun bash(): String? = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() }

    private fun fakeHome(): Pair<File, File> {
        val home = Files.createTempDirectory("ocm-termux-home").toFile()
        val bin = File(home, "bin").apply { mkdirs() }
        File(bin, "opencode").apply {
            writeText(
                """
                #!/bin/bash
                if [ "${'$'}1" = "--version" ]; then echo "fake-opencode 9.9.9"; exit 0; fi
                printf '%s' "${'$'}OPENCODE_PASSWORD" > "${'$'}HOME/seen-password"
                tr '\0' ' ' < /proc/${'$'}${'$'}/cmdline > "${'$'}HOME/seen-cmdline"
                echo ${'$'}${'$'} > "${'$'}HOME/seen-pid"
                while :; do sleep 0.2; done
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        return home to bin
    }

    private fun start(script: String, home: File, bin: File, stdin: String?): Process {
        val pb = ProcessBuilder(bash()!!, "-c", script).directory(home)
        pb.environment()["HOME"] = home.path
        pb.environment()["PATH"] = bin.path + ":/usr/bin:/bin"
        pb.redirectErrorStream(false)
        val p = pb.start()
        p.outputStream.use { if (stdin != null) it.write(stdin.toByteArray()) }
        return p
    }

    private fun waitFor(f: File, ms: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (f.isFile && f.length() > 0) return true; Thread.sleep(50) }
        return false
    }

    @Test
    fun serveAndStopForReal() {
        assumeTrue(bash() != null && File("/proc/self/cmdline").exists())
        val (home, bin) = fakeHome()
        val p = start(TermuxScripts.serve(43123), home, bin, "$secret\n")
        assertTrue("serve did not start", waitFor(File(home, "seen-pid")))
        val d = File(home, ".opencode-mobile")
        // exec chain: the pid in our pidfile is the serve process itself (no bash parent left).
        assertEquals(File(home, "seen-pid").readText().trim(), File(d, "serve.pid").readText().trim())
        assertEquals(secret, File(home, "seen-password").readText())
        val cmdline = File(home, "seen-cmdline").readText()
        assertFalse("password on argv", cmdline.contains(secret))
        assertTrue(cmdline.contains(" serve ") && cmdline.contains("--port 43123 "))
        val perms = Files.getPosixFilePermissions(File(d, "serve.env").toPath()).map { it.name }.toSet()
        assertEquals(setOf("OWNER_READ", "OWNER_WRITE"), perms)
        assertTrue(p.isAlive)

        // A foreign "opencode serve" on another port must survive our Stop.
        // cmdline "bash -c … opencode serve --hostname 127.0.0.1 --port 43124": the old pkill -f 'opencode serve' killed this.
        val foreign = ProcessBuilder(bash()!!, "-c", "while :; do sleep 1; done", "opencode", "serve", "--hostname", "127.0.0.1", "--port", "43124").start()
        val stop = start(TermuxScripts.stop(43123), home, bin, null)
        assertTrue(stop.waitFor(15, TimeUnit.SECONDS))
        assertEquals("stopped", stop.inputStream.bufferedReader().readText().trim())
        assertTrue("our serve still alive", p.waitFor(10, TimeUnit.SECONDS))
        assertTrue("foreign process was killed", foreign.isAlive)
        assertFalse(File(d, "serve.env").exists())
        assertFalse(File(d, "serve.pid").exists())
        foreign.destroyForcibly()

        // Stop again: nothing of ours runs, nothing else is touched.
        val again = start(TermuxScripts.stop(43123), home, bin, null)
        assertTrue(again.waitFor(15, TimeUnit.SECONDS))
        assertEquals("our server is not running", again.inputStream.bufferedReader().readText().trim())
        home.deleteRecursively()
    }

    @Test
    fun stopIgnoresPidfileOfSomeoneElse() {
        assumeTrue(bash() != null && File("/proc/self/cmdline").exists())
        val (home, bin) = fakeHome()
        val d = File(home, ".opencode-mobile").apply { mkdirs() }
        // A foreign process whose pid ended up in our pidfile (pid reuse): must not be killed.
        val victim = ProcessBuilder(bash()!!, "-c", "echo ${'$'}${'$'} > \"${'$'}0\"; exec sleep 300", File(d, "serve.pid").path).start()
        assertTrue(waitFor(File(d, "serve.pid")))
        val stop = start(TermuxScripts.stop(43123), home, bin, null)
        assertTrue(stop.waitFor(15, TimeUnit.SECONDS))
        assertTrue(victim.isAlive)
        victim.destroyForcibly()
        home.deleteRecursively()
    }

    @Test
    fun serveFailsClearlyWithoutPasswordOrBinary() {
        assumeTrue(bash() != null)
        val (home, bin) = fakeHome()
        val noPw = start(TermuxScripts.serve(43125), home, bin, "")
        assertTrue(noPw.waitFor(15, TimeUnit.SECONDS))
        assertEquals(TermuxScripts.EXIT_NO_PASSWORD, noPw.exitValue())
        File(bin, "opencode").delete()
        val noBin = start(TermuxScripts.serve(43125), home, bin, "$secret\n")
        assertTrue(noBin.waitFor(15, TimeUnit.SECONDS))
        assertEquals(TermuxScripts.EXIT_NOT_INSTALLED, noBin.exitValue())
        assertTrue(noBin.errorStream.bufferedReader().readText().contains("not installed in Termux"))
        home.deleteRecursively()
    }

    @Test
    fun failureMessageRedactsAndTails() {
        val r = CommandResult("", (1..40).joinToString("\n") { "line $it" } + "\nboom $secret", 1, null)
        val m = TermuxFailure.describe(r, secret)
        assertTrue(m.contains("code 1") || m.contains("код 1"))
        assertTrue(m.contains("boom ••••"))
        assertFalse(m.contains(secret))
        assertFalse(m.contains("line 1\n"))
        assertTrue(TermuxFailure.describe(CommandResult("", "", 127, null)).contains("not installed").or(
            TermuxFailure.describe(CommandResult("", "", 127, null)).contains("не установлен"),
        ))
        assertTrue(TermuxFailure.describe(CommandResult("", "", null, "allow-external-apps is not true")).contains("allow-external-apps"))
    }

    private fun projectFile(rel: String): File =
        listOf(File(rel), File("..", rel)).first { it.exists() }

    @Test
    fun installScriptCopiesMatchAndArePinned() {
        val asset = projectFile("app/src/main/assets/termux/termux-install.sh")
        val copy = projectFile("scripts/termux-install.sh")
        assertEquals(asset.readText(), copy.readText())
        val s = asset.readText()
        assertTrue(s.contains(OpenCodeRelease.LINUX_ARM64.sha256))
        assertTrue(s.contains(OpenCodeRelease.LINUX_X64.sha256))
        assertTrue(s.contains("OC_VERSION=\"\${OCM_OPENCODE_VERSION:-${OpenCodeRelease.VERSION}}\""))
        assertTrue(s.contains("--set-interpreter"))
        assertFalse(s.contains("--set-rpath"))
        assertTrue(s.contains("unset LD_PRELOAD LD_LIBRARY_PATH"))
        assertTrue(s.contains("freebuff-termux|codebuff-termux"))
        assertTrue(s.contains(".ocm-backup-\$stamp"))
        assumeTrue(bash() != null)
        val p = ProcessBuilder(bash()!!, "-n", asset.path).redirectErrorStream(true).start()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(p.inputStream.bufferedReader().readText(), 0, p.exitValue())
    }

    @Test
    fun pasteCommandWritesThenRuns() {
        val cmd = TermuxInstall.pasteCommand("echo hi\n")
        assertTrue(cmd.startsWith("unset LD_PRELOAD LD_LIBRARY_PATH\ncat > \"\$HOME/ocm-termux-install.sh\" <<'OCM_INSTALL_EOF'\necho hi\nOCM_INSTALL_EOF\n"))
        assertTrue(cmd.endsWith("bash \"\$HOME/ocm-termux-install.sh\"\n"))
    }

    @Test
    fun pasteCommandRunsInBash() {
        assumeTrue(bash() != null)
        val home = Files.createTempDirectory("ocm-paste").toFile()
        val pb = ProcessBuilder(bash()!!, "-c", TermuxInstall.pasteCommand("echo \"ok \$1\${HOME:+-home}\"\n"))
        pb.environment()["HOME"] = home.path
        val p = pb.start()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals("ok -home", p.inputStream.bufferedReader().readText().trim())
        home.deleteRecursively()
    }

    @Test
    fun pasteCommandClearsInheritedLibraryPath() {
        assumeTrue(bash() != null)
        val home = Files.createTempDirectory("ocm-paste-env").toFile()
        val command = TermuxInstall.pasteCommand("printf '%s' \"\${LD_LIBRARY_PATH-unset}:\${LD_PRELOAD-unset}\"\n")
        val prefix = "export LD_LIBRARY_PATH=/tmp/foreign-codex-libs\nexport LD_PRELOAD=/tmp/foreign-codex-preload.so\n"
        val pb = ProcessBuilder(bash()!!, "-c", prefix + command)
        pb.environment()["HOME"] = home.path
        pb.environment().remove("LD_LIBRARY_PATH")
        pb.environment().remove("LD_PRELOAD")
        val p = pb.start()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        val stdout = p.inputStream.bufferedReader().readText().trim()
        val stderr = p.errorStream.bufferedReader().readText().trim()
        assertEquals("stdout=[$stdout] stderr=[$stderr]", 0, p.exitValue())
        assertEquals("unset:unset", stdout)
        home.deleteRecursively()
    }

    @Test
    fun termuxMarkdownErrorIsCleaned() {
        val raw = "Error Code: `2`\nError Message:\n```\nRunCommandService requires `allow-external-apps` property to be set to `true` in `~/.termux/termux.properties` file.\n```"
        val c = TermuxFailure.cleanError(raw)
        assertEquals("RunCommandService requires allow-external-apps property to be set to true in ~/.termux/termux.properties file.", c)
        val m = TermuxFailure.describe(CommandResult("", "", null, raw))
        assertFalse(m.contains("```"))
        assertTrue(m.contains("termux-reload-settings"))
    }
}
