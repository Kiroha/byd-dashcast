package com.byd.dashcast.infrastructure

/**
 * Builds the `app_process` shell command that spawns the
 * [com.byd.dashcast.proxy.daemon.SurfaceDaemon], with launcher descriptors that cannot keep the ADB
 * stream open.
 */
object SurfaceDaemonStartCommand {
    @JvmStatic
    fun build(apkPath: String?, logPath: String?, latestLink: String?): String {
        if (apkPath == null || logPath == null || latestLink == null) {
            throw IllegalArgumentException("SurfaceDaemon paths required")
        }
        // `--nice-name=byd.mirror.daemon` is a WIRE IDENTIFIER: AdbLocalClient.DAEMON_GREP matches
        // this exact process name to decide whether to reuse or kill the daemon, so it must NOT be
        // renamed with the class. The fully-qualified class name that follows is the app_process
        // entry point and MUST track the Java class name — both ship in the same APK.
        val child = "CLASSPATH=" + quote(apkPath) +
            " exec /system/bin/app_process64 -Xnoimage-dex2oat /system/bin" +
            " --nice-name=byd.mirror.daemon" +
            " com.byd.dashcast.proxy.daemon.SurfaceDaemon" +
            " </dev/null >" + quote(logPath) + " 2>&1"
        return "setsid sh -c " + quote(child) +
            " </dev/null >/dev/null 2>&1 & " +
            "ln -sf " + quote(logPath) + " " + quote(latestLink) +
            "; echo STARTED"
    }

    @JvmStatic
    fun quote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
