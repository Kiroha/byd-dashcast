package com.byd.dashcast.clusterlive

/** A short-lived visual question after one read-only query; never recovery or load-settlement proof. */
internal data class ClusterCornerVerification(val filename: String, val generation: Long, val round: String,
    val expiresElapsedMs: Long, val observed: Boolean = false) {
    fun matches(name: String, answerRound: String, currentGeneration: Long, now: Long): Boolean =
        !observed && name == filename && answerRound == round && currentGeneration == generation && now < expiresElapsedMs

    companion object {
        fun issue(name: String, generation: Long, round: String, now: Long, selection: ClusterThemeProtocol.Response): ClusterCornerVerification {
            require(round.matches(Regex("[0-9a-f]{32}")) && selection.selected(name) && selection.ints[3] == 0)
            return ClusterCornerVerification(name, generation, round, now + 60000)
        }
    }
}
