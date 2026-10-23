package com.byd.dashcast.clusterlive

/** Journal schemas identify the admission contract; an old renderer journal is never an IVI lease. */
internal enum class ClusterThemeTransport(val id: String) {
    STRICT_RENDERER("strict-renderer"), ANDROID_IVI("android-ivi");

    companion object {
        fun recover(schema: Any?, transport: Any?): ClusterThemeTransport {
            require(schema is Int || schema is Long) { "Journal schema must be an integer" }
            return when ((schema as Number).toLong()) {
                1L, 2L -> { require(transport == null); STRICT_RENDERER }
                3L, 4L -> { require(transport == ANDROID_IVI.id); ANDROID_IVI }
                else -> error("Unknown transport journal schema")
            }
        }
    }
}
