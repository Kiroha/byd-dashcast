package com.byd.dashcast.cluster.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.IBinder

import com.byd.dashcast.util.AppLogger

import java.lang.reflect.Method

/**
 * DashboardLauncher — launches any application on the cluster (secondary) display.
 *
 * On Android 10 (API 29), ActivityOptions.setLaunchDisplayId() exists but is @hide.
 * It is called via reflection, which works without root in an app signed with the
 * platform key (platform.keystore in our case).
 *
 * Main method: launchOnMainDisplay(packageName) — moves an app back to the main display (display 0).
 */
@Suppress("DEPRECATION")
class DashboardLauncher(context: Context) {

    private val mContext: Context = context.applicationContext
    private var mDashboardDisplayId = -1

    fun setDashboardDisplayId(displayId: Int) {
        mDashboardDisplayId = displayId
        AppLogger.log(TAG, "Cluster display registered: id=$displayId")
    }

    fun getDashboardDisplayId(): Int = mDashboardDisplayId

    /** Launches an app on the main display (display ID 0). */
    fun launchOnMainDisplay(packageName: String): Boolean {
        val launchIntent = mContext.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            AppLogger.e(TAG, "App not installed or not found: $packageName")
            return false
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        // Display 0 = main screen
        return launchWithDisplayId(launchIntent, 0)
    }

