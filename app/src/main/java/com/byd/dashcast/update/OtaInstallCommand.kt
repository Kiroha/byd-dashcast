package com.byd.dashcast.update

/** Builds the uid-2000 staging, install, and relaunch shell transaction. */
object OtaInstallCommand {
    private const val STAGED_APK = "/data/local/tmp/dashcast-ota-update.apk"

    @JvmStatic
    fun build(sourcePath: String?, expectedSize: Long, packageName: String?,
              launchComponent: String?): String {
        if (sourcePath.isNullOrEmpty() || expectedSize <= 0L ||
            packageName == null || !packageName.matches(Regex("[A-Za-z0-9_.]+"))) {
            throw IllegalArgumentException("invalid OTA install arguments")
        }
        val source = quote(sourcePath)
        val staged = quote(STAGED_APK)
        val pkg = quote(packageName)
        val launcher = if (launchComponent.isNullOrEmpty())
            "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1"
        else
            "am start -W -f 0x10200000 -n " + quote(launchComponent) +
                " || monkey -p " + pkg +
                " -c android.intent.category.LAUNCHER 1"

        return "OTA_SRC=" + source + "; OTA_TMP=" + staged + "; " +
            "rm -f \"${'$'}OTA_TMP\"; " +
            "if cat \"${'$'}OTA_SRC\" > \"${'$'}OTA_TMP\" 2>/dev/null; then :; " +
            "elif run-as " + pkg + " cat \"${'$'}OTA_SRC\" > \"${'$'}OTA_TMP\" 2>/dev/null; " +
            "then :; else echo 'ERR OTA_STAGE_UNREADABLE'; " +
            "rm -f \"${'$'}OTA_TMP\"; exit 41; fi; " +
            "OTA_SIZE=$(wc -c < \"${'$'}OTA_TMP\" 2>/dev/null | tr -d ' '); " +
            "if [ \"${'$'}OTA_SIZE\" != " + expectedSize + " ]; then " +
            "echo \"ERR OTA_STAGE_SIZE expected=" + expectedSize +
            " actual=${'$'}OTA_SIZE\"; rm -f \"${'$'}OTA_TMP\"; exit 42; fi; " +
            "chmod 0644 \"${'$'}OTA_TMP\" || { echo 'ERR OTA_STAGE_CHMOD'; " +
            "rm -f \"${'$'}OTA_TMP\"; exit 43; }; " +
            "if pm install -r \"${'$'}OTA_TMP\"; then rm -f \"${'$'}OTA_TMP\"; sleep 2; " +
            "if ! dumpsys activity activities 2>/dev/null | grep 'mResumedActivity' " +
            "| grep -F -q " + pkg + "; then " + launcher + "; fi; " +
            "else OTA_RC=$?; rm -f \"${'$'}OTA_TMP\"; exit \"${'$'}OTA_RC\"; fi"
    }

    @JvmStatic
    fun quote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
