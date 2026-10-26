package com.byd.dashcast.clusterlive

import java.io.File

internal object ClusterBootIdentity {
    fun read(): String = File("/proc/sys/kernel/random/boot_id").readText().trim().also {
        check(it.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) { "Current boot identity unavailable" }
    }
}
