package com.byd.dashcast.ui.diag

import java.io.File
import java.io.Reader
import java.io.IOException

/** Explicit profile survives progress, interrupted-session recovery and manual sharing. */
internal enum class ExportKind(val label: String, val noun: String, val rootName: String, val prefix: String,
                               val entryRoot: String, val archivePrefix: String) {
    APK("APK", "APKs", "apk-exports", "apk-export-", "apks", "apks"),
    SYSTEM("SYSTEM", "system files", "system-exports", "system-export-", "system", "system"),
    CONTEXT("CONTEXTE", "context reads", "context-exports", "context-export-", "context", "context");

    companion object {
        /** Timestamp names are comparable across profiles; directory prefixes are not. */
        fun latest(bases: List<Pair<ExportKind, File>>): Pair<ExportKind, File>? = bases.flatMap { (kind, base) ->
            base.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(kind.prefix) }
                .map { kind to it }
        }.maxByOrNull { (kind, file) -> file.name.removePrefix(kind.prefix) }
    }
}

/** Bounded text inspection for recovery metadata and selected init fragments. */
internal object ExportText {
    fun read(reader: Reader, limit: Int): Pair<String, Boolean> {
        val chars = CharArray(limit + 1)
        var count = 0
        while (count < chars.size) {
            val n = reader.read(chars, count, chars.size - count)
            if (n < 0) break
            if (n == 0) throw IOException("Text reader returned no progress")
            count += n
        }
        return String(chars, 0, minOf(count, limit)) to (count > limit)
    }
}
