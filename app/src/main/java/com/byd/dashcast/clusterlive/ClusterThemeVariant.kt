package com.byd.dashcast.clusterlive

/** Closed, compiled experiments. Neither filenames nor caller-provided hashes select content. */
enum class ClusterThemeVariant(val id: String, val startOperation: Int, val asset: String,
    val carrierSha256: String, val minimumProtocol: Int) {
    MARKER("marker", 1, ClusterThemePolicy.ASSET,
        "e631fe209209cf2e9c9c93df5106ad267e4f38fa223f4ac8d4f3e660f07b614f", 31),
    FULL_SURFACE_TEST("full-surface-test", 5, "cluster_live/full_surface_test.rcc",
        "64a7a752b01e80a822610bf775eb656f79bd3378a6228b6e36c2490932049ddc", 32),
    CORNER_MARKER("corner-marker", 15, "cluster_live/corner_marker.rcc",
        "3f3052c4d79f5c14ed3b708921127ac07f91ac4b03e68c94ea176505b66eb6bb", 38),
    CORNER_MARKER_21F7735C("corner-marker-21f7735c", 18, "cluster_live/corner_marker_21f7735c.rcc",
        "fe069081f1d5c8374276aad2513b8862de61008f67fe6162fb2a4a351e00b734", 39),
    FULL_DASHBOARD_21F7735C("full-dashboard-21f7735c", 19, "cluster_live/full_dashboard_21f7735c.rcc",
        "8a96299b3acc1cffd2eaf2a57cf94cd2ae26dfd90b5fb6204a0182baea6ac250", 43),
    FULL_APP_21F7735C("full-app-21f7735c", 24, "cluster_live/full_app_21f7735c.rcc",
        "b121e6622f560a04a583c352977813ebc69047635f28cc0dee77ffeec78d007b", 44),
    FULLSCREEN_MINIMAL_21F7735C("fullscreen-minimal-21f7735c", 30, "cluster_live/fullscreen_minimal_21f7735c.rcc",
        "50c989cda02fd24b7945517780f76a0b9fee2309b149f977993458fdb81a2cac", 46),
    FULLSCREEN_INSTRUMENTS_21F7735C("fullscreen-instruments-21f7735c", 31, "cluster_live/fullscreen_instruments_21f7735c.rcc",
        "f8c0d5df43b207258d470b389648ef400cf2bd179205af39dce4849749c468ba", 47);

    val isDashboard: Boolean get() = this == FULL_DASHBOARD_21F7735C || this == FULL_APP_21F7735C ||
        this == FULLSCREEN_MINIMAL_21F7735C || this == FULLSCREEN_INSTRUMENTS_21F7735C
    val isFullApp: Boolean get() = this == FULL_APP_21F7735C
    val isFullScreen: Boolean get() = this == FULL_APP_21F7735C || this == FULLSCREEN_MINIMAL_21F7735C ||
        this == FULLSCREEN_INSTRUMENTS_21F7735C
    // Keep the historical id/hash and debt schema readable, but never load it again.
    val isRecoveryOnly: Boolean get() = this == FULL_APP_21F7735C
    val isMarker: Boolean get() = this != FULL_SURFACE_TEST && !isDashboard
    val isCorner: Boolean get() = this == CORNER_MARKER || this == CORNER_MARKER_21F7735C
    val isPersistent: Boolean get() = isCorner || isDashboard

    companion object {
        fun isCornerId(id: String): Boolean = values().any { it.id == id && it.isCorner }
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
