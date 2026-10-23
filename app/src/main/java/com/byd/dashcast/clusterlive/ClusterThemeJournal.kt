package com.byd.dashcast.clusterlive

import org.json.JSONObject

/** Validate recovery metadata before it can select a transport or release admission. */
internal object ClusterThemeJournal {
    data class Recovery(val transport: ClusterThemeTransport, val variant: ClusterThemeVariant,
        val clean: Boolean, val filename: String, val root: String, val renderer: String, val identity: String,
        val mutated: Boolean, val helperPid: Int, val helperStart: String, val helperToken: String,
        val device: Long, val inode: Long, val iviRecoverySettled: Boolean)

    fun recover(j: JSONObject): Recovery {
        val schema = j.get("schema")
        val transport = ClusterThemeTransport.recover(schema, if (j.has("transport")) j.get("transport") else null)
        val variant = ClusterThemeVariant.recover(if (transport == ClusterThemeTransport.ANDROID_IVI) 2 else schema,
            if (j.has("variant")) j.getString("variant") else null,
            if (j.has("carrierSha256")) j.getString("carrierSha256") else null)
        val clean = j.getString("state") == "CLEAN"
        val filename = j.getString("filename"); require(ClusterThemePolicy.ownedName(filename))
        require(j.get("mutated") is Boolean)
        require(j.get("helperPid") is Int || j.get("helperPid") is Long)
        val pid = j.getLong("helperPid"); require(pid in 0..Int.MAX_VALUE)
        val start = j.getString("helperStart"); val token = j.getString("helperToken")
        require(pid == 0L && start.isEmpty() && (token.isEmpty() || token.matches(Regex("[0-9a-f]{32}"))) ||
            pid > 0 && start.matches(Regex("[0-9]+")) && token.matches(Regex("[0-9a-f]{32}")))
        require(!clean || pid == 0L && start.isEmpty() && token.isEmpty()) { "Clean journal retains a helper" }
        val root = if (transport == ClusterThemeTransport.ANDROID_IVI) j.getString("root") else ""
        val identity = j.optString("identity")
        val device = j.optLong("createdDevice", -1); val inode = j.optLong("createdInode", -1)
        if (transport == ClusterThemeTransport.ANDROID_IVI) {
            require(root.isEmpty() && identity.isEmpty() || root == "/storage/emulated/0/.cluster/translate/" &&
                identity.matches(Regex("[0-9]+:[0-9]+(/[0-9]+:[0-9]+){2}"))) { "Invalid Android directory identity" }
            require(!j.getBoolean("mutated") || root.isNotEmpty()) { "Mutated IVI journal has no pinned directory" }
            require(device == -1L && inode == -1L || device >= 0 && inode >= 0) { "Incomplete staged object identity" }
            require(!j.getBoolean("mutated") || device >= 0 && inode >= 0) { "Mutated IVI journal has no staged object identity" }
        }
        require(!j.has("iviRecoverySettled") || j.get("iviRecoverySettled") is Boolean)
        val recoverySettled = j.optBoolean("iviRecoverySettled")
        require(!recoverySettled || transport == ClusterThemeTransport.ANDROID_IVI && j.getBoolean("mutated")) {
            "Recovery proof requires an admitted Android IVI load"
        }
        return Recovery(transport, variant, clean, filename, root, j.optString("renderer"), identity,
            j.getBoolean("mutated"), pid.toInt(), start, token, device, inode, recoverySettled)
    }
}
