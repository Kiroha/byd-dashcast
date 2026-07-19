package com.byd.dashcast.infrastructure;

/** Decides whether an existing MirrorDaemon is safe to keep across app lifecycle changes. */
final class MirrorDaemonReusePolicy {
    private MirrorDaemonReusePolicy() {}

    static int singleProcessPid(String psOutput) {
        if (psOutput == null || psOutput.trim().isEmpty()) return -1;
        String[] lines = psOutput.trim().split("\\r?\\n");
        if (lines.length != 1) return -1;
        String[] fields = lines[0].trim().split("\\s+");
        if (fields.length < 2) return -1;
        try {
            return Integer.parseInt(fields[1]);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static boolean shouldReuse(boolean binderAlive, int daemonPid,
                               String versionMarker, int appBuild) {
        if (!binderAlive || daemonPid <= 0 || versionMarker == null) return false;
        String[] fields = versionMarker.trim().split(":", -1);
        if (fields.length != 2) return false;
        try {
            int markerPid = Integer.parseInt(fields[0]);
            int daemonBuild = Integer.parseInt(fields[1]);
            return markerPid == daemonPid && daemonBuild == appBuild;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}