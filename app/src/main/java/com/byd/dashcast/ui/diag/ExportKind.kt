package com.byd.dashcast.ui.diag

import java.io.File
import java.io.Reader
import java.io.IOException

/** Explicit profile survives progress, interrupted-session recovery and manual sharing. */
internal enum class ExportKind(val label: String, val noun: String, val rootName: String, val prefix: String,
                               val entryRoot: String, val archivePrefix: String) {
    APK("APK", "APKs", "apk-exports", "apk-export-", "apks", "apks"),
    SYSTEM("SYSTEM", "system files", "system-exports", "system-export-", "system", "system"),
    SYSTEM_P15("SYSTEM P15", "targeted system files", "system-p15-exports", "system-p15-export-", "system", "system"),
    FIRMWARE_LARGE("FIRMWARE LARGE", "firmware files", "firmware-exports", "firmware-export-", "firmware", "firmware"),
    CONTEXT("CONTEXTE", "context reads", "context-exports", "context-export-", "context", "context"),
    QR_DIAGNOSTIC("QR DIAGNOSTIC", "QR diagnostic metadata reads", "qr-diagnostic-exports", "qr-diagnostic-", "qr-diagnostic", "qr-diagnostic"),
    OEM_SERVICE("SERVICE OEM", "OEM service metadata reads", "oem-service-exports", "oem-service-", "oem-service", "oem-service"),
    OEM_INIT_SOURCES("SOURCES INIT OEM", "init source files", "oem-init-source-exports", "oem-init-sources-", "init-sources", "oem-init-sources");

    val isSystem: Boolean get() = this == SYSTEM || this == SYSTEM_P15
    val isContext: Boolean get() = this == CONTEXT || this == QR_DIAGNOSTIC || this == OEM_SERVICE
    val contextProfile: DiLinkContextPlan.Profile get() = when (this) {
        CONTEXT -> DiLinkContextPlan.Profile.CONTEXT
        QR_DIAGNOSTIC -> DiLinkContextPlan.Profile.QR_DIAGNOSTIC
        OEM_SERVICE -> DiLinkContextPlan.Profile.OEM_SERVICE
        else -> error("Not a context metadata profile")
    }
    val systemProfile: SystemExportProfile get() = if (this == SYSTEM_P15) SystemExportProfile.DILINK_P15 else SystemExportProfile.FULL

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
