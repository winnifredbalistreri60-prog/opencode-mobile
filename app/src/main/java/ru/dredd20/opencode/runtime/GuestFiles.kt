package ru.dredd20.opencode.runtime

import android.content.Context
import java.io.File

object GuestFiles {
    private val CLI_NAMES = listOf(
        "termux-sensor", "termux-battery-status", "termux-location", "termux-camera-photo",
        "termux-clipboard-get", "termux-clipboard-set", "termux-notification", "termux-toast",
        "termux-vibrate", "termux-torch", "termux-tts-speak", "termux-volume",
        "termux-wifi-connectioninfo", "termux-share", "termux-open-url", "termux-telephony-call",
        "termux-usb",
        "termux-sms-send", "termux-sms-list", "termux-contact-list",
        "phone-mcp",
    )

    fun install(c: Context) {
        installPhoneCli(c)
        writeOpenCodeConfig(c)
    }

    /** Refresh Phone API CLI scripts without rewriting the user's opencode.json. Called on each server start so upgrades pick up script fixes. */
    fun installPhoneCli(c: Context) {
        val root = EmbeddedRuntime.rootfsDir(c)
        if (!root.isDirectory) return
        val bin = File(root, "usr/local/bin").apply { mkdirs() }
        val client = c.assets.open("phone/phone-client.sh").bufferedReader().use { it.readText() }
        File(bin, "phone-client").writeText(client)
        File(bin, "phone-client").setExecutable(true, false)
        val mcp = c.assets.open("phone/phone-mcp.py").bufferedReader().use { it.readText() }
        File(bin, "phone-mcp").writeText(mcp)
        File(bin, "phone-mcp").setExecutable(true, false)
        val wrapper = """
            |#!/bin/bash
            |# OpenCode Mobile Phone API wrapper (MIT). Speaks the same names as Termux:API CLI.
            |set -e
            |cmd="${'$'}(basename "${'$'}0")"
            |cmd="${'$'}{cmd#termux-}"
            |exec /usr/local/bin/phone-client "${'$'}cmd" "${'$'}@"
            |""".trimMargin()
        for (name in CLI_NAMES) {
            if (name == "phone-mcp") continue
            val f = File(bin, name)
            f.writeText(wrapper)
            f.setExecutable(true, false)
        }
    }

    fun writeOpenCodeConfig(c: Context) {
        val cfgDir = File(EmbeddedRuntime.rootfsDir(c), "root/.config/opencode").apply { mkdirs() }
        File(EmbeddedRuntime.rootfsDir(c), "root/.local/share/opencode").mkdirs()
        File(EmbeddedRuntime.rootfsDir(c), "root/.local/state/opencode").mkdirs()
        File(EmbeddedRuntime.rootfsDir(c), "root/.cache/opencode").mkdirs()
        File(EmbeddedRuntime.rootfsDir(c), "tmp").mkdirs()
        File(cfgDir, "opencode.json").writeText(openCodeConfigJson())
    }

    fun openCodeConfigJson(): String =
        """
        {
          "update": "disable",
          "mcp": {
            "servers": {
              "phone": {
                "type": "local",
                "command": ["phone-mcp"]
              }
            }
          }
        }
        """.trimIndent() + "\n"
}
