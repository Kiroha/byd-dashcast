package com.byd.dashcast.satellite

import android.content.Context
import com.byd.dashcast.hud.HudController
import com.byd.dashcast.hud.HudNavigationData

/** Serializes source selection with writes. Existing installations always select local input. */
object NavigationInputRouter {
    private var remoteSession: String? = null
    private var revision = 0L

    @Synchronized
    fun remoteRevision(): Long = revision

    @Synchronized
    fun acquireRemote(ctx: Context, session: String) {
        if (remoteSession != null && remoteSession != session && SatellitePrefs.usesRemoteGuidance(ctx)) {
            HudController.closeNavigation(ctx)
        }
        remoteSession = session
        revision++
    }
    @Synchronized
    fun updateLocal(ctx: Context, data: HudNavigationData): Boolean =
        if (SatellitePrefs.usesRemoteGuidance(ctx)) false else HudController.updateNavigation(ctx, data)

    @Synchronized
    fun noteLocal(ctx: Context) {
        if (!SatellitePrefs.usesRemoteGuidance(ctx)) HudController.noteNavFrameSeen()
    }

    @Synchronized
    fun closeLocal(ctx: Context) {
        if (!SatellitePrefs.usesRemoteGuidance(ctx)) HudController.closeNavigation(ctx)
    }

    @Synchronized
    fun updateRemote(ctx: Context, session: String, expectedRevision: Long, data: HudNavigationData,
        accepted: () -> Unit = {}): Boolean =
        if (remoteSession == session && revision == expectedRevision && SatellitePrefs.usesRemoteGuidance(ctx)) {
            // Receipt is distinct from OEM delivery. Publish only inside the source/revision guard.
            accepted()
            HudController.updateNavigation(ctx, data)
        } else false

    @Synchronized
    fun closeRemote(ctx: Context, session: String, release: Boolean = false) {
        if (remoteSession != session) return
        if (SatellitePrefs.usesRemoteGuidance(ctx)) HudController.closeNavigation(ctx)
        if (release) { remoteSession = null; revision++ }
    }

    @Synchronized
    fun selectRemote(ctx: Context, remote: Boolean) {
        if (SatellitePrefs.usesRemoteGuidance(ctx) != (remote && SatellitePrefs.isEnabled(ctx))) {
            HudController.closeNavigation(ctx)
        }
        SatellitePrefs.setRemoteGuidance(ctx, remote)
        revision++
        SatelliteStatus.sourceChanged()
    }

    @Synchronized
    fun enableReceiver(ctx: Context, enabled: Boolean) {
        // Close the resource owned by the OLD selection before changing the preferences.
        if (SatellitePrefs.isEnabled(ctx) != enabled &&
            ctx.getSharedPreferences(SatellitePrefs.FILE, 0).getBoolean("remote_guidance", false)) {
            HudController.closeNavigation(ctx)
        }
        SatellitePrefs.setEnabled(ctx, enabled)
        revision++
        SatelliteStatus.sourceChanged()
    }
}
