package com.byd.dashcast.clusterlive

/** Closed, compiled experiments. Neither filenames nor caller-provided hashes select content. */
enum class ClusterThemeVariant(val id: String, val startOperation: Int, val asset: String,
    val carrierSha256: String, val minimumProtocol: Int) {
    MARKER("marker", 1, ClusterThemePolicy.ASSET,
        "e631fe209209cf2e9c9c93df5106ad267e4f38fa223f4ac8d4f3e660f07b614f", 31),
    FULL_SURFACE_TEST("full-surface-test", 5, "cluster_live/full_surface_test.rcc",
        "64a7a752b01e80a822610bf775eb656f79bd3378a6228b6e36c2490932049ddc", 32),
    CORNER_MARKER("corner-marker", 15, "cluster_live/corner_marker.rcc",
        "3f3052c4d79f5c14ed3b708921127ac07f91ac4b03e68c94ea176505b66eb6bb", 38);

    val isMarker: Boolean get() = this != FULL_SURFACE_TEST

    companion object {
        fun recover(schema: Any?, id: String?, carrierSha256: String?): ClusterThemeVariant {
            require(schema is Int || schema is Long) { "Journal schema must be an integer" }
            return when ((schema as Number).toLong()) {
            1L -> {
                require(id == null && carrierSha256 == null) { "Legacy marker journal has unexpected profile fields" }
                MARKER
            }
            2L -> {
                val profile = values().singleOrNull { it.id == id } ?: error("Unknown journal profile")
                require(profile.carrierSha256 == carrierSha256) { "Journal profile/hash mismatch" }
                profile
            }
            else -> error("Unknown journal schema")
            }
        }
    }
}
