package com.byd.dashcast.clusterlive

/** One explicit confirmation round. Consuming it keeps admission closed until recovery. */
internal class ClusterRestartLease(private val now: () -> Long) {
    private var round = ""
    private var issuedAt = 0L
    private var consumed = false
    fun held(): Boolean {
        if (round.isNotEmpty() && !consumed && now() - issuedAt !in 0..120000) {
            round = ""; issuedAt = 0
        }
        return round.isNotEmpty()
    }
    fun issue(value: String): String {
        check(!held()) { "A restart confirmation already owns recovery" }
        require(value.matches(Regex("[0-9a-f]{32}")))
        round = value; issuedAt = now(); consumed = false
        return value
    }
    fun consume(value: String, verify: () -> Unit, persist: () -> Unit) {
        check(value == round && held() && !consumed && now() - issuedAt in 0..120000) { "Restart confirmation expired or already used" }
        verify()
        // Persist before exposing permission to dispatch. Failure consumes this round too.
        consumed = true
        persist()
    }
    fun cancel(value: String) {
        if (value == round && !consumed) { round = ""; issuedAt = 0 }
    }
    fun recovered() { round = ""; issuedAt = 0; consumed = false }
}
