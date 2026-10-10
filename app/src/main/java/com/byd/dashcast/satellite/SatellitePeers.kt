package com.byd.dashcast.satellite

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.util.Locale

/** Sender-reported display metadata; possession of the shared token is the authentication boundary. */
internal data class SatellitePeerIdentity(val id: String, val name: String)

/** Current connection in RAM and last authenticated connection in backup-excluded receiver settings. */
internal object SatellitePeers {
    private const val LAST = "last_authenticated_peer"
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    data class Peer(val session: String?, val identity: SatellitePeerIdentity?, val address: String?, val connectedAtMs: Long)
    data class Snapshot(val current: Peer?, val last: Peer?)
    private var current: Peer? = null

    fun parse(value: Any?): SatellitePeerIdentity? {
        val json = value as? JSONObject ?: return null
        val id = json.opt("id") as? String ?: return null
        val name = json.opt("name") as? String ?: return null
        if (!uuid.matches(id) || name.length !in 1..80 || name.isBlank() || name != name.trim() ||
            name.toByteArray(Charsets.UTF_8).size > 160 || name.any {
                Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
                    Character.isSurrogate(it) || (it.isWhitespace() && it != ' ')
            }) return null
        return SatellitePeerIdentity(id, name)
    }

    fun receiverId(certificateSha256: String): String {
        require(certificateSha256.matches(Regex("[0-9a-f]{64}")))
        return certificateSha256.take(16).uppercase(Locale.ROOT).chunked(4).joinToString("-")
    }

    /** The same monitor fences token rotation, receiver disabling and this history write. */
    fun authenticated(context: Context, expectedToken: String, session: String,
        identity: SatellitePeerIdentity?, address: String?): Boolean = synchronized(SatellitePrefs) {
        if (!SatellitePrefs.isEnabled(context) || !SatellitePrefs.tokenMatches(SatellitePrefs.token(context), expectedToken))
            return@synchronized false
        val safeAddress = numericAddress(address)
        val peer = Peer(session, identity, safeAddress, System.currentTimeMillis())
        val json = JSONObject().put("connectedAtMs", peer.connectedAtMs)
        identity?.let { json.put("device", JSONObject().put("id", it.id).put("name", it.name)) }
        safeAddress?.let { json.put("address", it) }
        context.getSharedPreferences(SatellitePrefs.FILE, Context.MODE_PRIVATE).edit { putString(LAST, json.toString()) }
        current = peer
        true
    }

    fun disconnected(session: String) = synchronized(SatellitePrefs) {
        if (current?.session == session) current = null
    }

    /** Called with the shared preference monitor held. Disabling keeps history; revocation clears it. */
    fun clear(context: Context, history: Boolean) = synchronized(SatellitePrefs) {
        current = null
        if (history) context.getSharedPreferences(SatellitePrefs.FILE, Context.MODE_PRIVATE).edit { remove(LAST) }
    }

    fun snapshot(context: Context): Snapshot = synchronized(SatellitePrefs) {
        val last = try {
            val text = context.getSharedPreferences(SatellitePrefs.FILE, Context.MODE_PRIVATE).getString(LAST, null)
            text?.takeIf { it.length <= 1024 }?.let {
                val json = JSONObject(it)
                val date = json.getLong("connectedAtMs")
                require(date > 0)
                val address = numericAddress(json.opt("address") as? String)
                Peer(null, parse(json.opt("device")), address, date)
            }
        } catch (_: Exception) { null }
        Snapshot(current.takeIf { SatellitePrefs.isEnabled(context) }, last)
    }

    private fun numericAddress(address: String?): String? = address?.substringBefore('%')?.takeIf {
        it.length in 2..45 && it.matches(Regex("[0-9a-fA-F:.]+"))
    }
}
