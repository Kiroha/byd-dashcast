package com.byd.dashcast.proxy.daemon;
import com.byd.dashcast.infrastructure.task.TaskLocation;
import java.util.Locale;

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

    /** One current guardian generation per package; different Layout slots run independently. */
    private static final FissionWatchdogRegistry sWatchdogs = new FissionWatchdogRegistry();

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

    // ─── Android 12 / RootTask compatibility (DiLink 5) ──────────────────

    /**
     * Returns the stack/root-task list from ATM.
     * Tries getAllStackInfos() (Android ≤11, DiLink 3) first, then falls back to
     * getAllRootTaskInfos() (Android 12+, DiLink 5) when the former is absent.
     */
    private static java.util.List<?> getAtmTaskList(Object iAtm) throws Throwable {
        Object res;
        try {
            res = iAtm.getClass().getMethod("getAllStackInfos").invoke(iAtm);
        } catch (NoSuchMethodException e) {
            // Android 12+ (DiLink 5): getAllStackInfos was renamed to getAllRootTaskInfos
            res = iAtm.getClass().getMethod("getAllRootTaskInfos").invoke(iAtm);
        }
        if (res instanceof java.util.List) return (java.util.List<?>) res;
        if (res != null && res.getClass().isArray())
            return java.util.Arrays.asList((Object[]) res);
        return java.util.Collections.emptyList();
    }

    /**
     * Returns the "stack / root-task" ID from a StackInfo or RootTaskInfo object.
     * Tries stackId (StackInfo, Android ≤11, DiLink 3) then taskId (RootTaskInfo, Android 12+, DiLink 5).
     */
    private static int getStackOrRootTaskId(Object info) {
        Object v = readFieldNoThrow(info, "stackId");
        if (v instanceof Integer) return (Integer) v;
        v = readFieldNoThrow(info, "taskId");
        if (v instanceof Integer) return (Integer) v;
        return -1;
    }

    /**
     * Returns child task IDs from a StackInfo or RootTaskInfo.
     * Tries taskIds (Android ≤11, DiLink 3) then childTaskIds (Android 12+, DiLink 5).
     */
    private static int[] getChildTaskIds(Object info) {
        Object v = readFieldNoThrow(info, "taskIds");
        if (v instanceof int[]) return (int[]) v;
        v = readFieldNoThrow(info, "childTaskIds");
        if (v instanceof int[]) return (int[]) v;
        return null;
    }

    // WindowConfiguration constants (android.app.WindowConfiguration, @hide).
    private static final int WM_FULLSCREEN             = 1;
    private static final int WM_SPLIT_SCREEN_PRIMARY   = 3;
    private static final int WM_SPLIT_SCREEN_SECONDARY = 4;
    private static final int AT_HOME                   = 2;

    /**
     * Windowing mode / activity type of a StackInfo (Android ≤11, DiLink 3) or a
     * RootTaskInfo (Android 12+, DiLink 5).
     *
     * <p>Neither class exposes these as public fields — they live inside
     * {@code configuration.windowConfiguration}. Reading them with
     * {@link #readFieldNoThrow} (what cleanFissionStacks did before 1.6.112) therefore
     * ALWAYS returned null on every ROM: every stack was classified "unreadable" and
     * defensively kept, so the zombie-stack cleanup never removed anything, ever
     * (confirmed across 17 on-car bug reports: {@code removed=0} in 100% of them).
     * That let a stale split-screen-primary stack survive on the fission display, which
     * makes the next FREEFORM stack creation NPE inside WindowManager and the launch
     * fail silently (INC-20260714-215700, DiLink 3.0).
     *
     * <p>Four routes, most direct first, so a ROM that shifts the class shape again still
     * resolves: (1) getter on the info object, (2) configuration.windowConfiguration
     * getter, (3) the textual {@code mWindowingMode=…} of the configuration's toString,
     * (4) the legacy public field. Returns -1 when every route fails.
     */
    private static int readWindowConfigInt(Object info, String getter, String legacyField,
                                           String[] names) {
        // 1. Direct getter on the info object (TaskInfo.getWindowingMode(), Android 12+).
        try {
            Object v = info.getClass().getMethod(getter).invoke(info);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignore) { /* not on this ROM — next route */ }

        Object cfg = readFieldNoThrow(info, "configuration");
        Object wc  = (cfg == null) ? null : readFieldNoThrow(cfg, "windowConfiguration");

        // 2. configuration.windowConfiguration.<getter>() — StackInfo (A10) and TaskInfo (A12).
        if (wc != null) {
            try {
                Object v = wc.getClass().getMethod(getter).invoke(wc);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignore) { /* next route */ }
        }

        // 3. Textual fallback: WindowConfiguration.toString() prints "mWindowingMode=freeform".
        String dump = (wc != null) ? String.valueOf(wc)
                    : (cfg != null) ? String.valueOf(cfg) : null;
        if (dump != null) {
            String key = getter.equals("getWindowingMode") ? "mWindowingMode=" : "mActivityType=";
            int at = dump.indexOf(key);
            if (at >= 0) {
                String tail = dump.substring(at + key.length());
                // Longest name first: "split-screen-secondary" must not match "split-screen-p…".
                int best = -1;
                for (int i = 0; i < names.length; i++) {
                    if (tail.startsWith(names[i])
                            && (best < 0 || names[i].length() > names[best].length())) {
                        best = i;
                    }
                }
                if (best >= 0) return best;
            }
        }

        // 4. Legacy public field (kept in case some ROM does expose it).
        Object v = readFieldNoThrow(info, legacyField);
        if (v instanceof Integer) return (Integer) v;
        return -1;
    }

    /** Windowing mode of a StackInfo / RootTaskInfo, or -1 when unreadable. */
    private static int readWindowingMode(Object info) {
        return readWindowConfigInt(info, "getWindowingMode", "windowingMode", new String[]{
                "undefined", "fullscreen", "pinned",
                "split-screen-primary", "split-screen-secondary", "freeform",
        });
    }

    /** Activity type of a StackInfo / RootTaskInfo, or -1 when unreadable. */
    private static int readActivityType(Object info) {
        return readWindowConfigInt(info, "getActivityType", "activityType", new String[]{
                "undefined", "standard", "home", "recents", "assistant",
        });
    }

    /**
     * True when an {@code am start-activity} transcript is a FAILED start.
     *
     * <p>Beyond the usual "Error:" line, system_server can throw straight through the shell
     * command: am then prints "Starting: Intent {…}" (which looks like success) followed by
     * "Exception occurred while executing:" and a stack trace — and no activity ever starts.
     * DiLink 3.0 does exactly that when a FREEFORM stack cannot be created on the fission
     * display. Testing only for "Error:" declared that start a success, so the bare-MAIN
     * fallback was never tried (INC-20260714-215700).
     *
     * <p>A null transcript keeps its historical meaning: no output ⇒ assume started.
     */
    private static boolean amStartFailed(String out) {
        return TaskLaunchRecovery.isStartFailure(out);
    }

    /** Polls up to ~5 s for {@code packageName}'s task id to appear. Returns -1 if none. */
    private static int pollForTaskId(String packageName) {
        int taskId = -1;
        for (int i = 1; i <= 16 && taskId <= 0; i++) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            taskId = findTaskIdForPackage(packageName);
        }
        return taskId;
    }

    /** Starts the app on {@code displayId} WITHOUT --windowingMode (default = fullscreen stack),
     *  the last-resort that avoids the FREEFORM-stack WindowManager NPE on some DiLink 3 ROMs. */
    private static void startPlainOnDisplay(StringBuilder log, int displayId,
                                            String cmpFlat, String packageName) {
        String target = (cmpFlat != null)
                ? "-n " + cmpFlat
                : "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + packageName;
        String cmd = "am start-activity -S -W"
                + " --display " + displayId
                + " --activity-no-animation"
                + " " + target;
        android.util.Log.w("Phase4TaskVerbs",
                "FISSION retrying without --windowingMode (freeform landed no task)");
        log.append("(retrying without --windowingMode)\n$ ").append(cmd).append('\n');
        String out = execShell(cmd, 5000);
        log.append(out == null ? "(no output)" : out).append('\n');
    }

    /** A failed FREEFORM creation leaves a new empty split stack, so clean again before plain. */
    private static int retryPlainOnCleanDisplay(StringBuilder log, int displayId,
                                                 String cmpFlat, String packageName) {
        return TaskLaunchRecovery.retryOnCleanDisplay(new TaskLaunchRecovery.Operations() {
            @Override public String cleanDisplay() {
                String cleanup = cleanFissionStacks(displayId);
                log.append("(re-clean after FREEFORM failure)\n").append(cleanup);
                return cleanup;
            }

            @Override public void launchPlain() {
                startPlainOnDisplay(log, displayId, cmpFlat, packageName);
            }

            @Override public int pollTask() {
                return pollForTaskId(packageName);
            }
        });
    }

    /**
     * Moves a stack/root task to the target display.
     * Tries moveStackToDisplay (Android ≤11, DiLink 3) then
     * moveRootTaskToDisplay (Android 12+, DiLink 5).
     */
    private static String moveStackOrRootTask(Object iAtm, int stackOrTaskId, int displayId) {
        try {
            iAtm.getClass().getMethod("moveStackToDisplay", int.class, int.class)
                    .invoke(iAtm, stackOrTaskId, displayId);
            return "OK moveStackToDisplay(" + stackOrTaskId + "," + displayId + ")";
        } catch (NoSuchMethodException e) {
            try {
                iAtm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class)
                        .invoke(iAtm, stackOrTaskId, displayId);
                return "OK moveRootTaskToDisplay(" + stackOrTaskId + "," + displayId + ")";
            } catch (Throwable t2) {
                Throwable c = (t2 instanceof java.lang.reflect.InvocationTargetException
                        && t2.getCause() != null) ? t2.getCause() : t2;
                return "ERR moveRootTaskToDisplay: " + c.getClass().getSimpleName()
                        + " — " + c.getMessage();
            }
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR moveStackToDisplay: " + c.getClass().getSimpleName()
                    + " — " + c.getMessage();
        }
    }

    // ─── Task query ───────────────────────────────────────────────────────

    /** Resolve the display containing {@code taskId} from StackInfo/RootTaskInfo. */
    private static int findDisplayIdForTask(Object iAtm, int taskId) {
        try {
            for (Object info : getAtmTaskList(iAtm)) {
                if (info == null) continue;
                boolean containsTask = getStackOrRootTaskId(info) == taskId;
                int[] taskIds = getChildTaskIds(info);
                if (!containsTask && taskIds != null) {
                    for (int id : taskIds) {
                        if (id == taskId) {
                            containsTask = true;
                            break;
                        }
                    }
                }
                if (!containsTask) continue;
                Object displayId = readFieldNoThrow(info, "displayId");
                if (displayId instanceof Integer) return (Integer) displayId;
            }
        } catch (Throwable ignore) {
            // FOUND with an unreadable display maps to UNKNOWN on the app side.
        }
        return TaskLocation.UNKNOWN_DISPLAY_ID;
    }

    /**
     * Locate a package task and its current display. A completed ATM query with no matching task
     * returns ABSENT; inability to query or determine the task identity returns UNKNOWN.
     */
    public static TaskLocation findTaskLocationForPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return TaskLocation.unknown();
        try {
            java.util.List<TaskLocation> locations = queryTaskLocationsForPackage(packageName);
            return locations.isEmpty() ? TaskLocation.absent() : locations.get(0);
        } catch (Throwable t) {
            android.util.Log.w("Phase4TaskVerbs",
                    "findTaskLocationForPackage(" + packageName + ") failed: " + t);
            return TaskLocation.unknown();
        }
    }

    private static TaskLocation findWatchdogTaskLocation(String packageName,
                                                         int targetDisplayId) {
        try {
            return FissionWatchdogPolicy.selectTask(
                    queryTaskLocationsForPackage(packageName), targetDisplayId);
        } catch (Throwable error) {
            android.util.Log.w("Phase4TaskVerbs",
                    "WATCHDOG task query failed pkg=" + packageName + ": " + error);
            return TaskLocation.unknown();
        }
    }

    private static java.util.List<TaskLocation> queryTaskLocationsForPackage(String packageName)
            throws Throwable {
        Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
        Object iAtm = atmCls.getMethod("getService").invoke(null);
        Method getTasks = sGetTasks;
        if (getTasks == null) {
            for (Method candidate : iAtm.getClass().getMethods()) {
                if ("getTasks".equals(candidate.getName())) {
                    getTasks = candidate;
                    break;
                }
            }
            sGetTasks = getTasks;
        }
        if (getTasks == null) throw new NoSuchMethodException("getTasks");

        Class<?>[] parameterTypes = getTasks.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            if (parameterTypes[index] == int.class) args[index] = (index == 0 ? 64 : 0);
            else if (parameterTypes[index] == boolean.class) args[index] = false;
            else args[index] = null;
        }
        Object rawTasks = getTasks.invoke(iAtm, args);
        if (!(rawTasks instanceof java.util.List)) {
            throw new IllegalStateException("getTasks returned "
                    + (rawTasks == null ? "null" : rawTasks.getClass().getName()));
        }

        java.util.List<TaskLocation> locations = new java.util.ArrayList<>();
        boolean identityUnknown = false;
        for (Object task : (java.util.List<?>) rawTasks) {
            if (task == null) continue;
            android.content.ComponentName topActivity =
                    (android.content.ComponentName) readFieldNoThrow(task, "topActivity");
            android.content.ComponentName baseActivity =
                    (android.content.ComponentName) readFieldNoThrow(task, "baseActivity");
            boolean packageMatches = topActivity != null
                    && packageName.equals(topActivity.getPackageName());
            packageMatches |= baseActivity != null
                    && packageName.equals(baseActivity.getPackageName());
            if (!packageMatches) continue;

            Object rawTaskId = readFieldNoThrow(task, "taskId");
            if (rawTaskId == null) rawTaskId = readFieldNoThrow(task, "id");
            if (!(rawTaskId instanceof Integer)) {
                identityUnknown = true;
                continue;
            }
            int taskId = (Integer) rawTaskId;
            Object rawDisplayId = readFieldNoThrow(task, "displayId");
            int displayId = rawDisplayId instanceof Integer
                    ? (Integer) rawDisplayId
                    : findDisplayIdForTask(iAtm, taskId);
            locations.add(TaskLocation.found(taskId, displayId));
        }
        if (identityUnknown) locations.add(TaskLocation.unknown());
        return locations;
    }

    /**
     * Lookup the taskId hosting {@code packageName} (any activity).
     * Returns -1 if no such task exists. Reads
     * {@code ActivityTaskManager.getService().getTasks(maxNum,…)} and inspects
     * each {@code RecentTaskInfo.topActivity}. Enumerates all overloads of
     * {@code getTasks} to handle BYD-specific signature variants.
     */
    public static int findTaskIdForPackage(String packageName) {
        TaskLocation location = findTaskLocationForPackage(packageName);
        return location.getStatus() == TaskLocation.Status.FOUND
                ? location.getTaskId() : -1;
    }

    // ─── Task removal ─────────────────────────────────────────────────────

    /**
     * Remove {@code taskId} from the ActivityTaskManager recents stack via
     * reflection ({@code IActivityTaskManager.removeTask(int)}). Must be
     * called before {@link Phase4ProcessVerbs#forceStopPackage} to avoid
     * leaving an orphan task on display 0 after session teardown.
     *
     * <p>Throws if the method cannot be found or the call fails — callers
     * should fall back to {@code am task remove} via ADB on any exception.
     */
    public static void removeTask(int taskId) throws Throwable {
        Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
        Object iAtm = atmCls.getMethod("getService").invoke(null);
        Method removeTask = null;
        for (Method cand : iAtm.getClass().getMethods()) {
            if ("removeTask".equals(cand.getName())) {
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length == 1 && pt[0] == int.class) {
                    removeTask = cand;
                    break;
                }
            }
        }
        if (removeTask == null) {
            throw new NoSuchMethodException("removeTask(int) not found on " + iAtm.getClass());
        }
        removeTask.invoke(iAtm, taskId);
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
     * Moves a task using the direct task API when available, then falls back to the containing
     * stack/root-task API used by DiLink 3/Android 10. The incident ROM exposes
     * {@code moveStackToDisplay(int,int)} but no direct {@code moveTaskToDisplay(int,int)}.
     */
    public static String moveTaskToDisplayCompatible(int taskId, int displayId) {
        return TaskMoveResult.runWithFallback(
                () -> moveTaskToDisplay(taskId, displayId),
                () -> moveTaskToDisplayViaStack(taskId, displayId));
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
            // Tries getAllStackInfos (DiLink 3) then getAllRootTaskInfos (DiLink 5 / Android 12).
            int stackId = -1, currentDisplayId = -1;
            try {
                java.util.List<?> stacks = getAtmTaskList(iAtm);
                for (Object si : stacks) {
                    if (si == null) continue;
                    int[] tids = getChildTaskIds(si);
                    if (tids == null) continue;
                    for (int t : tids) {
                        if (t == taskId) {
                            stackId = getStackOrRootTaskId(si);
                            Object did = readFieldNoThrow(si, "displayId");
                            if (did instanceof Integer) currentDisplayId = (Integer) did;
                            break;
                        }
                    }
                    if (stackId != -1) break;
                }
            } catch (Throwable lookupEx) {
                log.append("WARN getAtmTaskList: ").append(lookupEx.getClass().getSimpleName())
                   .append(" — ").append(lookupEx.getMessage()).append(" ; ");
            }
            log.append("stackId=").append(stackId).append(" currentDisplay=")
               .append(currentDisplayId).append(" ; ");
            if (stackId < 0) {
                log.append("ERR no stack for task=").append(taskId);
                return log.toString();
            }

            // Step C: moveStackToDisplay (DiLink 3) or moveRootTaskToDisplay (DiLink 5 / Android 12).
            if (currentDisplayId == displayId) {
                log.append("SKIP move (already on display ").append(displayId).append(')');
                return log.toString();
            }
            log.append(moveStackOrRootTask(iAtm, stackId, displayId));
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
                    if (!cand.getName().toLowerCase(Locale.ROOT).contains("resize")) continue;
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
            boolean freeformStackFailure = false;

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
                started = !amStartFailed(out);
                freeformStackFailure = TaskLaunchRecovery.isFreeformStackFailure(out);
            }

            // Fallback: bare MAIN with -p when component resolution failed.
            // Do not repeat the same --windowingMode launch after the known BYD framework NPE:
            // it only recreates another poisoned split stack. Re-clean and go plain instead.
            if (!started && !freeformStackFailure) {
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
                started = !amStartFailed(out);
                freeformStackFailure = TaskLaunchRecovery.isFreeformStackFailure(out);
            }

            // Last-resort (no --windowingMode): on some DiLink 3.0 ROMs, creating a FREEFORM
            // stack on the fission display throws inside WindowManager (NPE in
            // ActivityStack.onConfigurationChanged) so NO activity starts. Dropping the windowing
            // mode lets the activity land in the display's default (fullscreen) stack — visible on
            // the cluster at full size — and the FREEFORM flip further down still gets its chance.
            // EARLY trigger: both --windowingMode 5 attempts clearly threw (am printed the error).
            boolean triedPlain = false;
            int taskId;
            if (!started) {
                taskId = retryPlainOnCleanDisplay(log, displayId, cmpFlat, packageName);
                triedPlain = true;
            } else {
                // Poll up to ~5 s for the task to appear.
                taskId = pollForTaskId(packageName);
            }
            log.append("findTask(post-poll) = ").append(taskId).append('\n');

            // LATE trigger: the freeform attempt reported "started" (no am error text) yet landed
            // NO task — the FREEFORM stack creation failed silently on this ROM (INC-20260716-091016,
            // where started=true skipped the old !started-gated last-resort). Retry once without the
            // windowing mode, keyed on the ACTUAL outcome (no task) rather than the am exit text.
            if (taskId <= 0 && !triedPlain) {
                taskId = retryPlainOnCleanDisplay(log, displayId, cmpFlat, packageName);
                log.append("findTask(post-fallback) = ").append(taskId).append('\n');
            }

            if (taskId <= 0) {
                log.append("FAIL: no task discovered for ").append(packageName).append('\n');
                return log.toString();
            }

            // Placement sequence mirrors DevTool handleLaunchAndForce exactly.
            // This is the ONLY sequence confirmed to work on BYD DiLink 3.0.
            log.append("  ").append(Phase4DisplayVerbs.setDisplayToSingleTaskInstance(displayId)).append('\n');
            log.append("  ").append(setTaskResizeable(taskId, 4 /*FORCE_RESIZEABLE*/)).append('\n');

            // Resolve real stackId via getAllStackInfos (DiLink 3) or getAllRootTaskInfos (DiLink 5 / Android 12).
            int stackId = -1;
            try {
                Class<?> ac = Class.forName("android.app.ActivityTaskManager");
                Object ia = ac.getMethod("getService").invoke(null);
                java.util.List<?> ss = getAtmTaskList(ia);
                outer2:
                for (Object si : ss) {
                    int[] tids = getChildTaskIds(si);
                    if (tids == null) continue;
                    for (int t : tids) {
                        if (t == taskId) {
                            stackId = getStackOrRootTaskId(si);
                            break outer2;
                        }
                    }
                }
                log.append("  stackId=").append(stackId).append('\n');
            } catch (Throwable ex) {
                log.append("  WARN getAtmTaskList: ").append(ex.getMessage()).append('\n');
            }

            // FREEFORM pre-move: flip task+stack to FREEFORM before the move.
            log.append("  ").append(setTaskWindowingModeFreeform(taskId)).append('\n');

            // moveStackToDisplay (DiLink 3) or moveRootTaskToDisplay (DiLink 5 / Android 12).
            if (stackId >= 0) {
                try {
                    Class<?> ac = Class.forName("android.app.ActivityTaskManager");
                    Object ia = ac.getMethod("getService").invoke(null);
                    log.append("  ").append(moveStackOrRootTask(ia, stackId, displayId)).append('\n');
                } catch (Throwable ex) {
                    Throwable c = (ex instanceof java.lang.reflect.InvocationTargetException
                            && ex.getCause() != null) ? ex.getCause() : ex;
                    log.append("  moveStackOrRootTask: ").append(c.getClass().getSimpleName())
                       .append(" — ").append(c.getMessage()).append('\n');
                }
            }

            // FREEFORM post-move: moveStackToDisplay creates a FULLSCREEN stack; re-apply FREEFORM.
            log.append("  ").append(setTaskWindowingModeFreeform(taskId)).append('\n');

            log.append("  ").append(resizeTaskRect(taskId, 0, 0, width, height)).append('\n');
            log.append("  ").append(setFocusedRootTask(taskId)).append('\n');

            String watchdogStatus = startFissionWatchdog(
                    packageName, taskId, displayId, width, height);
            log.append(watchdogStatus).append('\n');
            log.append("FINISH: launchAndForce complete.\n");
            android.util.Log.i("Phase4TaskVerbs", "FISSION launchAndForce DONE pkg=" + packageName
                    + " taskId=" + taskId + " displayId=" + displayId
                    + " watchdog=" + watchdogStatus);
        } catch (Throwable t) {
            log.append("EXCEPTION: ").append(t).append('\n');
            android.util.Log.e("Phase4TaskVerbs", "FISSION launchAndForce EXCEPTION: " + t);
        }
        return log.toString();
    }

    private static String startFissionWatchdog(String packageName, int initialTaskId,
                                               int targetDisplayId, int width, int height) {
        final long generation = sWatchdogs.start(packageName);
        final FissionWatchdogPolicy policy = new FissionWatchdogPolicy();
        Thread watchdog = new Thread(() -> {
            int currentTaskId = initialTaskId;
            int reanchorCount = 0;
            try {
                for (int poll = 1; poll <= FissionWatchdogPolicy.MAX_POLLS; poll++) {
                    try {
                        Thread.sleep(FissionWatchdogPolicy.POLL_INTERVAL_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!sWatchdogs.isCurrent(packageName, generation)) {
                        android.util.Log.d("Phase4TaskVerbs",
                                "WATCHDOG superseded pkg=" + packageName
                                        + " generation=" + generation);
                        return;
                    }

                        TaskLocation location = findWatchdogTaskLocation(
                            packageName, targetDisplayId);
                    TaskLocation.DisplayMatch match = location.matchDisplay(targetDisplayId);
                    if (location.getStatus() == TaskLocation.Status.FOUND
                            && location.getTaskId() != currentTaskId) {
                        android.util.Log.i("Phase4TaskVerbs",
                                "WATCHDOG adopted new task pkg=" + packageName
                                        + " oldTask=" + currentTaskId
                                        + " newTask=" + location.getTaskId());
                        currentTaskId = location.getTaskId();
                    }

                    FissionWatchdogPolicy.Action action = policy.onPoll(poll, match);
                    if (action == FissionWatchdogPolicy.Action.COMPLETE) {
                        android.util.Log.d("Phase4TaskVerbs",
                                "WATCHDOG complete pkg=" + packageName
                                        + " task=" + currentTaskId
                                        + " polls=" + poll
                                        + " reanchors=" + reanchorCount
                                        + " final=" + match);
                        return;
                    }
                    if (action != FissionWatchdogPolicy.Action.REANCHOR
                            || currentTaskId <= 0) {
                        continue;
                    }

                    reanchorCount++;
                    String move = moveTaskToDisplayViaStack(currentTaskId, targetDisplayId);
                    String mode = setTaskWindowingModeFreeform(currentTaskId);
                    String resize = resizeTaskRect(currentTaskId, 0, 0, width, height);
                    String focus = setFocusedRootTask(currentTaskId);
                    android.util.Log.i("Phase4TaskVerbs",
                            "WATCHDOG re-anchor pkg=" + packageName
                                    + " task=" + currentTaskId
                                    + " from=" + location.getDisplayId()
                                    + " to=" + targetDisplayId
                                    + " poll=" + poll
                                    + " move=[" + move + "]"
                                    + " mode=[" + mode + "]"
                                    + " resize=[" + resize + "]"
                                    + " focus=[" + focus + "]");
                }
            } catch (Throwable error) {
                android.util.Log.w("Phase4TaskVerbs",
                        "WATCHDOG unexpected pkg=" + packageName + ": " + error);
            } finally {
                sWatchdogs.finish(packageName, generation);
            }
        }, "fission-watchdog-" + initialTaskId);
        watchdog.setDaemon(true);
        try {
            watchdog.start();
            return "WATCHDOG started pkg=" + packageName
                    + " task=" + initialTaskId
                    + " display=" + targetDisplayId
                    + " guard=" + FissionWatchdogPolicy.INITIAL_GUARD_POLLS
                    * FissionWatchdogPolicy.POLL_INTERVAL_MS / 1000 + "s"
                    + " max=" + FissionWatchdogPolicy.MAX_POLLS
                    * FissionWatchdogPolicy.POLL_INTERVAL_MS / 1000 + "s";
        } catch (Throwable startError) {
            sWatchdogs.finish(packageName, generation);
            android.util.Log.w("Phase4TaskVerbs",
                    "WATCHDOG start failed pkg=" + packageName + ": " + startError);
            return "WATCHDOG failed pkg=" + packageName
                    + " error=" + startError.getClass().getSimpleName();
        }
    }

    /** Cancels the latest guardian before an intentional move/release during Layout teardown. */
    public static boolean cancelFissionWatchdog(String packageName) {
        return packageName == null
                ? sWatchdogs.cancelAll() > 0
                : sWatchdogs.cancel(packageName);
    }

    // ─── Stack management ─────────────────────────────────────────────────

    /**
     * Destroy the zombie stacks on {@code displayId} (the fission/cluster display).
     * Recovery verb for when a prior session left a stack in split-screen-primary mode,
     * causing "Can only have one child on stack…mode=split-screen-primary" — or, on
     * DiLink 3.0, a WindowManager NPE while creating the next FREEFORM stack — on the
     * next launch.
     *
     * <p>Removes: any non-fullscreen, non-home stack that holds NO task (a stale
     * split-screen one included). Keeps fullscreen stacks, home stacks, and EVERY
     * task-holding stack — so it can never tear down the running projection, which on
     * DiLink 3 legitimately sits in a split-screen-primary stack after a resize.
     * Every call site is a pre-launch cleanup.
     *
     * <p>Safe to call repeatedly — on a clean display the loop finds nothing to remove.
     *
     * @return multi-line log of every stack inspected and removed.
     */
    /** {@link #getAtmTaskList} that never throws; {@code null} means "could not be read". */
    private static java.util.List<?> getAtmTaskListNoThrow(Object iAtm) {
        try {
            return getAtmTaskList(iAtm);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * Re-reads the stack list and reports whether {@code sid} is STILL there.
     *
     * <p>{@code IActivityTaskManager.removeStack(int)} does NOT delete the stack object: it
     * finishes the tasks <em>inside</em> the stack. On an already-empty orphan it is therefore
     * a silent no-op that returns normally, and cleanFissionStacks used to count that as a
     * removal — on-car it logged {@code removed=1} eleven consecutive times while the orphaned
     * split-screen-primary stack survived every pass, and the survivor kept NPE-ing
     * WindowManager ({@code ActivityStack.getBounds()} → {@code onConfigurationChanged:663})
     * on the next FREEFORM cluster launch. Nothing may be counted as removed until the stack
     * list itself confirms the disappearance.
     *
     * @return {@code TRUE} still present, {@code FALSE} gone, {@code null} list unreadable.
     */
    private static Boolean stackStillPresent(Object iAtm, int sid) {
        java.util.List<?> fresh = getAtmTaskListNoThrow(iAtm);
        if (fresh == null) return null;
        for (Object si : fresh) {
            if (si == null) continue;
            if (getStackOrRootTaskId(si) == sid) return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /**
     * MANDATORY SAFETY GUARD for the {@code removeStacksInWindowingModes} escalation: that verb
     * is display-agnostic and dissolves EVERY split-screen stack of the system. A live cluster
     * projection legitimately sits in a SPLIT_SCREEN_PRIMARY stack on DiLink 3
     * ({@link #moveAndResize} → setTaskWindowingModeWithBounds drives BYD's
     * setCustomTaskWindowingModeSplitScreenPrimary with modes {5,3,0}), so escalating while any
     * split stack still holds a task would kill the user's running projection.
     *
     * <p>Checks EVERY display, not just the fission one. A split stack whose child-task array
     * cannot be read counts as NON-empty (defensive), exactly like the per-stack keep below.
     *
     * @return {@code true} only when every split-screen stack in the system is task-less.
     */
    private static boolean allSplitStacksEmpty(java.util.List<?> stacks) {
        if (stacks == null || stacks.isEmpty()) return false;
        for (Object si : stacks) {
            if (si == null) continue;
            int wm = readWindowingMode(si);
            if (wm != WM_SPLIT_SCREEN_PRIMARY && wm != WM_SPLIT_SCREEN_SECONDARY) continue;
            int[] tids = getChildTaskIds(si);
            if (tids == null || tids.length != 0) return false;   // unreadable ⇒ assume live
        }
        return true;
    }

    /**
     * Reflective, never-throwing call to {@code removeStacksInWindowingModes(int[])}
     * (Android ≤11 / DiLink 3) or its Android-12+ name {@code removeRootTasksInWindowingModes}
     * (DiLink 5). Unlike {@code removeStack(int)} this routes to
     * {@code ActivityStackSupervisor.removeStack(stack)} and destroys the stack OBJECT — the
     * only Q-surface verb that can dissolve a 0-task stack.
     *
     * <p>Callers MUST gate this on {@link #allSplitStacksEmpty}.
     *
     * @return the verb actually invoked, or {@code null} when absent / it threw.
     */
    private static String removeStacksInWindowingModesNoThrow(Object iAtm, int[] modes,
                                                              StringBuilder log) {
        for (String verb : new String[]{
                "removeStacksInWindowingModes", "removeRootTasksInWindowingModes"}) {
            for (Method cand : iAtm.getClass().getMethods()) {
                if (!verb.equals(cand.getName())) continue;
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length != 1 || pt[0] != int[].class) continue;
                try {
                    cand.invoke(iAtm, (Object) modes);
                    return verb;
                } catch (Throwable rex) {
                    Throwable c = (rex instanceof java.lang.reflect.InvocationTargetException
                            && rex.getCause() != null) ? rex.getCause() : rex;
                    log.append("  ERR    ").append(verb).append(": ")
                       .append(c.getClass().getSimpleName())
                       .append(": ").append(c.getMessage()).append('\n');
                    return null;
                }
            }
        }
        return null;
    }

    public static String cleanFissionStacks(int displayId) {
        StringBuilder log = new StringBuilder();
        log.append("== cleanFissionStacks display=").append(displayId).append(" ==\n");
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            // DiLink 3: getAllStackInfos / DiLink 5 Android 12: getAllRootTaskInfos
            java.util.List<?> stacks = getAtmTaskList(iAtm);
            if (stacks.isEmpty()) { log.append("no stacks returned\n"); return log.toString(); }

            // DiLink 3: removeStack(int) / DiLink 5 Android 12: removeTask(int)
            Method removeStack = null;
            outer:
            for (String verb : new String[]{"removeStack", "removeTask"}) {
                for (Method cand : iAtm.getClass().getMethods()) {
                    if (!verb.equals(cand.getName())) continue;
                    Class<?>[] pt = cand.getParameterTypes();
                    if (pt.length == 1 && pt[0] == int.class) { removeStack = cand; break outer; }
                }
            }
            if (removeStack == null) {
                log.append("WARN: no removeStack/removeTask(int) on ATM proxy\n");
            }

            int removed = 0, kept = 0;
            // removeStacksInWindowingModes is system-wide: one escalation per pass is enough
            // (it dissolves every empty split stack at once) and keeps the blast radius minimal.
            boolean escalated = false;
            for (Object si : stacks) {
                if (si == null) continue;
                // DiLink 3: stackId field / DiLink 5 Android 12: taskId field
                Integer sid = null;
                {
                    Object v = readFieldNoThrow(si, "stackId");
                    if (v == null) v = readFieldNoThrow(si, "taskId");
                    if (v instanceof Integer) sid = (Integer) v;
                }
                Integer did = (Integer) readFieldNoThrow(si, "displayId");
                if (sid == null || did == null) continue;
                if (did != displayId) continue;
                int wmV = readWindowingMode(si);
                int atV = readActivityType(si);
                int[] tids = getChildTaskIds(si);
                // tids==null means the child-task-id read FAILED (a ROM whose task-array field
                // is not named as expected, or a transient reflection failure) — NOT that the
                // stack is empty. Treating it as "0 tasks → removable" could blank a LIVE
                // cluster/HUD projection whose tasks we simply couldn't read, so keep it
                // defensively (mirrors the wm/at unreadable keep below). Only a readable,
                // genuinely task-less stack reaches removeStack().
                if (tids == null) {
                    log.append("  keep?  stackId=").append(sid)
                       .append(" wm=").append(wmV).append(" at=").append(atV)
                       .append(" (task list unreadable, defensive keep)\n");
                    kept++;
                    continue;
                }
                int nTasks = tids.length;

                // FULLSCREEN: leave (a normal projection lives here).
                // HOME: leave (the display's fallback Activity).
                if (wmV == WM_FULLSCREEN || atV == AT_HOME) {
                    log.append("  keep   stackId=").append(sid)
                       .append(" wm=").append(wmV).append(" at=").append(atV)
                       .append(" tasks=").append(nTasks).append('\n');
                    kept++;
                    continue;
                }
                // Both unreadable: the class shape differs on this ROM — cannot safely
                // classify, so keep (never blind-empty the display). Should no longer
                // happen now that readWindowConfigInt() looks inside configuration.
                if (wmV == -1 && atV == -1) {
                    log.append("  keep?  stackId=").append(sid)
                       .append(" wm=-1 at=-1 (unreadable, defensive keep)\n");
                    kept++;
                    continue;
                }
                // A TASK-LESS, non-fullscreen, non-home stack on the fission display is a
                // zombie: it can never be the live projection, and a stale split-screen-primary
                // one poisons the display's split bookkeeping, so creating the next FREEFORM
                // stack throws NPE inside WindowManager and the launch fails silently
                // (INC-20260714-215700 — dump: stackId=5 mode=split-screen-primary
                // visible=false, 0 tasks). That is the zombie this verb exists to destroy.
                boolean splitStack = (wmV == WM_SPLIT_SCREEN_PRIMARY
                                   || wmV == WM_SPLIT_SCREEN_SECONDARY);
                // A stack that STILL HOLDS A TASK is NEVER removed — not even a split-screen
                // one. removeStack() finishes every activity in the stack, and on DiLink 3 the
                // live projection legitimately sits in a SPLIT_SCREEN_PRIMARY stack:
                // moveAndResize() → setTaskWindowingModeWithBounds() drives BYD's
                // setCustomTaskWindowingModeSplitScreenPrimary with modes {5,3,0}, and mode 3
                // IS split-screen-primary. Split mode also launches the 2nd app while the 1st
                // stays live (MainActivity → launchOnDashboardWithBounds → this verb), so
                // removing task-holding split stacks would kill the running app. If a live
                // split stack ever has to be dissolved, move the task out
                // (setTaskWindowingModeFreeform + moveStackOrRootTask) — never removeStack().
                if (nTasks != 0) {
                    log.append("  keep   stackId=").append(sid)
                       .append(" wm=").append(wmV).append(" at=").append(atV)
                       .append(" tasks=").append(nTasks)
                       .append(splitStack ? " (live split-screen — task-holding, kept)\n"
                                          : " (live)\n");
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
                       .append(" wm=").append(wmV).append(" at=").append(atV)
                       .append(" tasks=0")
                       .append(splitStack ? " (empty split-screen zombie)" : " (empty zombie)")
                       .append('\n');
                    // removeStack(int) finishes the tasks IN a stack — on a 0-task orphan it is
                    // a silent no-op, so the invoke returning normally proves NOTHING. Verify
                    // against the live stack list before claiming (or counting) a removal.
                    Boolean present = stackStillPresent(iAtm, sid);
                    if (Boolean.FALSE.equals(present)) {
                        log.append("  REMOVE-VERIFIED stackId=").append(sid).append('\n');
                        removed++;
                    } else if (present == null) {
                        log.append("  REMOVE-UNVERIFIED stackId=").append(sid)
                           .append(" (stack list unreadable — not counted)\n");
                    } else {
                        // Still there. Escalate ONCE to the only Q-surface verb that destroys
                        // the stack OBJECT instead of its tasks — hard-gated on "no split-screen
                        // stack anywhere holds a task" so a live projection can never be killed.
                        if (!escalated) {
                            escalated = true;
                            if (allSplitStacksEmpty(getAtmTaskListNoThrow(iAtm))) {
                                log.append("  ESCALATE removeStacksInWindowingModes{")
                                   .append(WM_SPLIT_SCREEN_PRIMARY).append(',')
                                   .append(WM_SPLIT_SCREEN_SECONDARY)
                                   .append("} (all split stacks task-less)\n");
                                String verb = removeStacksInWindowingModesNoThrow(iAtm,
                                        new int[]{WM_SPLIT_SCREEN_PRIMARY,
                                                  WM_SPLIT_SCREEN_SECONDARY}, log);
                                if (verb == null) {
                                    log.append("  ESCALATE-UNAVAILABLE (no "
                                             + "removeStacksInWindowingModes(int[]) on ATM)\n");
                                } else {
                                    present = stackStillPresent(iAtm, sid);
                                }
                            } else {
                                log.append("  ESCALATE-SKIPPED stackId=").append(sid)
                                   .append(" (a split-screen stack still holds tasks — refusing"
                                         + " to dissolve a live projection)\n");
                            }
                        }
                        if (Boolean.FALSE.equals(present)) {
                            log.append("  REMOVE-VERIFIED stackId=").append(sid)
                               .append(" (escalated)\n");
                            removed++;
                        } else {
                            log.append("  REMOVE-INEFFECTIVE stackId=").append(sid).append('\n');
                        }
                    }
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

    /**
     * Look up the stack/root-task ID containing {@code taskId}.
     * Tries getAllStackInfos (DiLink 3) then getAllRootTaskInfos (DiLink 5 / Android 12).
     * Returns -1 if not found.
     */
    public static int findStackIdForTask(int taskId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            for (Object si : getAtmTaskList(iAtm)) {
                if (si == null) continue;
                int[] tids = getChildTaskIds(si);
                if (tids == null) continue;
                for (int t : tids) if (t == taskId) return getStackOrRootTaskId(si);
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
                String nm = m.getName().toLowerCase(Locale.ROOT);
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
            // redirectErrorStream(true): stderr is merged into stdout so a single reader
            // drains ONE stream. The previous code read stdout fully THEN stderr, so a
            // child that filled its 64KB stderr pipe before closing stdout deadlocked the
            // reader (and stalled this binder thread until the waitFor timeout).
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process proc = p;
            // StringBuffer (not StringBuilder): the reader thread appends while the caller
            // reads out.toString() after a BOUNDED join, so the buffer is touched from two
            // threads — StringBuffer's synchronized append/toString removes that data race.
            final StringBuffer out = new StringBuffer();
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[4096];
                try (java.io.InputStream is = proc.getInputStream()) {
                    int n;
                    while ((n = is.read(buf)) > 0) out.append(new String(buf, 0, n));
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
