package com.byd.dashcast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.byd.dashcast.R
import com.byd.dashcast.update.OtaRelaunchCoordinator
import com.byd.dashcast.util.AppLogger

/**
 * Receives the result of a PackageInstaller session commit.
 * With INSTALL_PACKAGES (granted via platform.keystore), the install is silent.
 * STATUS_PENDING_USER_ACTION is the fallback when silent install is not permitted.
 */
@Suppress("DEPRECATION")
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                AppLogger.i(TAG, "OTA install successful")
                OtaRelaunchCoordinator.relaunchIfPending(
                    context.applicationContext,
                    "PackageInstaller.STATUS_SUCCESS"
                )
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Silent install not permitted — show system install dialog to the user
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }
            else -> {
                OtaRelaunchCoordinator.clearPending(context)
                val displayMsg = if (!message.isNullOrEmpty()) message else "code=$status"
                AppLogger.e(TAG, "OTA install failed: status=$status msg=$message")
                Toast.makeText(context, context.getString(R.string.toast_update_install_failed) + displayMsg,
                        Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
    }
}
