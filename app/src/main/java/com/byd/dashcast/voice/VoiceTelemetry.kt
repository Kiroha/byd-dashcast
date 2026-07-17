package com.byd.dashcast.voice

import java.util.concurrent.atomic.AtomicInteger

internal class VoiceTelemetryGate {
    private val subscribers = AtomicInteger()

    fun acquire() {
        subscribers.incrementAndGet()
    }

    fun release() {
        while (true) {
            val current = subscribers.get()
            if (current == 0 || subscribers.compareAndSet(current, current - 1)) return
        }
    }

    fun isEnabled(): Boolean = subscribers.get() > 0
}

object VoiceTelemetry {
    private val gate = VoiceTelemetryGate()

    @JvmStatic
    fun acquire() = gate.acquire()

    @JvmStatic
    fun release() = gate.release()

    @JvmStatic
    fun isEnabled(): Boolean = gate.isEnabled()
}