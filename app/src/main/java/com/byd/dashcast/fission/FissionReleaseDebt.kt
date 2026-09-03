package com.byd.dashcast.fission

import java.util.concurrent.ConcurrentHashMap

/** Process-wide ownership record for daemon slot releases that have not been acknowledged. */
object FissionReleaseDebt {

    fun interface Releaser {
        @Throws(Exception::class)
        fun release(key: String)
    }

    private val keys = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun record(key: String?) {
        if (!key.isNullOrEmpty()) keys += key
    }

    @JvmStatic
    fun recordAll(unreleased: Collection<String>?) {
        unreleased.orEmpty().forEach(::record)
    }

    @JvmStatic
    fun settled(key: String?) {
        if (key != null) keys.remove(key)
    }

    /** Retries a stable snapshot; failures remain owned for the next healthy binder. */
    @JvmStatic
    fun retry(releaser: Releaser?): Set<String> {
        if (releaser == null) return snapshot()
        for (key in keys.toList()) {
            try {
                releaser.release(key)
                keys.remove(key)
            } catch (_: Exception) {
                // Ownership remains in the process-wide set.
            }
        }
        return snapshot()
    }

    @JvmStatic
    fun snapshot(): Set<String> = HashSet(keys)

    @JvmStatic
    fun clearAll() {
        keys.clear()
    }

    internal fun resetForTest() {
        clearAll()
    }
}