package com.byd.dashcast.cluster

/**
 * Which packages must never be sent to the cluster.
 *
 * The rule targets a *property*, not a list of names: **an app we cannot force-stop cannot be
 * brought back**. Projection ends by killing the app so the system forgets it was ever on the
 * cluster; when the kill fails, the app is left with a display preference pointing at a screen the
 * driver cannot see, and the next launcher tap goes there instead of to the centre screen.
 *
 * INC-20260815-181820 is the worked example: `com.byd.androidauto` was projected, the eviction
 * correctly parked its task on display 0, the kill failed (same pid before and after), and seven
 * seconds later a tap on the head unit's own Android Auto card was routed to the cluster —
 * demonstrated by the same launcher call site logging `mDisplayId=0` two minutes earlier and
 * `mDisplayId=1` after the session. The tester uninstalled DashCast over it, which could not help:
 * every piece of surviving state lives in `system_server`, not in our APK.
 *
 * Deliberately NOT keyed on uid 1000 or on `FLAG_SYSTEM`. That was the first hypothesis and the
 * corpus refutes it: `com.byd.avc` and `com.byd.mediacenter` both run as `system` and have been
 * force-stopped cleanly, and `com.byd.avc` has been projected successfully four times. Blocking by
 * uid would delete a feature people use to fix a fault they never hit.
 */
object ProjectionSafetyPolicy {

    /**
     * Packages refused whatever their flags report.
     *
     * Kept to the single case with a first-party incident behind it. A name list is the weakest
     * form of this rule — it only knows about cars we have already broken — which is why the
     * persistent-process test below carries the general case.
     */
    val ALWAYS_DENIED: Set<String> = setOf(
        "com.byd.androidauto"
    )

    enum class Verdict { ALLOWED, DENIED_KNOWN_HARMFUL, DENIED_PERSISTENT, DENIED_IS_HOME }

    /**
     * @param isPersistent  `ApplicationInfo.FLAG_PERSISTENT` — a process the framework restarts and
     *   that `forceStopPackage` skips, so projection could never clean up after itself. This is the
     *   general form of the rule; note it is inference rather than a confirmed reading of the
     *   incident package, whose flags the report never captured.
     * @param isHomeHandler true when this package currently resolves `CATEGORY_HOME`. Resolved at
     *   runtime on purpose: the hard-coded launcher list misses the launcher actually in use on 27
     *   corpus cars (`com.lexwah.kinex`, `com.dudu.autoui`), so a user could send their own home
     *   screen to the cluster.
     */
    @JvmStatic
    fun verdict(packageName: String?, isPersistent: Boolean, isHomeHandler: Boolean): Verdict {
        if (packageName == null || packageName.isEmpty()) return Verdict.ALLOWED
        if (ALWAYS_DENIED.contains(packageName)) return Verdict.DENIED_KNOWN_HARMFUL
        if (isHomeHandler) return Verdict.DENIED_IS_HOME
        if (isPersistent) return Verdict.DENIED_PERSISTENT
        return Verdict.ALLOWED
    }

    @JvmStatic
    fun isAllowed(packageName: String?, isPersistent: Boolean, isHomeHandler: Boolean): Boolean =
        verdict(packageName, isPersistent, isHomeHandler) == Verdict.ALLOWED

    /** One line for the journal, so a refusal in a bug report explains itself. */
    @JvmStatic
    fun reason(v: Verdict): String = when (v) {
        Verdict.ALLOWED -> "allowed"
        Verdict.DENIED_KNOWN_HARMFUL ->
            "known to strand itself on the cluster (cannot be force-stopped; INC-20260815-181820)"
        Verdict.DENIED_PERSISTENT ->
            "persistent system process — force-stop cannot clean up after projection"
        Verdict.DENIED_IS_HOME ->
            "this is the current home screen"
    }
}
