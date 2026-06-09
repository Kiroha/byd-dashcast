package com.byd.dashcast.beta.proxy;

import java.lang.reflect.Method;

/**
 * Phase4TaskVerbs — task and stack management verbs that run inside the daemon
 * process (uid 2000).
 *
 * <p>All methods here wrap IActivityTaskManager (ATM) reflection calls that
 * control task placement, windowing mode, resize, focus, and launch orchestration
 * on the BYD DiLink 3.0 fission cluster display.
 *
 * <p>The full {@code launchAndForce} + async watchdog sequence is the only path
 * confirmed to reliably land an app in FREEFORM mode on a display that lacks
 * {@code FLAG_SUPPORTS_FREEFORM_WINDOW_MANAGEMENT} (BYD Seal EU, Android 10).
 *
 * @see Phase4DisplayVerbs
 * @see Phase4ProcessVerbs
 * @since v1.1.9 build 174 — split from Phase4Verbs in v1.4.4-beta.
 */
public final class Phase4TaskVerbs {

    private Phase4TaskVerbs() {}

    // ─── Cached ATM reflection methods ───────────────────────────────────

    private static volatile Method sGetTasks;
    private static volatile Method sMoveTaskToDisplay;
    private static volatile Method sResizeTask;

    /** Prevents spawning multiple fission-watchdog threads concurrently. */
    private static final java.util.concurrent.atomic.AtomicBoolean sWatchdogActive =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ─── Reflection helper ────────────────────────────────────────────────

    private static Object readFieldNoThrow(Object target, String fieldName) {
        try {
            java.lang.reflect.Field f = target.getClass().getField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignore) {
            return null;
        }
    }

    // ─── Task query ───────────────────────────────────────────────────────

    /**
     * Lookup the taskId hosting {@code packageName} (any activity).
     * Returns -1 if no such task exists. Reads
     * {@code ActivityTaskManager.getService().getTasks(maxNum,…)} and inspects
     * each {@code RecentTaskInfo.topActivity}. Enumerates all overloads of
     * {@code getTasks} to handle BYD-specific signature variants.
     */
    public static int findTaskIdForPackage(String packageName) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method getTasks = sGetTasks;
            if (getTasks == null) {
                for (Method cand : iAtm.getClass().getMethods()) {
                    if ("getTasks".equals(cand.getName())) { getTasks = cand; break; }
                }
                sGetTasks = getTasks;
            }
            if (getTasks == null) {
                android.util.Log.w("Phase4TaskVerbs", "findTaskIdForPackage: no getTasks on " + iAtm.getClass());
                return -1;
            }
            Class<?>[] pt = getTasks.getParameterTypes();
            Object[] args = new Object[pt.length];
            for (int i = 0; i < pt.length; i++) {
                if (pt[i] == int.class)          args[i] = (i == 0 ? 64 : 0);
                else if (pt[i] == boolean.class) args[i] = false;
                else                              args[i] = null;
            }
            Object res = getTasks.invoke(iAtm, args);
            if (!(res instanceof java.util.List)) return -1;
            for (Object task : (java.util.List<?>) res) {
                if (task == null) continue;
                android.content.ComponentName topActivity =
                        (android.content.ComponentName) readFieldNoThrow(task, "topActivity");
                android.content.ComponentName baseActivity =
                        (android.content.ComponentName) readFieldNoThrow(task, "baseActivity");
                String pkg = (topActivity != null ? topActivity.getPackageName()
                            : baseActivity != null ? baseActivity.getPackageName() : null);
                if (packageName.equals(pkg)) {
                    Object id = readFieldNoThrow(task, "taskId");
                    if (id == null) id = readFieldNoThrow(task, "id");
                    if (id instanceof Integer) return (Integer) id;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("Phase4TaskVerbs", "findTaskIdForPackage(" + packageName + ") failed: " + t);
        }
        return -1;
    }

    // ─── Task movement ────────────────────────────────────────────────────

