package com.byd.dashcast.infrastructure

/** Builds a shell command whose launcher descriptors cannot keep the ADB stream open. */
object MirrorDaemonStartCommand {
    @JvmStatic
    fun build(apkPath: String?, logPath: String?, latestLink: String?): String {
        if (apkPath == null || logPath == null || latestLink == null) {
            throw IllegalArgumentException("MirrorDaemon paths required")
        }
        val child = "CLASSPATH=" + quote(apkPath) +
            " exec /system/bin/app_process64 -Xnoimage-dex2oat /system/bin" +
            " --nice-name=byd.mirror.daemon" +
            " com.byd.dashcast.proxy.daemon.MirrorDaemon" +
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
