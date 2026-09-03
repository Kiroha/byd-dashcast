package com.byd.dashcast.infrastructure

/**
 * Decides whether an existing [com.byd.dashcast.proxy.daemon.SurfaceDaemon] is safe to keep across
 * app lifecycle changes.
 *
 * Reusing matters precisely because that daemon HOLDS state (cluster slot overlays, trusted
 * VirtualDisplays, the mirror token): killing it throws all of it away. See the two-daemon boundary
 * documented on [com.byd.dashcast.proxy.daemon.SurfaceDaemon].
 */
object SurfaceDaemonReusePolicy {
    @JvmStatic
    fun singleProcessPid(psOutput: String?): Int {
        if (psOutput == null || psOutput.trim().isEmpty()) return -1
        val lines = psOutput.trim().split(Regex("\\r?\\n"))
        if (lines.size != 1) return -1
        val fields = lines[0].trim().split(Regex("\\s+"))
        if (fields.size < 2) return -1
        return try {
            fields[1].toInt()
        } catch (ignored: NumberFormatException) {
            -1
        }
    }

    @JvmStatic
    fun shouldReuse(binderAlive: Boolean, daemonPid: Int, versionMarker: String?, appBuild: Int): Boolean {
        if (!binderAlive || daemonPid <= 0 || versionMarker == null) return false
        val fields = versionMarker.trim().split(":")
        if (fields.size != 2) return false
        return try {
            val markerPid = fields[0].toInt()
            val daemonBuild = fields[1].toInt()
            markerPid == daemonPid && daemonBuild == appBuild
        } catch (ignored: NumberFormatException) {
            false
        }
    }
}
