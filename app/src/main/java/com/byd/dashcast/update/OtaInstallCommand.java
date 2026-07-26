package com.byd.dashcast.update;

/** Builds the uid-2000 staging, install, and relaunch shell transaction. */
final class OtaInstallCommand {
    private static final String STAGED_APK = "/data/local/tmp/dashcast-ota-update.apk";

    private OtaInstallCommand() {}

    static String build(String sourcePath, long expectedSize, String packageName,
                        String launchComponent) {
        if (sourcePath == null || sourcePath.isEmpty() || expectedSize <= 0L
                || packageName == null || !packageName.matches("[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException("invalid OTA install arguments");
        }
        String source = quote(sourcePath);
        String staged = quote(STAGED_APK);
        String pkg = quote(packageName);
        String launcher = launchComponent == null || launchComponent.isEmpty()
                ? "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1"
                : "am start -W -f 0x10200000 -n " + quote(launchComponent)
                    + " || monkey -p " + pkg
                    + " -c android.intent.category.LAUNCHER 1";

        return "OTA_SRC=" + source + "; OTA_TMP=" + staged + "; "
                + "rm -f \"$OTA_TMP\"; "
                + "if cat \"$OTA_SRC\" > \"$OTA_TMP\" 2>/dev/null; then :; "
                + "elif run-as " + pkg + " cat \"$OTA_SRC\" > \"$OTA_TMP\" 2>/dev/null; "
                + "then :; else echo 'ERR OTA_STAGE_UNREADABLE'; "
                + "rm -f \"$OTA_TMP\"; exit 41; fi; "
                + "OTA_SIZE=$(wc -c < \"$OTA_TMP\" 2>/dev/null | tr -d ' '); "
                + "if [ \"$OTA_SIZE\" != " + expectedSize + " ]; then "
                + "echo \"ERR OTA_STAGE_SIZE expected=" + expectedSize
                + " actual=$OTA_SIZE\"; rm -f \"$OTA_TMP\"; exit 42; fi; "
                + "chmod 0644 \"$OTA_TMP\" || { echo 'ERR OTA_STAGE_CHMOD'; "
                + "rm -f \"$OTA_TMP\"; exit 43; }; "
                + "if pm install -r \"$OTA_TMP\"; then rm -f \"$OTA_TMP\"; sleep 2; "
                + "if ! dumpsys activity activities 2>/dev/null | grep 'mResumedActivity' "
                + "| grep -F -q " + pkg + "; then " + launcher + "; fi; "
                + "else OTA_RC=$?; rm -f \"$OTA_TMP\"; exit \"$OTA_RC\"; fi";
    }

    static String quote(String value) {
        if (value == null) throw new IllegalArgumentException("null shell value");
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}