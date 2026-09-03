package com.byd.dashcast.cluster

/** Owns package mutation, snapshot, and persistence as one ordered critical section. */
internal class PersistedPackageSet(
    private val writer: (Set<String>) -> Unit,
) {
    private val values = LinkedHashSet<String>()

    @Synchronized
    fun add(value: String) {
        values.add(value)
        writer(HashSet(values))
    }

    @Synchronized
    fun remove(value: String) {
        values.remove(value)
        writer(HashSet(values))
    }

    @Synchronized
    fun contains(value: String): Boolean = values.contains(value)

    @Synchronized
    fun snapshot(): List<String> = ArrayList(values)
}