package com.byd.dashcast.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.ui.diag.DiagActivity;
import com.byd.dashcast.ui.log.LogActivity;

/**
 * Receives {@link VoiceCommandRouter#ACTION_VOICE_COMMAND} local broadcasts and
 * translates each command string into the appropriate Host action.
 *
 * Register / unregister via LocalBroadcastManager in onStart / onStop.
 */
public final class VoiceCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "VoiceCommandReceiver";

    public interface Host {
        boolean isActivityAlive();
        void activateCluster();
        void restoreBydDashboard();
        void startActivity(Intent intent);
        void quickSwitchToApp(String pkg);
    }

    private final Host mHost;

    public VoiceCommandReceiver(Host host) {
        mHost = host;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!mHost.isActivityAlive() || intent == null) return;
        String cmd = intent.getStringExtra(VoiceCommandRouter.EXTRA_CMD);
        if (cmd == null) return;

        if (VoiceCommandRouter.CMD_CLUSTER_ON.equals(cmd)) {
            mHost.activateCluster();
        } else if (VoiceCommandRouter.CMD_CLUSTER_OFF.equals(cmd)) {
            mHost.restoreBydDashboard();
        } else if (VoiceCommandRouter.CMD_OPEN_DIAG.equals(cmd)) {
            mHost.startActivity(new Intent(context, DiagActivity.class));
        } else if (VoiceCommandRouter.CMD_OPEN_LOGS.equals(cmd)) {
            mHost.startActivity(new Intent(context, LogActivity.class));
        } else if (VoiceCommandRouter.CMD_LAUNCH_ON_CLUSTER.equals(cmd)) {
            String pkg = intent.getStringExtra(VoiceCommandRouter.EXTRA_PKG);
            if (pkg != null) mHost.quickSwitchToApp(pkg);
        } else {
            AppLogger.d(TAG, "Voice: unhandled cmd=" + cmd);
        }
    }
}
