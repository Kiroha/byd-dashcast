package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.cluster.display.DaemonDisplayEnumerator
import com.byd.dashcast.clusterlive.ClusterDashboardDisplayPolicy

/** A fixed, bounded shell read. No display creation, OEM command or fallback target. */
internal object ClusterDashboardDisplayProbe {
    fun read(): ClusterDashboardDisplayPolicy.Evidence {
        val result = ProxyShell.exec("dumpsys -t 1 display", 1500, 256 * 1024)
        return parse(result.output, result.exit == 0)
    }

    internal fun parse(output: String, success: Boolean = true): ClusterDashboardDisplayPolicy.Evidence {
        fun unavailable() = ClusterDashboardDisplayPolicy.inspect(emptyList(), false)
        // Android 10 dumpsys can exit 0 after timing out or failing mid-dump.
        val failed = listOf("[output truncated]", "DUMP TIMEOUT", "EXPIRED", "Permission Denial",
            "Error dumping service", "Error in poll while dumping service", "Failed to read while dumping service",
            "Failed to write while dumping service", "Can't find service").any { output.contains(it, ignoreCase = true) }
        if (!success || failed || !output.contains("DISPLAY MANAGER")) return unavailable()
        val logical = Regex("(?m)^[ \\t]*Logical Displays: size=(\\d+)[ \\t]*$").find(output) ?: return unavailable()
        val count = logical.groupValues[1].toIntOrNull() ?: return unavailable()
        if (count !in 1..32) return unavailable()
        val body = output.substring(logical.range.last + 1)
        val headers = Regex("(?m)^  Display (\\d+):[ \\t]*$").findAll(body).toList()
        if (headers.size != count || headers.map { it.groupValues[1] }.distinct().size != count) return unavailable()
        val displays = headers.mapIndexed { index, header ->
            val block = body.substring(header.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: body.length)
            val base = Regex("(?m)^[ \\t]*mBaseDisplayInfo=(DisplayInfo\\{[^\\r\\n]+\\})[ \\t]*$").findAll(block).toList()
            if (base.size != 1) return unavailable()
            val parsed = DaemonDisplayEnumerator.parseDisplayDump(base.single().groupValues[1])
            if (parsed.size != 1 || parsed.single().id != header.groupValues[1].toIntOrNull()) return unavailable()
            parsed.single()
        }
        return ClusterDashboardDisplayPolicy.inspect(displays)
    }
}
