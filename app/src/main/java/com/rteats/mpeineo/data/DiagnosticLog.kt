package com.rteats.mpeineo.data

import android.app.Application
import java.io.File
import java.time.Instant

class DiagnosticLog(
    application: Application,
) {
    private val lock = Any()
    private val directory = File(application.filesDir, "diagnostics")
    private val file = File(directory, "runtime.log")

    init {
        directory.mkdirs()
        trimIfNeeded()
    }

    fun log(
        category: String,
        message: String,
    ) {
        val safeCategory = category
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(24)
        val safeMessage = message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(2_000)

        synchronized(lock) {
            directory.mkdirs()
            file.appendText("${Instant.now()} [$safeCategory] $safeMessage\n")
            trimIfNeededLocked()
        }
    }

    fun readText(): String =
        synchronized(lock) {
            if (file.exists()) file.readText() else ""
        }

    fun clear() {
        synchronized(lock) {
            file.delete()
        }
    }

    private fun trimIfNeeded() {
        synchronized(lock) {
            trimIfNeededLocked()
        }
    }

    private fun trimIfNeededLocked() {
        if (!file.exists() || file.length() <= MAX_BYTES) return

        val lines = file.readLines()
        val kept = lines.takeLast(MAX_LINES)
        file.writeText(
            if (kept.isEmpty()) "" else kept.joinToString(separator = "\n", postfix = "\n"),
        )
    }

    private companion object {
        const val MAX_BYTES = 1024L * 1024L
        const val MAX_LINES = 4_000
    }
}
