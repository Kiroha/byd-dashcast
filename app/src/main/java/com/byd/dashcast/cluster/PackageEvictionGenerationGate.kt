package com.byd.dashcast.cluster

/** Serializes a package relaunch against an already-dispatched eviction force-stop. */
internal class PackageEvictionGenerationGate {
    data class Token(val packageName: String, val generation: Long)
    data class Completion(val deferredLaunch: Runnable?)

    private data class State(
        var generation: Long = 0L,
        var destructiveGeneration: Long? = null,
        var deferredLaunch: Runnable? = null,
    )

    private val states = HashMap<String, State>()
    private var nextGeneration = 0L

    @Synchronized
    fun beginEviction(packageName: String): Token {
        val state = states.getOrPut(packageName) { State() }
        state.generation = ++nextGeneration
        return Token(packageName, state.generation)
    }

    /** Returns true when the launch can run now, false when force-stop completion owns it. */
    @Synchronized
    fun prepareLaunch(packageName: String, launch: Runnable): Boolean {
        val state = states.getOrPut(packageName) { State() }
        state.generation = ++nextGeneration
        if (state.destructiveGeneration == null) return true
        state.deferredLaunch = launch
        return false
    }

    @Synchronized
    fun isCurrent(token: Token): Boolean =
        states[token.packageName]?.generation == token.generation

    @Synchronized
    fun runIfCurrent(token: Token, action: Runnable): Boolean {
        if (!isCurrent(token)) return false
        action.run()
        return true
    }

    @Synchronized
    fun tryBeginDestructive(token: Token): Boolean {
        val state = states[token.packageName] ?: return false
        if (state.generation != token.generation || state.destructiveGeneration != null) {
            return false
        }
        state.destructiveGeneration = token.generation
        return true
    }

    @Synchronized
    fun finishDestructive(token: Token, onCurrent: Runnable): Completion? {
        val state = states[token.packageName] ?: return null
        if (state.destructiveGeneration != token.generation) return null
        if (state.generation == token.generation) onCurrent.run()
        state.destructiveGeneration = null
        val deferred = state.deferredLaunch
        state.deferredLaunch = null
        return Completion(deferred)
    }
}