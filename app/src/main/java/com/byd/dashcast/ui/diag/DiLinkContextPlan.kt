package com.byd.dashcast.ui.diag

/** Closed metadata plan. There is no shell command input in this feature. */
internal object DiLinkContextPlan {
    enum class Kind { ID, EPOCH, SERVICES, PROPERTY, PACKAGE_PATH, PACKAGE_DUMP }
    data class Command(val key: String, val label: String, val argv: List<String>, val kind: Kind,
                       val subject: String = "") {
        val shell: String get() = argv.joinToString(" ")
    }
    val commands: List<Command> = buildList {
        add(Command("shell_id", "Identité du transport", listOf("id"), Kind.ID))
        add(Command("shell_date_epoch", "Horloge de la voiture", listOf("date", "+%s"), Kind.EPOCH))
        add(Command("service_list", "Liste des services", listOf("service", "list"), Kind.SERVICES))
        for ((property, label) in listOf(
            "ro.build.type" to "Type de système", "ro.debuggable" to "Mode de débogage",
            "sys.byd.countrycode" to "Code pays", "apps.setting.product.inswver" to "Version système",
            "ro.build.region" to "Région système", "persist.sys.timezone" to "Fuseau horaire",
            "persist.sys.locale" to "Langue du système")) {
            add(Command("getprop_${property.replace('.', '_')}", label,
                listOf("getprop", property), Kind.PROPERTY, property))
        }
        for ((name, label) in listOf("com.byd.byddevelopmenttools" to "Outils OEM",
                                     "com.byd.authservice" to "AuthService")) {
            val key = if (name.endsWith("authservice")) "authservice" else "tools"
            add(Command("pm_path_$key", "$label : chemins APK", listOf("pm", "path", name), Kind.PACKAGE_PATH, name))
            add(Command("dumpsys_package_$key", "$label : métadonnées", listOf("dumpsys", "package", name), Kind.PACKAGE_DUMP, name))
        }
    }
    const val COMMAND_TIMEOUT_MS = 30_000L
    const val TRANSPORT_TIMEOUT_MS = 35_000L
    const val MAX_OUTPUT_BYTES = 256 * 1024
    const val OUTPUT_DESCRIPTION = "Proxy TXN_EXEC: stdout and stderr combined, UTF-8 decoded, trailing CR/LF removed; not original separate byte streams."

    data class Interpretation(val status: String, val reason: String = "", val values: Map<String, Any?> = emptyMap())

    fun interpret(command: Command, exit: Int?, output: String, transportError: String = ""): Interpretation {
        if (transportError.isNotEmpty() || exit == null) return Interpretation("transport_error", transportError.ifEmpty { "Missing exit code" })
        if (output.endsWith("[output truncated]")) return Interpretation("truncated", "Transport output limit reached; text is incomplete")
        if (exit == -1 && output.startsWith("ERR timeout ")) return Interpretation("timeout", output)
        if (exit != 0) return Interpretation("command_error", "Exit code $exit")
        // Android commands can report failures on stdout with exit 0. Preserve that distinction.
        if (Regex("(?im)^\\s*(?:ERR |Error:|Permission (?:Denial|denied)|SecurityException|java\\.lang\\.SecurityException|/system/bin/sh:|getprop:|Can't find service:|cmd: Can't find service:)").containsMatchIn(output)) {
            return Interpretation("diagnostic_output", "Command returned a diagnostic despite exit 0")
        }
        val value = output.trim()
        return when (command.kind) {
            Kind.ID -> Regex("\\buid=([0-9]+)").find(value)?.let {
                Interpretation("ok", values = mapOf("observed_uid" to it.groupValues[1]))
            } ?: Interpretation("unexpected_output", "No UID recognized; transport privilege remains unknown")
            Kind.EPOCH -> if (Regex("-?[0-9]{1,13}").matches(value))
                Interpretation("ok", values = mapOf("vehicle_epoch_seconds" to value.toLong(),
                    "interpretation" to "Device clock reading; no independently trusted PC clock or clock offset is inferred."))
                else Interpretation("unexpected_output", "date +%s did not return an epoch integer")
            Kind.PROPERTY -> when {
                value.contains('\n') || value.contains('\r') -> Interpretation("unexpected_output", "Multiple property lines")
                value.isEmpty() -> Interpretation("value_absent", "Empty is unknown; no locale, country or property default is inferred",
                    mapOf("property" to command.subject, "value" to ""))
                else -> Interpretation("ok", values = mapOf("property" to command.subject, "value" to value))
            }
            Kind.PACKAGE_PATH -> if (value.isNotEmpty() && value.lines().all { it.startsWith("package:/") })
                Interpretation("ok") else Interpretation("package_path_unavailable", "Package path was not returned")
            Kind.PACKAGE_DUMP -> if (output.contains("Package [${command.subject}]"))
                Interpretation("ok", values = mapOf("interpretation" to "Package metadata only; activity access and effective permissions are not established."))
                else Interpretation("package_metadata_unavailable", "Expected package marker absent")
            Kind.SERVICES -> interpretServices(value)
        }
    }

    private fun interpretServices(value: String): Interpretation {
        val lines = value.lines().filter { it.isNotBlank() }.map { it.trim() }
        val header = lines.firstOrNull()?.let { Regex("Found ([0-9]+) services:").matchEntire(it) }
        val rows = lines.drop(1).map { Regex("([0-9]+)\\s+([^:\\s]+): \\[([^\\]\\r\\n]*)\\]").matchEntire(it) }
        if (header == null || header.groupValues[1].toIntOrNull() != rows.size || rows.any { it == null })
            return Interpretation("unexpected_output", "Service list format/count not recognized")
        return Interpretation("ok", values = mapOf("observed_services" to rows.map { row ->
            mapOf("index" to row!!.groupValues[1], "name" to row.groupValues[2], "interface_descriptor" to row.groupValues[3])
        }, "interpretation" to "Returned listing only; invocation rights, implementation and global absence are not established."))
    }
}
