package com.byd.dashcast.proxy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DaemonBroadcastRegistrarTest {

    @Test
    fun `daemon receivers require a shell-held sender permission`() {
        var capturedReceiver: BroadcastReceiver? = null
        var capturedFilter: IntentFilter? = null
        var capturedPermission: String? = null
        val context = object : ContextWrapper(null) {
            override fun registerReceiver(
                receiver: BroadcastReceiver?,
                filter: IntentFilter?,
                broadcastPermission: String?,
                scheduler: Handler?
            ): Intent? {
                capturedReceiver = receiver
                capturedFilter = filter
                capturedPermission = broadcastPermission
                return null
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = Unit
        }
        val filter = IntentFilter("daemon.ready")

        DaemonBroadcastRegistrar.register(context, receiver, filter)

        assertSame(receiver, capturedReceiver)
        assertSame(filter, capturedFilter)
        assertEquals(Manifest.permission.DUMP, capturedPermission)
    }
}