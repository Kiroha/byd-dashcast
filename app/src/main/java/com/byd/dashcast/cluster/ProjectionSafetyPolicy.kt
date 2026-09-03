package com.byd.dashcast.cluster

/**
 * The one thing that must never be sent to the cluster: the home screen.
 *
 * This class started life much bigger, and shrinking it is the finding. v1.8.29 refused
 * `com.byd.androidauto` by name and every `FLAG_PERSISTENT` process by rule, reasoning that "an app
 * we cannot force-stop cannot be brought back". The rule was sound; applying it here was not.
 * Android Auto and CarPlay on the cluster are the whole point of DashCast — INC-20260815-181820
 * itself opens with the tester saying Maps and Waze via Android Auto worked on the screen behind
 * the wheel — and `com.byd.carplay.ui` is persistent, so the general rule silently removed the
 * headline feature as well as the named one.
 *
 * The harm in that incident was never in PROJECTING those apps. It was in destroying their task on
 * the way out: `forceStopApp` removed the task before a kill that then failed, leaving the app with
 * no task to recycle and a display preference pointing at the cluster. That is fixed where it
 * happens. Prevention by removal traded the product's purpose for a cleanup bug.
 *
 * What survives is the single case with no plausible use behind it: projecting the launcher itself
 * would leave the head unit with no home screen. Resolved at runtime, because the hard-coded
 * launcher list misses the launcher actually in use on 27 corpus cars.
 */
object ProjectionSafetyPolicy {

    /**
     * **Empty, deliberately.** v1.8.29 put `com.byd.androidauto` here and refused persistent
     * processes as the general rule. Both were wrong, and the maintainer caught it within hours:
     * Android Auto and CarPlay on the cluster are what this app is *for* — the incident report even
     * opens with the tester saying Maps and Waze via Android Auto worked on the screen behind the
     * wheel. `com.byd.carplay.ui` is persistent, so the general rule silently deleted the headline
     * feature too.
     *
     * The harm was never in projecting those apps. It was in destroying their task on the way out,
     * which is fixed where it happens, in `AdbLocalClient.forceStopApp`. Prevention by removal
     * traded the product's purpose for a cleanup bug.
     *
     * Kept as a named, empty set rather than deleted: the next person to reach for a deny-list
     * should read why this one is empty first.
     */
    val ALWAYS_DENIED: Set<String> = emptySet()

    enum class Verdict { ALLOWED, DENIED_KNOWN_HARMFUL, DENIED_IS_HOME }

    /**
     * @param isHomeHandler true when this package currently resolves `CATEGORY_HOME`. The only rule
     *   left, and the only one with no plausible use case behind it: a home screen projected onto
     *   the cluster leaves the head unit with no launcher. Resolved at runtime on purpose — the
     *   hard-coded launcher list misses the launcher actually in use on 27 corpus cars
     *   (`com.lexwah.kinex`, `com.dudu.autoui`).
     */
    @JvmStatic
    fun verdict(packageName: String?, isHomeHandler: Boolean): Verdict {
        if (packageName == null || packageName.isEmpty()) return Verdict.ALLOWED
        if (ALWAYS_DENIED.contains(packageName)) return Verdict.DENIED_KNOWN_HARMFUL
        if (isHomeHandler) return Verdict.DENIED_IS_HOME
        return Verdict.ALLOWED
    }

    @JvmStatic
    fun isAllowed(packageName: String?, isHomeHandler: Boolean): Boolean =
        verdict(packageName, isHomeHandler) == Verdict.ALLOWED

    /** One line for the journal, so a refusal in a bug report explains itself. */
    @JvmStatic
    fun reason(v: Verdict): String = when (v) {
        Verdict.ALLOWED -> "allowed"
        Verdict.DENIED_KNOWN_HARMFUL ->
            "known to be unrecoverable after projection"
        Verdict.DENIED_IS_HOME ->
            "this is the current home screen"
    }
}
