package com.byd.dashcast.infrastructure.task;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.proxy.ProxyClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paths 2 + 2b: daemon-side {@code dumpsys activity recents} and
 * {@code dumpsys activity activities} via the shell-uid proxy.
 *
 * Requires the proxy daemon to be connected ({@link ProxyClient#isConnected()}).
 * Uses the same regex logic migrated verbatim from {@code ClusterService} to
 * preserve identical parse behaviour across all BYD ROM variants.
 */
public final class ProxyTaskFinder implements TaskFinder {

    private static final String TAG = "ProxyTaskFinder";

    // Pre-compiled patterns — same as ClusterService originals (zero logic change).
    private static final Pattern P_TASK_ID =
            Pattern.compile("Task\\{[^}]*#(\\d+)");
    private static final Pattern P_RECENT_SPLIT =
            Pattern.compile("(?m)^\\s*\\* Recent #\\d+:\\s*");

    @Override
    public int findTaskId(String packageName) throws TaskFinderException {
        if (!ProxyClient.isConnected()) {
            throw new TaskFinderException("daemon not connected");
        }
        // Path 2 — dumpsys activity recents
        try {
            String out = ProxyClient.runShell("dumpsys activity recents");
            if (out != null && !out.isEmpty()) {
                int id = parseFromRecents(out, packageName);
                if (id != NOT_FOUND) return id;
                AppLogger.d(TAG, packageName + " not in recents (len=" + out.length() + ")");
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "recents dump failed: " + t.getMessage());
        }
        // Path 2b — dumpsys activity activities (launcher-agnostic fallback)
        try {
            String out = ProxyClient.runShell("dumpsys activity activities");
            if (out != null && !out.isEmpty()) {
                int id = parseFromActivities(out, packageName);
                if (id != NOT_FOUND) return id;
                AppLogger.d(TAG, packageName + " not in activities (len=" + out.length() + ")");
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "activities dump failed: " + t.getMessage());
        }
        return NOT_FOUND;
    }

    // ── Parsers (migrated verbatim from ClusterService static methods) ─────────

    public static int parseFromRecents(String dump, String packageName) {
        if (dump == null || packageName == null) return NOT_FOUND;
        // Fast path — affinity on the same Task{...} line.
        try {
            Pattern p = Pattern.compile(
                    "Task\\{[^}]*#(\\d+)[^}]*\\bA=" + Pattern.quote(packageName) + "\\b");
            Matcher m = p.matcher(dump);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}

        // Block scan — realActivity= or cmp= match anywhere inside the Task block.
        try {
            String[] blocks = P_RECENT_SPLIT.split(dump);
            String marker1 = "realActivity=" + packageName + "/";
            String marker2 = "cmp=" + packageName + "/";
            String marker3 = " A=" + packageName + " ";
            for (String block : blocks) {
                if (block == null || block.isEmpty()) continue;
                if (block.contains(marker1) || block.contains(marker2) || block.contains(marker3)) {
                    Matcher mm = P_TASK_ID.matcher(block);
                    if (mm.find()) {
                        try { return Integer.parseInt(mm.group(1)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return NOT_FOUND;
    }

    public static int parseFromActivities(String dump, String packageName) {
        if (dump == null || packageName == null) return NOT_FOUND;
        try {
            Pattern p = Pattern.compile(
                    "ActivityRecord\\{[^}]*\\s"
                            + Pattern.quote(packageName)
                            + "/[^\\s}]+\\st(\\d+)\\}");
            Matcher m = p.matcher(dump);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return NOT_FOUND;
    }
}
