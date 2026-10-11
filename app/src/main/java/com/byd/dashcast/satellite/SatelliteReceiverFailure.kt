package com.byd.dashcast.satellite

/** Finite diagnostic codes only: never exception messages, credentials or source payloads. */
enum class SatelliteReceiverFailure {
    FOREGROUND, KEYSTORE, KEY_GENERATION, KEY_MANAGER, TLS_CONTEXT,
    LISTENER_BIND, LISTENER_PERMISSION, CONTROL_TRANSPORT, STARTUP_TIMEOUT, SHUTDOWN
}
