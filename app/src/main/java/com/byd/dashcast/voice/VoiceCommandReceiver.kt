package com.byd.dashcast.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.ui.diag.DiagActivity
import com.byd.dashcast.ui.log.LogActivity

/**
 * Receives [VoiceCommandRouter.ACTION_VOICE_COMMAND] local broadcasts and
 * translates each command string into the appropriate [Host] action.
 *
 * Register / unregister via LocalBroadcastManager in onStart / onStop.
 */
class VoiceCommandReceiver(private val mHost: Host) : BroadcastReceiver() {

    /** Callback surface implemented by the hosting Activity (see MainActivity). */
    interface Host {
        fun isActivityAlive(): Boolean
        fun activateCluster()
        fun restoreBydDashboard()
        fun startActivity(intent: Intent)
        fun quickSwitchToApp(pkg: String)
    }

    // intent is nullable: the platform can deliver a null Intent (the Java guarded it).
    // context is never null-checked in the original and the framework always supplies one.
    override fun onReceive(context: Context, intent: Intent?) {
        if (!mHost.isActivityAlive() || intent == null) return
        val cmd = intent.getStringExtra(VoiceCommandRouter.EXTRA_CMD) ?: return

        when (cmd) {
            VoiceCommandRouter.CMD_CLUSTER_ON  -> mHost.activateCluster()
            VoiceCommandRouter.CMD_CLUSTER_OFF -> mHost.restoreBydDashboard()
            VoiceCommandRouter.CMD_OPEN_DIAG   -> mHost.startActivity(Intent(context, DiagActivity::class.java))
            VoiceCommandRouter.CMD_OPEN_LOGS   -> mHost.startActivity(Intent(context, LogActivity::class.java))
            VoiceCommandRouter.CMD_LAUNCH_ON_CLUSTER ->
                intent.getStringExtra(VoiceCommandRouter.EXTRA_PKG)?.let { mHost.quickSwitchToApp(it) }
            else -> AppLogger.d(TAG, "Voice: unhandled cmd=$cmd")
        }
    }

    companion object {
        private const val TAG = "VoiceCommandReceiver"
    }
}
