package com.byd.dashcast.hud

/** Process-local counters survive listener rebinds, without retaining notifications or road text. */
internal class NavigationDiagnostics {
    enum class Rejection { NO_EXTRAS, SKIPPED, EMPTY, NO_GUIDANCE, NO_MANEUVER, NO_DISTANCE }

    private var observed = 0L
    private var duplicates = 0L
    private var parsed = 0L
    private var deliveryAttempts = 0L
    private var acceptedAny = 0L
    private var unavailable = 0L
    private var deliveryErrors = 0L
    private val rejected = LongArray(Rejection.entries.size)
    private var lastObservedMs: Long? = null
    private var lastPackage: String? = null
    private var lastRejection: String? = null
    private var lastDelivery: String? = null
    private var lastRejectionLogMs: Long? = null
    private var lastRejectionLogKey: String? = null
    private var lastDeliveryLogMs: Long? = null
    private var lastDeliveryLogKey: String? = null

    @Synchronized
    fun observe(pkg: String, nowMs: Long) {
        observed++
        lastPackage = pkg
        lastObservedMs = nowMs
    }

    @Synchronized fun duplicate() { duplicates++ }
    @Synchronized fun parsed() { parsed++ }

    /** Every rejection is counted; recurring journal entries are limited to one per 30 seconds. */
    @Synchronized
    fun reject(
        pkg: String, reason: Rejection, nowMs: Long,
        smallIconResource: String = "", distanceMeters: Int = -1,
        titleLength: Int = 0, textLength: Int = 0, bigTextLength: Int = 0,
    ): String? {
        rejected[reason.ordinal]++
        // Resource names are diagnostic identifiers, not arbitrary notification text.
        val resource = smallIconResource.take(96).replace(UNSAFE_RESOURCE_CHAR, "_")
        val detail = "app=$pkg reason=${reason.name.lowercase(java.util.Locale.ROOT)} " +
            "smallIcon='$resource' dist=$distanceMeters " +
            "titleLen=$titleLength textLen=$textLength bigLen=$bigTextLength"
        lastRejection = detail
        val key = "$pkg|$reason"
        val last = lastRejectionLogMs
        if (key == lastRejectionLogKey && last != null && nowMs - last < 30_000L) return null
        lastRejectionLogKey = key
        lastRejectionLogMs = nowMs
        return detail
    }

    /** Acceptance is the controller's ANY-output transport result, never proof of physical render. */
    @Synchronized
    fun delivery(pkg: String, enabled: Boolean, accepted: Boolean, nowMs: Long, errorClass: String? = null): String? {
        deliveryAttempts++
        when {
            errorClass != null -> deliveryErrors++
            accepted -> acceptedAny++
            else -> unavailable++
        }
        lastDelivery = "app=$pkg outputsEnabled=$enabled acceptedAny=$accepted" +
            (if (errorClass == null) "" else " errorClass=$errorClass")
        if (accepted && errorClass == null) {
            lastDeliveryLogKey = null
            return null
        }
        val key = "$pkg|$enabled|$errorClass"
        val last = lastDeliveryLogMs
        if (key == lastDeliveryLogKey && last != null && nowMs - last < 30_000L) return null
        lastDeliveryLogKey = key
        lastDeliveryLogMs = nowMs
        return lastDelivery
    }

    @Synchronized
    fun summary(nowMs: Long): String = buildString {
        append("observed=").append(observed).append(" duplicate=").append(duplicates)
        append(" parsed=").append(parsed).append(" lastApp=").append(lastPackage ?: "none")
        append(" lastObservedAgeMs=").append(lastObservedMs?.let { (nowMs - it).coerceAtLeast(0) } ?: "none")
        append('\n').append("rejected:")
        Rejection.entries.forEach { append(' ').append(it.name.lowercase(java.util.Locale.ROOT))
            .append('=').append(rejected[it.ordinal]) }
        append('\n').append("deliveryAttempts=").append(deliveryAttempts)
        append(" acceptedAny=").append(acceptedAny).append(" unavailable=").append(unavailable)
        append(" errors=").append(deliveryErrors)
        append('\n').append("lastRejection: ").append(lastRejection ?: "none")
        append('\n').append("lastDelivery: ").append(lastDelivery ?: "none")
    }

    private companion object {
        val UNSAFE_RESOURCE_CHAR = Regex("[^A-Za-z0-9_.]")
    }
}
