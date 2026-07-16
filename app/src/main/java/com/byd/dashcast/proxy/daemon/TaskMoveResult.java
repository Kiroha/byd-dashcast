package com.byd.dashcast.proxy.daemon;

/** Normalizes direct and stack-based task-move diagnostics into a stable success contract. */
public final class TaskMoveResult {

    public interface Operation {
        String run();
    }

    private TaskMoveResult() {}

    public static String runWithFallback(Operation direct, Operation fallback) {
        String directResult = direct.run();
        if (isSuccess(directResult)) return directResult;
        return combine(directResult, fallback.run());
    }

    public static boolean isSuccess(String result) {
        if (result == null) return false;
        String normalized = result.trim();
        return normalized.startsWith("OK ")
                || normalized.contains("; OK moveStackToDisplay(")
                || normalized.contains("; OK moveRootTaskToDisplay(")
                || normalized.contains("SKIP move (already on display ");
    }

    public static String combine(String direct, String fallback) {
        if (isSuccess(direct)) return direct;
        if (isSuccess(fallback)) {
            return "OK stack fallback: " + fallback + " ; direct=" + direct;
        }
        return "ERR task move failed: direct=[" + direct + "] fallback=[" + fallback + "]";
    }
}