    /**
     * Core mechanism: calls ActivityOptions.setLaunchDisplayId() via reflection.
     * Accessible from an app signed with platform.keystore without additional permissions.
     * Tries the direct path (Context.startActivity) THEN the IActivityManager path.
     */
    private fun launchWithDisplayId(intent: Intent, displayId: Int): Boolean {
        // Remove the FREEFORM "grow-from-zero" animation that stretches the cluster.
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        ensureLaunchMethodsCached()
        try {
            val options = ActivityOptions.makeCustomAnimation(mContext, 0, 0)

            val setDisplayId = sCachedSetLaunchDisplayId
            if (setDisplayId != null) {
                setDisplayId.invoke(options, displayId)
            } else {
                throw NoSuchMethodException("setLaunchDisplayId absent on this ROM")
            }

            // WINDOWING_MODE_FULLSCREEN (1) is rejected by DiLink 3.0 — confirmed by TEST 10.
            // FIX (BYD Dashboard APK v1.10.5): WINDOWING_MODE_FREEFORM (5) + setLaunchBounds(full).
            val setWindowingMode = sCachedSetLaunchWindowingMode
            if (setWindowingMode != null) {
                setWindowingMode.invoke(options, 5) // WINDOWING_MODE_FREEFORM = 5
                AppLogger.i(TAG, "setLaunchWindowingMode(FREEFORM=5) applied")
            } else {
                AppLogger.w(TAG, "setLaunchWindowingMode absent (ROM trop ancienne)")
            }
            // Bounds = actual display dimensions → fills the entire cluster
            val setBounds = sCachedSetLaunchBounds
            if (setBounds != null) {
                val size = Point(1920, 720) // fallback: fission_bg_xdjaVirtualSurface 1920×720 (03/05/2026)
                val dm = mContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                val targetDisplay = dm?.getDisplay(displayId)
                if (targetDisplay != null) {
                    targetDisplay.getRealSize(size)
                    AppLogger.i(TAG, "getRealSize display $displayId → ${size.x}×${size.y}")
                } else {
                    // DL4: getDisplay() is blocked by the OEM DisplayManagerService whitelist even
                    // though the display exists, so prefer the geometry the uid-2000 daemon read
                    // from `dumpsys display` over the 1920×720 literal. Registry is null on
                    // DL3/DL5, where this branch keeps the original fallback verbatim.
                    val info = ClusterDisplayRegistry.forDisplayId(displayId)
                    if (info != null && info.width > 0 && info.height > 0) {
                        size.set(info.width, info.height)
                        AppLogger.i(TAG, "getDisplay($displayId) null → daemon-resolved ${size.x}×${size.y}")
                    } else {
                        AppLogger.w(TAG, "getDisplay($displayId) null → fallback 1920×720")
                    }
                }
                setBounds.invoke(options, Rect(0, 0, size.x, size.y))
                AppLogger.i(TAG, "setLaunchBounds(0,0,${size.x},${size.y}) applied")
            } else {
                AppLogger.w(TAG, "setLaunchBounds absent")
            }

            // Attempt 1: Context.startActivity (works on display 0, may fail on display 1)
            try {
                mContext.startActivity(intent, options.toBundle())
                AppLogger.i(TAG, "Context.startActivity OK display=$displayId")
                AppLogger.log(TAG, "LAUNCH OK display=$displayId")
                return true
            } catch (e1: Exception) {
                AppLogger.w(TAG, "Context.startActivity failed display=$displayId — trying IActivityManager", e1)
            }

            // Attempt 2: IActivityManager.startActivityAsUser (WindowManagement path, userId=-2)
            try {
                val amClass = Class.forName("android.app.IActivityManager\$Stub")
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getDeclaredMethod("getService", String::class.java)
                val iAm = amClass.getMethod("asInterface", IBinder::class.java)
                    .invoke(null, getService.invoke(null, "activity"))
                    ?: throw IllegalStateException("IActivityManager null")

                // startActivityAsUser(null, null, intent, null, null, null, 0, 0, null, optBundle, -2)
                var startActivity = sStartActivityAsUser
                if (startActivity == null) {
                    for (m in iAm.javaClass.methods) {
                        if (m.name == "startActivityAsUser" && m.parameterTypes.size == 11) {
                            startActivity = m
                            break
                        }
                    }
                    sStartActivityAsUser = startActivity
                }
                if (startActivity == null) throw NoSuchMethodException("startActivityAsUser(11)")
                val result = startActivity.invoke(
                    iAm,
                    null, null, intent, null, null, null,
                    0, 0, null,
                    options.toBundle(), -2
                ) as Int
                AppLogger.i(TAG, "IActivityManager.startActivityAsUser result=$result display=$displayId")
                AppLogger.log(TAG, "LAUNCH IActMgr result=$result display=$displayId")
                return result == 0
            } catch (e2: Exception) {
                AppLogger.e(TAG, "IActivityManager.startActivityAsUser failed", e2)
                AppLogger.e(TAG, "LAUNCH IActMgr FAILED — ${e2.javaClass.simpleName}: ${e2.message}")
            }

            return false
        } catch (e: NoSuchMethodException) {
            AppLogger.e(TAG, "setLaunchDisplayId not found on this ROM — falling back to main display", e)
            AppLogger.log(TAG, "LAUNCH FALLBACK — setLaunchDisplayId absent")
            mContext.startActivity(intent)
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error launching on display $displayId", e)
            AppLogger.log(TAG, "LAUNCH EXCEPTION display=$displayId — ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    companion object {
        private const val TAG = "DashboardLauncher"

        // Cached reflection Methods — resolved once, reused on every launch call.
        @Volatile
        private var sLaunchMethodsCached = false
        private var sCachedSetLaunchDisplayId: Method? = null
        private var sCachedSetLaunchWindowingMode: Method? = null // null if absent on this ROM
        private var sCachedSetLaunchBounds: Method? = null // null if absent on this ROM

        @Volatile
        private var sStartActivityAsUser: Method? = null

        @Synchronized
        private fun ensureLaunchMethodsCached() {
            if (sLaunchMethodsCached) return
            try {
                sCachedSetLaunchDisplayId = ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            } catch (ignored: NoSuchMethodException) {
            }
            try {
                sCachedSetLaunchWindowingMode = ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            } catch (ignored: NoSuchMethodException) {
            }
            try {
                sCachedSetLaunchBounds = ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchBounds", Rect::class.java)
                    .apply { isAccessible = true }
            } catch (ignored: NoSuchMethodException) {
            }
            sLaunchMethodsCached = true
        }
    }
}
