package com.byd.dashcast.proxy.daemon

/** Normalizes direct and stack-based task-move diagnostics into a stable success contract. */
object TaskMoveResult {

    fun interface Operation {
        fun run(): String?
    }

    @JvmStatic
    fun runWithFallback(direct: Operation, fallback: Operation): String {
        val directResult = direct.run()
        if (directResult != null && isSuccess(directResult)) return directResult
        return combine(directResult, fallback.run())
    }

    @JvmStatic
    fun isSuccess(result: String?): Boolean {
        if (result == null) return false
        val normalized = result.trim()
        return normalized.startsWith("OK ") ||
            normalized.contains("; OK moveStackToDisplay(") ||
            normalized.contains("; OK moveRootTaskToDisplay(") ||
            normalized.contains("SKIP move (already on display ")
    }

    @JvmStatic
    fun combine(direct: String?, fallback: String?): String {
        if (direct != null && isSuccess(direct)) return direct
        if (fallback != null && isSuccess(fallback)) {
            return "OK stack fallback: $fallback ; direct=$direct"
        }
        return "ERR task move failed: direct=[$direct] fallback=[$fallback]"
    }
}
