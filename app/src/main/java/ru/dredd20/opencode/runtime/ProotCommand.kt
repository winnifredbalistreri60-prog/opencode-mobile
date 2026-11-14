package ru.dredd20.opencode.runtime

data class ProotLayout(
    val nativeLibDir: String,
    val rootfs: String,
    val hostDir: String,
    val tmpDir: String,
    val fakeProcDir: String,
    val runDir: String,
) {
    val proot get() = "$nativeLibDir/libproot.so"
    val loader get() = "$nativeLibDir/libproot-loader.so"
    val loader32 get() = "$nativeLibDir/libproot-loader32.so"
}

/** Command line and environment for PRoot (pure logic, unit-tested). */
object ProotCommand {
    const val HOST_MOUNT = "/opencode-host"
    const val GUEST_PATH = "/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val PHONE_SOCK = "/run/phone.sock"
    const val PHONE_TOKEN = "/run/phone.token"
    val FAKE_PROC = listOf("loadavg", "stat", "uptime", "version", "vmstat")

    fun args(
        l: ProotLayout,
        guest: List<String>,
        fakeProcFiles: List<String> = emptyList(),
        cwd: String = "/root",
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): List<String> {
        require(guest.isNotEmpty()) { "empty guest command" }
        val a = mutableListOf(
            l.proot, "--link2symlink", "--kill-on-exit", "-0",
            "-r", l.rootfs,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${l.rootfs}/tmp:/dev/shm",
            "-b", "${l.hostDir}:$HOST_MOUNT",
            "-b", "${l.runDir}:/run",
        )
        for (f in fakeProcFiles) {
            require(f in FAKE_PROC) { "unexpected fake /proc file $f" }
            a += listOf("-b", "${l.fakeProcDir}/$f:/proc/$f")
        }
        for ((host, guestPath) in extraBinds) {
            a += listOf("-b", "$host:$guestPath")
        }
        a += listOf("-w", cwd)
        a += guest
        return a
    }

    /**
     * Isolated guest environment: Android env is not inherited.
     * HOME / XDG_* / TMPDIR point inside the rootfs so OpenCode does not leak
     * paths like /data/local/tmp/opencode onto the Android host.
     */
    fun env(l: ProotLayout, noSeccomp: Boolean, hasLoader32: Boolean, extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("PROOT_TMP_DIR", l.tmpDir)
        put("PROOT_LOADER", l.loader)
        if (hasLoader32) put("PROOT_LOADER_32", l.loader32)
        if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
        put("HOME", "/root")
        put("LANG", "C.UTF-8")
        put("TERM", "xterm-256color")
        put("TMPDIR", "/tmp")
        put("TMP", "/tmp")
        put("TEMP", "/tmp")
        put("XDG_DATA_HOME", "/root/.local/share")
        put("XDG_CONFIG_HOME", "/root/.config")
        put("XDG_STATE_HOME", "/root/.local/state")
        put("XDG_CACHE_HOME", "/root/.cache")
        put("XDG_RUNTIME_DIR", "/run")
        put("PATH", GUEST_PATH)
        put("OPENCODE_DISABLE_AUTOUPDATE", "1")
        put("OPENCODE_FILEWATCHER_DISABLE", "1")
        put("OPENCODE_DISABLE_FILEWATCHER", "1")
        put("PHONE_SOCK", PHONE_SOCK)
        put("PHONE_TOKEN_FILE", PHONE_TOKEN)
        for ((k, v) in extra) {
            require(k.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "bad env name $k" }
            put(k, v)
        }
    }

    fun guestEnv(extra: Map<String, String> = emptyMap()): List<String> {
        val e = mutableListOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "TERM=xterm-256color",
            "PATH=$GUEST_PATH",
            "TMPDIR=/tmp",
            "TMP=/tmp",
            "TEMP=/tmp",
            "XDG_DATA_HOME=/root/.local/share",
            "XDG_CONFIG_HOME=/root/.config",
            "XDG_STATE_HOME=/root/.local/state",
            "XDG_CACHE_HOME=/root/.cache",
            "XDG_RUNTIME_DIR=/run",
            "OPENCODE_DISABLE_AUTOUPDATE=1",
            "OPENCODE_FILEWATCHER_DISABLE=1",
            "OPENCODE_DISABLE_FILEWATCHER=1",
            "PHONE_SOCK=${PHONE_SOCK}",
            "PHONE_TOKEN_FILE=${PHONE_TOKEN}",
        )
        for ((k, v) in extra) {
            require(k.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "bad env name $k" }
            e += "$k=$v"
        }
        return e
    }

    fun bash(command: String, extraEnv: Map<String, String> = emptyMap()): List<String> =
        guestEnv(extraEnv) + listOf("/bin/bash", "-lc", command)

    fun fakeProcContent(name: String, kernelRelease: String): String = when (name) {
        "loadavg" -> "0.12 0.07 0.02 2/165 765\n"
        "uptime" -> "124.08 932.80\n"
        "version" -> "Linux version $kernelRelease (proot@opencode-mobile) #1 SMP PREEMPT\n"
        "stat" -> "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n" +
            "intr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\n"
        "vmstat" -> "nr_free_pages 136757\nnr_inactive_anon 0\nnr_active_anon 0\n"
        else -> throw IllegalArgumentException(name)
    }

    fun resolvConf(dns: List<String>): String {
        val all = (dns.map { it.trim().substringBefore('%') }.filter { it.isNotEmpty() } + listOf("1.1.1.1", "8.8.8.8")).distinct()
        return "# OpenCode Mobile (embedded runtime)\n" + all.take(4).joinToString("") { "nameserver $it\n" }
    }
}
