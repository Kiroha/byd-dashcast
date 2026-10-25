package com.byd.dashcast.clusterlive

import org.json.JSONObject

/** Validate recovery metadata before it can select a transport or release admission. */
internal object ClusterThemeJournal {
    data class Recovery(val transport: ClusterThemeTransport, val variant: ClusterThemeVariant,
        val clean: Boolean, val filename: String, val root: String, val renderer: String, val identity: String,
        val mutated: Boolean, val helperPid: Int, val helperStart: String, val helperToken: String,
        val device: Long, val inode: Long, val iviRecoverySettled: Boolean, val retirement: ClusterThemeRetirement?,
        val dashboardStage: String = "NONE", val dashboardNativeDebt: Boolean = false, val dashboardHelperCommand: Int = -1,
        val dashboardHelperPid: Int = 0, val dashboardHelperStart: String = "", val dashboardHelperToken: String = "",
        val dashboardBootstrapStarted: Boolean = false, val dashboardSizeAdmitted: Boolean? = null,
        val dashboardBootstrapRecoverySettled: Boolean = false, val dashboardSmallPanelSeen: Boolean = false,
        val fullAppAdasDebt: Boolean = false, val fullAppAdasRound: String = "",
        val factoryApplyPending: Boolean = false, val factoryApplyCompleted: Boolean = false)

    fun recover(j: JSONObject): Recovery {
        val schema = j.get("schema")
        val transport = ClusterThemeTransport.recover(schema, if (j.has("transport")) j.get("transport") else null)
        val variant = ClusterThemeVariant.recover(if (transport == ClusterThemeTransport.ANDROID_IVI) 2 else schema,
            if (j.has("variant")) j.getString("variant") else null,
            if (j.has("carrierSha256")) j.getString("carrierSha256") else null)
        require(!variant.isPersistent || transport == ClusterThemeTransport.ANDROID_IVI) { "Persistent profile is Android IVI only" }
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
        val retirement = if ((schema as Number).toLong() == 4L) {
            require(transport == ClusterThemeTransport.ANDROID_IVI && j.getBoolean("mutated"))
            ClusterThemeRetirement.parse(j.getJSONObject("retirement")).also {
                require(!recoverySettled && !clean || it.moved) { "Recovery requires completed retirement" }
                require(it.device == device && it.inode != inode) { "Retirement must remain on the carrier filesystem" }
                require(!clean || recoverySettled) { "Retired CLEAN journal requires recovery proof" }
            }
        } else { require(!j.has("retirement")); null }
        val dashboard = if (variant.isDashboard) j.getJSONObject("dashboard") else null
        dashboard?.let {
            require(it.get("stage") is String && it.get("nativeDebt") is Boolean)
            for (key in listOf("helperPid", "helperCommand")) require(it.get(key) is Int || it.get(key) is Long)
            require(it.getLong("helperPid") in 0..Int.MAX_VALUE.toLong())
            require(it.get("helperStart") is String && it.get("helperToken") is String)
        }
        val stage = dashboard?.getString("stage") ?: "NONE"
        val nativeDebt = dashboard?.getBoolean("nativeDebt") ?: false
        val nativeCommand = dashboard?.getInt("helperCommand") ?: -1
        val nativePid = dashboard?.getInt("helperPid") ?: 0
        val nativeStart = dashboard?.getString("helperStart") ?: ""
        val nativeToken = dashboard?.getString("helperToken") ?: ""
        for (key in listOf("bootstrapStarted", "bootstrapRecoverySettled", "smallPanelSeen"))
            require(dashboard?.has(key) != true || dashboard.get(key) is Boolean)
        require(dashboard?.has("sizeAdmitted") != true || dashboard.isNull("sizeAdmitted") || dashboard.get("sizeAdmitted") is Boolean)
        val bootstrap = dashboard?.optBoolean("bootstrapStarted") ?: false
        val sizeAdmitted = if (dashboard?.has("sizeAdmitted") == true && !dashboard.isNull("sizeAdmitted")) dashboard.getBoolean("sizeAdmitted") else null
        val bootstrapSettled = dashboard?.optBoolean("bootstrapRecoverySettled") ?: false
        val small = dashboard?.optBoolean("smallPanelSeen") ?: false
        require(!bootstrapSettled || bootstrap && !j.getBoolean("mutated") && !nativeDebt && nativePid == 0)
        require(!bootstrap || j.getBoolean("mutated") || sizeAdmitted != true)
        require(!clean || !bootstrap || j.getBoolean("mutated") || bootstrapSettled)
        require(stage in setOf("NONE", "OPENING", "OPEN_RECEIVED", "CLOSING", "CLOSED_RECEIVED", "UNKNOWN"))
        require(stage !in setOf("OPENING", "OPEN_RECEIVED", "CLOSING") || nativeDebt)
        require(nativePid == 0 && nativeStart.isEmpty() && nativeToken.isEmpty() && nativeCommand == -1 ||
            nativePid > 0 && nativeStart.matches(Regex("[0-9]+")) && nativeToken.matches(Regex("[0-9a-f]{32}")) &&
            variant.isDashboard && ClusterDashboardProfile.permitsCommand(nativeCommand) && nativeDebt)
        require(!clean || !nativeDebt && nativePid == 0)
        val adas = if (variant.isFullApp) j.getJSONObject("fullAppAdas") else null
        require(adas == null || adas.get("debt") is Boolean && adas.get("round") is String)
        val adasDebt = adas?.getBoolean("debt") ?: false
        val adasRound = adas?.getString("round") ?: ""
        require(adasRound.isEmpty() && !adasDebt || adasRound.matches(Regex("[0-9a-f]{32}")))
        require(!clean || !adasDebt) { "Clean journal retains full-app ADAS recovery debt" }
        for (key in listOf("factoryApplyPending", "factoryApplyCompleted"))
            require(!j.has(key) || variant.isDashboard && j.get(key) is Boolean)
        val factoryPending = j.optBoolean("factoryApplyPending")
        val factoryCompleted = j.optBoolean("factoryApplyCompleted")
        require(!clean || !factoryPending)
        return Recovery(transport, variant, clean, filename, root, j.optString("renderer"), identity,
            j.getBoolean("mutated"), pid.toInt(), start, token, device, inode, recoverySettled, retirement, stage, nativeDebt, nativeCommand,
            nativePid, nativeStart, nativeToken, bootstrap, sizeAdmitted, bootstrapSettled, small, adasDebt, adasRound,
            factoryPending, factoryCompleted)
    }
}
