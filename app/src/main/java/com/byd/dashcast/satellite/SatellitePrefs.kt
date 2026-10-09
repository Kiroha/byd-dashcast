package com.byd.dashcast.satellite

import android.content.Context
import android.content.SharedPreferences
import android.annotation.SuppressLint
import androidx.core.content.edit
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Installation-local settings. This file is outside the existing backup allowlist. */
object SatellitePrefs {
    internal const val FILE = "dashcast_satellite"
    const val PORT = 47832
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)
    fun usesRemoteGuidance(ctx: Context): Boolean =
        isEnabled(ctx) && prefs(ctx).getBoolean("remote_guidance", false)

    internal fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit { putBoolean("enabled", enabled) }
    }

    internal fun setRemoteGuidance(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit { putBoolean("remote_guidance", enabled) }
    }

    @Synchronized
    fun token(ctx: Context): String = prefs(ctx).getString("token", null) ?: rotateToken(ctx)

    @Synchronized
    @SuppressLint("UseKtx") // Check synchronous persistence before exposing a new credential.
    fun rotateToken(ctx: Context): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        check(prefs(ctx).edit().putString("token", token).commit())
        return token
    }

    fun tokenMatches(expected: String, supplied: String): Boolean =
        supplied.length == expected.length && MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))

    fun observeInput(ctx: Context, changed: () -> Unit): Closeable {
        val preferences = prefs(ctx)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enabled" || key == "remote_guidance") changed()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return Closeable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
