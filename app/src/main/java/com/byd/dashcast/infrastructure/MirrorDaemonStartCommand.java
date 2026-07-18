package com.byd.dashcast.infrastructure;

/** Builds a shell command whose launcher descriptors cannot keep the ADB stream open. */
final class MirrorDaemonStartCommand {
    private MirrorDaemonStartCommand() {}

    static String build(String apkPath, String logPath, String latestLink) {
        if (apkPath == null || logPath == null || latestLink == null) {
            throw new IllegalArgumentException("MirrorDaemon paths required");
        }
        String child = "CLASSPATH=" + quote(apkPath)
                + " exec /system/bin/app_process64 -Xnoimage-dex2oat /system/bin"
                + " --nice-name=byd.mirror.daemon"
                + " com.byd.dashcast.proxy.daemon.MirrorDaemon"
                + " </dev/null >" + quote(logPath) + " 2>&1";
        return "setsid sh -c " + quote(child)
                + " </dev/null >/dev/null 2>&1 & "
                + "ln -sf " + quote(logPath) + " " + quote(latestLink)
                + "; echo STARTED";
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}