package ru.dredd20.opencode.hardware

import java.io.File

object CameraFiles {
    fun prune(dir: File, keep: Int) {
        val files = dir.listFiles { _, name -> name.endsWith(".jpg", true) }?.sortedBy { it.lastModified() } ?: return
        val extra = files.size - keep.coerceAtLeast(1)
        if (extra > 0) files.take(extra).forEach { it.delete() }
    }
}