    /**
     * Move an existing task to {@code displayId} via reflection.
     * Enumerates {@code getMethods()} to catch any BYD-renamed variant
     * (moveTaskToDisplay / moveRootTaskToDisplay / moveTopActivityToDisplay …)
     * with 2 int params.
     */
    public static String moveTaskToDisplay(int taskId, int displayId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method target = sMoveTaskToDisplay;
            Method[] methods = null;
            if (target == null) {
                String[] preferred = {
                        "moveRootTaskToDisplay",
                        "moveTaskToDisplay",
                        "moveTopActivityToDisplay",
                        "reparentTaskToDisplay"
                };
                methods = iAtm.getClass().getMethods();
                outer:
                for (String wanted : preferred) {
                    for (Method m : methods) {
                        if (!m.getName().equals(wanted)) continue;
                        Class<?>[] pt = m.getParameterTypes();
                        if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                            target = m;
                            break outer;
                        }
                    }
                }
                if (target != null) sMoveTaskToDisplay = target;
            }
            if (target == null) {
                if (methods == null) methods = iAtm.getClass().getMethods();
                StringBuilder dump = new StringBuilder(
                        "ERR moveTaskToDisplay: no (int,int) variant. Candidates: ");
                boolean first = true;
                for (Method m : methods) {
                    String n = m.getName();
                    if (n.contains("Display") || n.contains("Stack") || n.contains("Task")) {
                        if (!first) dump.append(", ");
                        dump.append(n).append('(');
                        Class<?>[] pt = m.getParameterTypes();
                        for (int i = 0; i < pt.length; i++) {
                            if (i > 0) dump.append(',');
                            dump.append(pt[i].getSimpleName());
                        }
                        dump.append(')');
                        first = false;
                    }
                }
                return dump.toString();
            }
            target.invoke(iAtm, taskId, displayId);
            return "OK " + target.getName() + "(" + taskId + "," + displayId + ")";
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR moveTaskToDisplay: " + cause.getClass().getSimpleName()
                    + " — " + cause.getMessage();
        }
    }

    /**
     * BYD Seal EU / Android 10 stack-based move:
     * 1) flip task to FREEFORM via AOSP setTaskWindowingMode (also flips the containing
     *    stack — required for subsequent resizeTask to be accepted).
     * 2) find the task's stackId + current displayId via getAllStackInfos().
     * 3) moveStackToDisplay(stackId, displayId), skipped if already on target.
     */
    public static String moveTaskToDisplayViaStack(int taskId, int displayId) {
        StringBuilder log = new StringBuilder();
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);

            // Step A: flip task (and containing stack) to FREEFORM.
            try {
                Method setWm;
                String which;
                try {
                    setWm = iAtm.getClass().getMethod(
                            "setTaskWindowingMode", int.class, int.class, boolean.class);
                    which = "setTaskWindowingMode";
                } catch (NoSuchMethodException nsm) {
                    setWm = iAtm.getClass().getMethod(
                            "setCustomTaskWindowingMode", int.class, int.class, boolean.class);
                    which = "setCustomTaskWindowingMode";
                }
                setWm.invoke(iAtm, taskId, 5 /*WINDOWING_MODE_FREEFORM*/, true /*toTop*/);
                log.append("OK ").append(which).append('(').append(taskId).append(",FREEFORM)");
            } catch (Throwable wmEx) {
                Throwable c = (wmEx instanceof java.lang.reflect.InvocationTargetException
                        && wmEx.getCause() != null) ? wmEx.getCause() : wmEx;
                log.append("WARN setTaskWindowingMode: ").append(c.getClass().getSimpleName())
                   .append(" — ").append(c.getMessage());
            }
            log.append(" ; ");

            // Step B: find stackId AND its current displayId.
            int stackId = -1, currentDisplayId = -1;
            try {
                Method getAll = iAtm.getClass().getMethod("getAllStackInfos");
                Object res = getAll.invoke(iAtm);
                java.util.List<?> stacks;
                if (res instanceof java.util.List) stacks = (java.util.List<?>) res;
                else if (res != null && res.getClass().isArray())
                    stacks = java.util.Arrays.asList((Object[]) res);
                else stacks = java.util.Collections.emptyList();
                for (Object si : stacks) {
                    if (si == null) continue;
                    int sid = -1, did = -1;
                    int[] tids = null;
                    try {
                        java.lang.reflect.Field fStack = si.getClass().getField("stackId");
                        sid = fStack.getInt(si);
                    } catch (NoSuchFieldException ignore) {}
                    try {
                        java.lang.reflect.Field fDid = si.getClass().getField("displayId");
                        did = fDid.getInt(si);
                    } catch (NoSuchFieldException ignore) {}
                    try {
                        java.lang.reflect.Field fTaskIds = si.getClass().getField("taskIds");
                        tids = (int[]) fTaskIds.get(si);
                    } catch (NoSuchFieldException ignore) {}
                    if (tids != null) {
                        for (int t : tids) {
                            if (t == taskId) {
                                stackId = sid;
                                currentDisplayId = did;
                                break;
                            }
                        }
                    }
                    if (stackId != -1) break;
                }
            } catch (Throwable lookupEx) {
                log.append("WARN getAllStackInfos: ").append(lookupEx.getClass().getSimpleName())
                   .append(" — ").append(lookupEx.getMessage()).append(" ; ");
            }
            log.append("stackId=").append(stackId).append(" currentDisplay=")
               .append(currentDisplayId).append(" ; ");
            if (stackId < 0) {
                log.append("ERR no stack for task=").append(taskId);
                return log.toString();
            }

            // Step C: moveStackToDisplay — only if not already on target.
            if (currentDisplayId == displayId) {
                log.append("SKIP moveStackToDisplay (already on display ")
                   .append(displayId).append(')');
                return log.toString();
            }
            Method mv = iAtm.getClass().getMethod(
                    "moveStackToDisplay", int.class, int.class);
            mv.invoke(iAtm, stackId, displayId);
            log.append("OK moveStackToDisplay(").append(stackId).append(',')
               .append(displayId).append(')');
            return log.toString();
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            log.append("ERR moveTaskToDisplayViaStack: ")
               .append(cause.getClass().getSimpleName())
               .append(" — ").append(cause.getMessage());
            return log.toString();
        }
    }

    // ─── Task resize ──────────────────────────────────────────────────────

    /**
     * Resize an existing task. RESIZE_MODE_FORCED = 1 (skips user-driven gate).
     * Pass all-zero bounds to clear (full display).
     * Enumerates resize* signatures to adapt to BYD/AOSP variants.
     */
    public static String resizeTaskRect(int taskId, int l, int t, int r, int b) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            android.graphics.Rect bounds = (l == 0 && t == 0 && r == 0 && b == 0)
                    ? null : new android.graphics.Rect(l, t, r, b);

            Method m = sResizeTask;
            Object[] args = null;
            Method[] methods = null;
            if (m == null) {
                methods = iAtm.getClass().getMethods();
                for (Method cand : methods) {
                    if (!cand.getName().equals("resizeTask")) continue;
                    Class<?>[] pt = cand.getParameterTypes();
                    if (pt.length == 3 && pt[0] == int.class
                            && pt[1] == android.graphics.Rect.class
                            && pt[2] == int.class) {
                        m = cand; break;
                    }
                    if (pt.length == 2 && pt[0] == int.class
                            && pt[1] == android.graphics.Rect.class) {
                        m = cand;
                    }
                }
                if (m != null) sResizeTask = m;
            }
            if (m != null) {
                args = (m.getParameterTypes().length == 3)
                        ? new Object[]{taskId, bounds, 1}
                        : new Object[]{taskId, bounds};
            }
            if (m == null) {
                if (methods == null) methods = iAtm.getClass().getMethods();
                StringBuilder dump = new StringBuilder("ERR resizeTask: no variant. Candidates: ");
                boolean first = true;
                for (Method cand : methods) {
                    if (!cand.getName().toLowerCase().contains("resize")) continue;
                    if (!first) dump.append(", ");
                    dump.append(cand.getName()).append('(');
                    Class<?>[] pt = cand.getParameterTypes();
                    for (int i = 0; i < pt.length; i++) {
                        if (i > 0) dump.append(',');
                        dump.append(pt[i].getSimpleName());
                    }
                    dump.append(')');
                    first = false;
                }
                return dump.toString();
            }
            m.invoke(iAtm, args);
            return "OK " + m.getName() + "(" + taskId + ","
                    + (bounds == null ? "null" : bounds.toShortString()) + ")";
        } catch (Throwable ex) {
            Throwable cause = (ex instanceof java.lang.reflect.InvocationTargetException
                    && ex.getCause() != null) ? ex.getCause() : ex;
            String msg = cause.getMessage();
            return "ERR resizeTask: " + cause.getClass().getSimpleName()
                    + " — " + (msg == null ? cause.toString() : msg);
        }
    }

    // ─── Task focus / windowing mode ──────────────────────────────────────

    /** Set focused task — drives input and brings task to top of its display. */
    public static String setFocusedRootTask(int taskId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method m;
            try {
                m = iAtm.getClass().getMethod("setFocusedRootTask", int.class);
            } catch (NoSuchMethodException nsm) {
                m = iAtm.getClass().getMethod("setFocusedTask", int.class);
            }
            m.invoke(iAtm, taskId);
            return "OK " + m.getName() + "(" + taskId + ")";
        } catch (Throwable t) {
            return "ERR setFocusedRootTask: " + t.getClass().getSimpleName() + " — " + t.getMessage();
        }
    }

    /**
     * Force a task to be resizeable.
     * resizeMode values (AOSP): 0=UNRESIZEABLE 1=CROP_WINDOWS 2=RESIZEABLE
     * 3=RESIZEABLE_AND_PIPABLE 4=FORCE_RESIZEABLE.
     */
    public static String setTaskResizeable(int taskId, int resizeMode) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method m = iAtm.getClass().getMethod("setTaskResizeable", int.class, int.class);
            m.invoke(iAtm, taskId, resizeMode);
            return "OK setTaskResizeable(" + taskId + "," + resizeMode + ")";
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR setTaskResizeable: " + c.getClass().getSimpleName()
                    + " — " + c.getMessage();
        }
    }

    /**
     * Set a task's windowing mode to FREEFORM (5) using the best available ATM API.
     * Needed after moveStackToDisplay(), which creates a new FULLSCREEN stack on the
     * target display and overrides the task's prior FREEFORM mode.
     */
    public static String setTaskWindowingModeFreeform(int taskId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            try {
                Method m = iAtm.getClass().getMethod(
                        "setTaskWindowingMode", int.class, int.class, boolean.class);
                m.invoke(iAtm, taskId, 5 /*WINDOWING_MODE_FREEFORM*/, true /*toTop*/);
                return "OK setTaskWindowingMode(" + taskId + ",FREEFORM,true)";
            } catch (NoSuchMethodException e1) {
                Method m = iAtm.getClass().getMethod(
                        "setCustomTaskWindowingMode", int.class, int.class, boolean.class);
                m.invoke(iAtm, taskId, 5, true);
                return "OK setCustomTaskWindowingMode(" + taskId + ",FREEFORM,true)";
            }
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR setTaskWindowingModeFreeform: " + c.getClass().getSimpleName()
                    + " — " + c.getMessage();
        }
    }

    // ─── Launch orchestration ─────────────────────────────────────────────

    /**
     * Full OpenBYD 2.0 launchAndForce sequence: am start → poll task id →
     * move + resize + focus, with an async watchdog that re-anchors the task if
     * it bounces back to display 0 (Waze FLAG_ACTIVITY_LAUNCH_ADJACENT).
     *
     * <p>The only path confirmed to land an app in FREEFORM mode on a display
     * that lacks {@code FLAG_SUPPORTS_FREEFORM_WINDOW_MANAGEMENT} (BYD Seal EU).
     * Do NOT gate, add fallbacks, or add mode checks — the cascade as a whole
     * is the point. See {@code doc_api/CLUSTER_RESIZE_SEQUENCE.md}.
     *
     * @return multi-line log of every step for caller-side rendering.
     */
    public static String launchAndForce(String packageName, String activityClass,
                                        int displayId, int width, int height) {
        StringBuilder log = new StringBuilder();
        log.append("== launchAndForce ").append(packageName)
           .append(activityClass != null ? "/" + activityClass : "")
           .append(" → display=").append(displayId)
           .append(' ').append(width).append('x').append(height).append(" ==\n");
        android.util.Log.i("Phase4TaskVerbs", "FISSION launchAndForce START pkg=" + packageName
                + " displayId=" + displayId + " " + width + "x" + height);
        try {
            // Pre-cleanup: nuke any zombie split-screen-primary / freeform stack.
            log.append(cleanFissionStacks(displayId));

            // Always force-stop: running task keeps existing stack's mode otherwise.
            log.append("$ am force-stop ").append(packageName).append('\n');
            String stopOut = execShell("am force-stop " + packageName, 3000);
            log.append(stopOut == null ? "(no output)" : stopOut).append('\n');

            // Resolve component if caller didn't provide one.
            String cmpFlat = activityClass != null
                    ? packageName + "/" + activityClass : null;
            if (cmpFlat == null) {
                String resolveCmd = "cmd package resolve-activity --brief"
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + " " + packageName;
                log.append("$ ").append(resolveCmd).append('\n');
                String resolveOut = execShell(resolveCmd, 3000);
                log.append(resolveOut == null ? "(no output)" : resolveOut).append('\n');
                if (resolveOut != null) {
                    for (String line : resolveOut.split("\\r?\\n")) {
                        line = line.trim();
                        if (line.contains("/") && !line.startsWith("[err]")
                                && !line.equals(packageName)) {
                            cmpFlat = line;
                            break;
                        }
                    }
                }
                log.append("resolved component = ").append(cmpFlat).append('\n');
            }

            boolean started = false;

            // Dilink5 Dashboard pattern: -S -W --windowingMode 5 --display N pkg/cls.
            if (cmpFlat != null) {
                String cmd = "am start-activity -S -W"
                        + " --windowingMode 5"
                        + " --display " + displayId
                        + " --activity-no-animation"
                        + " -n " + cmpFlat;
                android.util.Log.i("Phase4TaskVerbs", "FISSION am start: " + cmd);
                log.append("$ ").append(cmd).append('\n');
                String out = execShell(cmd, 5000);
                log.append(out == null ? "(no output)" : out).append('\n');
                started = (out == null || !out.contains("Error:"));
            }

            // Fallback: bare MAIN with -p when component resolution failed.
            if (!started) {
                String cmd = "am start-activity -S -W"
                        + " --windowingMode 5"
                        + " --display " + displayId
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + " --activity-no-animation"
                        + " -p " + packageName;
                log.append("$ ").append(cmd).append('\n');
                String out = execShell(cmd, 5000);
                log.append(out == null ? "(no output)" : out).append('\n');
                started = (out == null || !out.contains("Error:"));
            }

            // Poll up to ~5 s for the task to appear.
            int taskId = -1;
            for (int i = 1; i <= 16 && taskId <= 0; i++) {
                try { Thread.sleep(300); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                taskId = findTaskIdForPackage(packageName);
            }
            log.append("findTask(post-poll) = ").append(taskId).append('\n');

            if (taskId <= 0) {
                log.append("FAIL: no task discovered for ").append(packageName).append('\n');
                return log.toString();
            }

            // Placement sequence mirrors DevTool handleLaunchAndForce exactly.
            // This is the ONLY sequence confirmed to work on BYD DiLink 3.0.
            log.append("  ").append(Phase4DisplayVerbs.setDisplayToSingleTaskInstance(displayId)).append('\n');
            log.append("  ").append(setTaskResizeable(taskId, 4 /*FORCE_RESIZEABLE*/)).append('\n');

            // Resolve real stackId via getAllStackInfos (same as DevTool Step 3b).
            int stackId = -1;
            try {
                Class<?> ac = Class.forName("android.app.ActivityTaskManager");
                Object ia = ac.getMethod("getService").invoke(null);
                Object res = ia.getClass().getMethod("getAllStackInfos").invoke(ia);
                java.util.List<?> ss;
                if (res instanceof java.util.List) ss = (java.util.List<?>) res;
                else if (res != null && res.getClass().isArray())
                    ss = java.util.Arrays.asList((Object[]) res);
                else ss = java.util.Collections.emptyList();
                outer2:
                for (Object si : ss) {
                    int[] tids;
                    try { tids = (int[]) si.getClass().getField("taskIds").get(si); }
                    catch (Exception ignore) { continue; }
                    if (tids == null) continue;
                    for (int t : tids) {
                        if (t == taskId) {
                            try { stackId = si.getClass().getField("stackId").getInt(si); }
                            catch (Exception ignore) {}
                            break outer2;
                        }
                    }
                }
                log.append("  stackId=").append(stackId).append('\n');
            } catch (Throwable ex) {
                log.append("  WARN getAllStackInfos: ").append(ex.getMessage()).append('\n');
            }

            // FREEFORM pre-move: flip task+stack to FREEFORM before the move.
            log.append("  ").append(setTaskWindowingModeFreeform(taskId)).append('\n');

            // moveStackToDisplay: creates the FREEFORM stack context that allows resizeTask.
            if (stackId >= 0) {
                try {
                    Class<?> ac = Class.forName("android.app.ActivityTaskManager");
                    Object ia = ac.getMethod("getService").invoke(null);
                    java.lang.reflect.Method mv = ia.getClass().getMethod(
                            "moveStackToDisplay", int.class, int.class);
                    mv.invoke(ia, stackId, displayId);
                    log.append("  OK moveStackToDisplay(").append(stackId).append(',')
                       .append(displayId).append(")\n");
                } catch (Throwable ex) {
                    Throwable c = (ex instanceof java.lang.reflect.InvocationTargetException
                            && ex.getCause() != null) ? ex.getCause() : ex;
                    log.append("  moveStackToDisplay: ").append(c.getClass().getSimpleName())
                       .append(" — ").append(c.getMessage()).append('\n');
                }
            }

            // FREEFORM post-move: moveStackToDisplay creates a FULLSCREEN stack; re-apply FREEFORM.
            log.append("  ").append(setTaskWindowingModeFreeform(taskId)).append('\n');

            log.append("  ").append(resizeTaskRect(taskId, 0, 0, width, height)).append('\n');
            log.append("  ").append(setFocusedRootTask(taskId)).append('\n');

            // Async watchdog: polls getAllStackInfos() every 500 ms for 10 s.
            // Re-anchors if Waze's FLAG_ACTIVITY_LAUNCH_ADJACENT bounces the task to display 0.
            final int wTaskId = taskId;
            if (sWatchdogActive.compareAndSet(false, true)) {
                new Thread(() -> {
                try {
                    int stableCount = 0;
                    for (int iter = 0; iter < 20; iter++) {
                        try { Thread.sleep(500); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); return;
                        }
                        if (iter < 4) continue; // skip first 2 s, let app settle
                        int curDisplay = -1;
                        try {
                            Class<?> ac = Class.forName("android.app.ActivityTaskManager");
                            Object ia = ac.getMethod("getService").invoke(null);
                            Object res = ia.getClass().getMethod("getAllStackInfos").invoke(ia);
                            java.util.List<?> ss;
                            if (res instanceof java.util.List) ss = (java.util.List<?>) res;
                            else if (res != null && res.getClass().isArray())
                                ss = java.util.Arrays.asList((Object[]) res);
                            else ss = java.util.Collections.emptyList();
                            outer:
                            for (Object si : ss) {
                                int[] tids;
                                try { tids = (int[]) si.getClass().getField("taskIds").get(si); }
                                catch (Exception ignore) { continue; }
                                if (tids == null) continue;
                                for (int t : tids) {
                                    if (t == wTaskId) {
                                        try { curDisplay = si.getClass()
                                                .getField("displayId").getInt(si); }
                                        catch (Exception ignore) {}
                                        break outer;
                                    }
                                }
                            }
                        } catch (Throwable pollEx) {
                            android.util.Log.w("Phase4TaskVerbs",
                                    "WATCHDOG poll err: " + pollEx.getMessage());
                            continue;
                        }
                        if (curDisplay < 0) return; // task gone
                        if (curDisplay == displayId) {
                            if (++stableCount >= 3) {
                                android.util.Log.d("Phase4TaskVerbs", "WATCHDOG: stable, done");
                                return;
                            }
                            continue;
                        }
                        stableCount = 0;
                        android.util.Log.i("Phase4TaskVerbs",
                                "WATCHDOG: task " + wTaskId + " on display " + curDisplay
                                + " — re-anchoring to " + displayId);
                        moveTaskToDisplayViaStack(wTaskId, displayId);
                        setTaskWindowingModeFreeform(wTaskId);
                        resizeTaskRect(wTaskId, 0, 0, width, height);
                        setFocusedRootTask(wTaskId);
                        android.util.Log.i("Phase4TaskVerbs", "WATCHDOG: re-anchor done");
                        return;
                    }
                    android.util.Log.d("Phase4TaskVerbs", "WATCHDOG: ended without bounce");
                } catch (Throwable t) {
                    android.util.Log.w("Phase4TaskVerbs",
                            "WATCHDOG unexpected: " + t.getMessage());
                } finally {
                    sWatchdogActive.set(false);
                }
                }, "fission-watchdog").start();
            }
            log.append("WATCHDOG started (20×500ms, detects from T+2s)\n");
            log.append("FINISH: launchAndForce complete.\n");
            android.util.Log.i("Phase4TaskVerbs", "FISSION launchAndForce DONE pkg=" + packageName
                    + " taskId=" + taskId + " displayId=" + displayId + " watchdog=running");
        } catch (Throwable t) {
            log.append("EXCEPTION: ").append(t).append('\n');
            android.util.Log.e("Phase4TaskVerbs", "FISSION launchAndForce EXCEPTION: " + t);
        }
        return log.toString();
    }

    // ─── Stack management ─────────────────────────────────────────────────

    /**
     * Destroy every non-fullscreen, non-home stack on {@code displayId}.
     * Recovery verb for the fission display when a prior session left a zombie
     * stack in split-screen-primary mode, causing
     * "Can only have one child on stack…mode=split-screen-primary" on the next launch.
     *
     * <p>Safe to call repeatedly — on a clean display the loop finds nothing to remove.
     *
     * @return multi-line log of every stack inspected and removed.
     */
    public static String cleanFissionStacks(int displayId) {
        StringBuilder log = new StringBuilder();
        log.append("== cleanFissionStacks display=").append(displayId).append(" ==\n");
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Object res = iAtm.getClass().getMethod("getAllStackInfos").invoke(iAtm);
            java.util.List<?> stacks;
            if (res instanceof java.util.List) stacks = (java.util.List<?>) res;
            else if (res != null && res.getClass().isArray())
                stacks = java.util.Arrays.asList((Object[]) res);
            else { log.append("no stacks returned\n"); return log.toString(); }

            Method removeStack = null;
            for (Method cand : iAtm.getClass().getMethods()) {
                if (!"removeStack".equals(cand.getName())) continue;
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length == 1 && pt[0] == int.class) { removeStack = cand; break; }
            }
            if (removeStack == null) {
                log.append("WARN: no removeStack(int) on ATM proxy\n");
            }

            int removed = 0, kept = 0;
            for (Object si : stacks) {
                if (si == null) continue;
                Integer sid = (Integer) readFieldNoThrow(si, "stackId");
                Integer did = (Integer) readFieldNoThrow(si, "displayId");
                Integer wm  = (Integer) readFieldNoThrow(si, "windowingMode");
                Integer at  = (Integer) readFieldNoThrow(si, "activityType");
                if (sid == null || did == null) continue;
                if (did != displayId) continue;
                int wmV = wm != null ? wm : -1;
                int atV = at != null ? at : -1;
                // FULLSCREEN=1: leave (normal projection lives here).
                // HOME=2: leave (default fallback Activity).
                if (wmV == 1 || atV == 2) {
                    log.append("  keep   stackId=").append(sid)
                       .append(" wm=").append(wmV).append(" at=").append(atV).append('\n');
                    kept++;
                    continue;
                }
                // If both fields are unreadable (-1/-1) the StackInfo class shape differs
                // on this ROM — cannot safely classify. Keep to avoid emptying the display.
                if (wmV == -1 && atV == -1) {
                    log.append("  keep?  stackId=").append(sid)
                       .append(" wm=-1 at=-1 (unreadable, defensive keep)\n");
                    kept++;
                    continue;
                }
                if (removeStack == null) {
                    log.append("  SKIP   stackId=").append(sid)
                       .append(" wm=").append(wmV).append(" (no removeStack verb)\n");
                    continue;
                }
                try {
                    removeStack.invoke(iAtm, (int) sid);
                    log.append("  REMOVE stackId=").append(sid)
                       .append(" wm=").append(wmV).append(" at=").append(atV).append('\n');
                    removed++;
                } catch (Throwable rex) {
                    Throwable c = (rex instanceof java.lang.reflect.InvocationTargetException
                            && rex.getCause() != null) ? rex.getCause() : rex;
                    log.append("  ERR    stackId=").append(sid).append(": ")
                       .append(c.getClass().getSimpleName())
                       .append(": ").append(c.getMessage()).append('\n');
                }
            }
            log.append("done — removed=").append(removed).append(" kept=").append(kept).append('\n');
        } catch (Throwable t) {
            log.append("EXCEPTION: ").append(t).append('\n');
        }
        return log.toString();
    }

    /** Look up the stackId containing {@code taskId} via getAllStackInfos(). Returns -1 if not found. */
    public static int findStackIdForTask(int taskId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Object res = iAtm.getClass().getMethod("getAllStackInfos").invoke(iAtm);
            java.util.List<?> stacks;
            if (res instanceof java.util.List) stacks = (java.util.List<?>) res;
            else if (res != null && res.getClass().isArray())
                stacks = java.util.Arrays.asList((Object[]) res);
            else return -1;
            for (Object si : stacks) {
                if (si == null) continue;
                int sid;
                int[] tids;
                try {
                    sid = si.getClass().getField("stackId").getInt(si);
                    tids = (int[]) si.getClass().getField("taskIds").get(si);
                } catch (NoSuchFieldException e) { continue; }
                if (tids == null) continue;
                for (int t : tids) if (t == taskId) return sid;
            }
        } catch (Throwable ignore) {}
        return -1;
    }

    /**
     * Flip a stack's windowing mode via every known ATM verb. Tries, in order:
     * setActivityStackWindowingMode, setStackWindowingMode (multiple arities),
     * setActivityStackWindowingModeForced.
     */
    public static String setStackWindowingMode(int stackId, int mode) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            String[] names = {
                    "setActivityStackWindowingMode",
                    "setStackWindowingMode",
                    "setActivityStackWindowingModeForced",
            };
            for (String name : names) {
                for (Method cand : iAtm.getClass().getMethods()) {
                    if (!cand.getName().equals(name)) continue;
                    Class<?>[] pt = cand.getParameterTypes();
                    try {
                        if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                            cand.invoke(iAtm, stackId, mode);
                            return "OK " + name + "(" + stackId + "," + mode + ")";
                        }
                        if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class
                                && pt[2] == boolean.class) {
                            cand.invoke(iAtm, stackId, mode, true);
                            return "OK " + name + "(" + stackId + "," + mode + ",true)";
                        }
                    } catch (Throwable inv) {
                        Throwable c = (inv instanceof java.lang.reflect.InvocationTargetException
                                && inv.getCause() != null) ? inv.getCause() : inv;
                        return "ERR " + name + ": " + c.getClass().getSimpleName()
                                + " — " + c.getMessage();
                    }
                }
            }
            return "SKIP setStackWindowingMode: no candidate method";
        } catch (Throwable t) {
            return "ERR setStackWindowingMode: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage();
        }
    }

    /**
     * Resize a stack directly. Does NOT hit the {@code canResizeTask()} gate
     * (which requires FREEFORM windowing mode). Useful when the display lacks
     * {@code FLAG_SUPPORTS_FREEFORM}.
     */
    public static String resizeStackRect(int stackId, int l, int t, int r, int b) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            android.graphics.Rect bounds = (l == 0 && t == 0 && r == 0 && b == 0)
                    ? null : new android.graphics.Rect(l, t, r, b);
            Method[] methods = iAtm.getClass().getMethods();
            Method m = null;
            Object[] args = null;
            for (Method cand : methods) {
                if (!cand.getName().equals("resizeStack")) continue;
                Class<?>[] pt = cand.getParameterTypes();
                // AOSP Android 10 signature: (int, Rect, boolean, boolean, boolean, int)
                if (pt.length == 6 && pt[0] == int.class
                        && pt[1] == android.graphics.Rect.class
                        && pt[2] == boolean.class && pt[3] == boolean.class
                        && pt[4] == boolean.class && pt[5] == int.class) {
                    m = cand;
                    args = new Object[]{stackId, bounds, true, true, false, -1};
                    break;
                }
                if (pt.length == 2 && pt[0] == int.class
                        && pt[1] == android.graphics.Rect.class) {
                    m = cand;
                    args = new Object[]{stackId, bounds};
                }
            }
            if (m == null) return "SKIP resizeStack: no matching variant";
            m.invoke(iAtm, args);
            return "OK resizeStack(" + stackId + ","
                    + (bounds == null ? "null" : bounds.toShortString()) + ")";
        } catch (Throwable ex) {
            Throwable cause = (ex instanceof java.lang.reflect.InvocationTargetException
                    && ex.getCause() != null) ? ex.getCause() : ex;
            return "ERR resizeStack: " + cause.getClass().getSimpleName()
                    + " — " + cause.getMessage();
        }
    }

    // ─── Diagnostics ──────────────────────────────────────────────────────

    /** Dump every ATM method whose name matches one of the given lowercase substrings. */
    public static String dumpAtmMethods(String[] substrings) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            StringBuilder out = new StringBuilder("ATM methods matching ")
                    .append(java.util.Arrays.toString(substrings)).append(":\n");
            java.util.TreeSet<String> sorted = new java.util.TreeSet<>();
            for (Method m : iAtm.getClass().getMethods()) {
                String nm = m.getName().toLowerCase();
                boolean match = false;
                for (String s : substrings) if (nm.contains(s)) { match = true; break; }
                if (!match) continue;
                StringBuilder sig = new StringBuilder(m.getName()).append('(');
                Class<?>[] pt = m.getParameterTypes();
                for (int i = 0; i < pt.length; i++) {
                    if (i > 0) sig.append(',');
                    sig.append(pt[i].getSimpleName());
                }
                sig.append(')');
                sorted.add(sig.toString());
            }
            for (String s : sorted) out.append("  ").append(s).append('\n');
            return out.toString();
        } catch (Throwable t) {
            return "ERR dumpAtmMethods: " + t;
        }
    }

    /** Read a task's current bounds via getTaskBounds(int) — verifies whether a resize took effect. */
    public static String getTaskBoundsVerb(int taskId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method m = iAtm.getClass().getMethod("getTaskBounds", int.class);
            Object res = m.invoke(iAtm, taskId);
            return "getTaskBounds(" + taskId + ") = "
                    + (res instanceof android.graphics.Rect
                            ? ((android.graphics.Rect) res).toShortString() : String.valueOf(res));
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR getTaskBounds: " + c.getClass().getSimpleName() + " — " + c.getMessage();
        }
    }

    /**
     * BYD-specific verb that takes a Rect alongside a windowing mode hint.
     * Present on BYD Seal EU / Android 10 — used by BYD's own WindowManagement
     * to place floating windows on the fission cluster display.
     *
     * <p>Tries modes 5=FREEFORM, 3=SPLIT_SCREEN_PRIMARY, 0 in order.
     * Returns the first OK result, or the last ERR.
     */
    public static String setTaskWindowingModeWithBounds(int taskId,
                                                        int l, int t, int r, int b) {
        StringBuilder log = new StringBuilder();
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            android.graphics.Rect bounds = new android.graphics.Rect(l, t, r, b);

            String[] names = {
                    "setCustomTaskWindowingModeSplitScreenPrimary",
                    "setTaskWindowingModeSplitScreenPrimary",
            };
            int[] modes = {5, 3, 0};

            Method target = null;
            String targetName = null;
            for (String nm : names) {
                for (Method cand : iAtm.getClass().getMethods()) {
                    if (!cand.getName().equals(nm)) continue;
                    Class<?>[] pt = cand.getParameterTypes();
                    if (pt.length == 6
                            && pt[0] == int.class && pt[1] == int.class
                            && pt[2] == boolean.class && pt[3] == boolean.class
                            && pt[4] == android.graphics.Rect.class
                            && pt[5] == boolean.class) {
                        target = cand;
                        targetName = nm;
                        break;
                    }
                }
                if (target != null) break;
            }
            if (target == null) return "SKIP setTaskWindowingModeWithBounds: no matching variant";

            Throwable lastEx = null;
            for (int mode : modes) {
                try {
                    target.invoke(iAtm, taskId, mode,
                            true /*onTop*/, false /*animate*/, bounds, false /*showRecents*/);
                    log.append("OK ").append(targetName).append('(')
                       .append(taskId).append(",mode=").append(mode)
                       .append(',').append(bounds.toShortString()).append(')');
                    return log.toString();
                } catch (Throwable ex) {
                    lastEx = (ex instanceof java.lang.reflect.InvocationTargetException
                            && ex.getCause() != null) ? ex.getCause() : ex;
                    log.append("[").append(mode).append("→")
                       .append(lastEx.getClass().getSimpleName())
                       .append(": ").append(lastEx.getMessage()).append("] ");
                }
            }
            return "ERR " + targetName + " all modes failed: " + log;
        } catch (Throwable ex) {
            return "ERR setTaskWindowingModeWithBounds: "
                    + ex.getClass().getSimpleName() + " — " + ex.getMessage();
        }
    }

    /**
     * Move + resize a task on the BYD cluster fission display.
     *
     * <p>This is the EXACT v1.2.61 sequence, validated in v1.2.70 as the only
     * pipeline that reliably repositions Waze on every slider drag without
     * crashing system_server. See {@code doc_api/CLUSTER_RESIZE_SEQUENCE.md}.
     *
     * <p><b>Do not</b> add if/else gating, mode checks, or fallbacks — every
     * previous "smart" variant regressed because StackInfo.windowingMode is
     * unreadable on this ROM (always -1). The cascade as a whole is the point.
     */
    public static String moveAndResize(String packageName, int displayId,
                                       int l, int t, int r, int b) {
        StringBuilder log = new StringBuilder();
        log.append("== moveAndResize ").append(packageName)
           .append(" → display=").append(displayId)
           .append(" rect=[").append(l).append(',').append(t).append(',')
           .append(r).append(',').append(b).append("] ==\n");
        try {
            int taskId = findTaskIdForPackage(packageName);
            log.append("findTask = ").append(taskId).append('\n');
            if (taskId <= 0) {
                log.append("FAIL: no task for ").append(packageName)
                   .append(" — launch the app first via launchAndForce.\n");
                return log.toString();
            }
            // EXACT v1.2.61 sequence — every verb contributes; the cascade as a whole is the point.
            log.append("  ").append(Phase4DisplayVerbs.setDisplayToSingleTaskInstance(displayId)).append('\n');
            log.append("  ").append(moveTaskToDisplayViaStack(taskId, displayId)).append('\n');
            log.append("  ").append(setTaskResizeable(taskId, 4)).append('\n');
            int stackId = findStackIdForTask(taskId);
            log.append("stackId = ").append(stackId).append('\n');
            if (stackId > 0) {
                log.append("  ").append(setStackWindowingMode(stackId, 5)).append('\n');
                log.append("  ").append(resizeStackRect(stackId, l, t, r, b)).append('\n');
            }
            log.append("  ").append(setTaskWindowingModeWithBounds(taskId, l, t, r, b)).append('\n');
            log.append("  ").append(resizeTaskRect(taskId, l, t, r, b)).append('\n');
            log.append("  ").append(setFocusedRootTask(taskId)).append('\n');
            log.append("  ").append(getTaskBoundsVerb(taskId)).append('\n');
            log.append("FINISH: moveAndResize complete.\n");
        } catch (Throwable ex) {
            log.append("EXCEPTION: ").append(ex).append('\n');
        }
        return log.toString();
    }

    // ─── Shell helper ─────────────────────────────────────────────────────

    private static String execShell(String command, int timeoutMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            final Process proc = p;
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[4096];
                try (java.io.InputStream is = proc.getInputStream();
                     java.io.InputStream es = proc.getErrorStream()) {
                    int n;
                    while ((n = is.read(buf)) > 0) out.append(new String(buf, 0, n));
                    while ((n = es.read(buf)) > 0) out.append("[err] ").append(new String(buf, 0, n));
                } catch (Throwable ignore) {}
            });
            reader.start();
            boolean finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                try { p.destroyForcibly(); } catch (Throwable ignore) {}
                return "[execShell timeout " + timeoutMs + "ms]";
            }
            reader.join(Math.max(200, timeoutMs / 4));
            return out.toString().trim();
        } catch (Throwable t) {
            return "[execShell EXCEPTION] " + t;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) {}
        }
    }
}
