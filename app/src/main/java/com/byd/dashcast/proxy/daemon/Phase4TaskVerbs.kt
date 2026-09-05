package com.byd.dashcast.proxy.daemon

import android.content.ComponentName
import android.graphics.Rect
import android.util.Log

import com.byd.dashcast.infrastructure.task.TaskLocation

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.TimeUnit

/**
 * Phase4TaskVerbs — task and stack management verbs that run inside the daemon process (uid 2000).
 *
 * All methods here wrap IActivityTaskManager (ATM) reflection calls that control task placement,
 * windowing mode, resize, focus, and launch orchestration on the BYD DiLink 3.0 fission cluster
 * display.
 *
 * The full [launchAndForce] + async watchdog sequence is the only path confirmed to reliably land
 * an app in FREEFORM mode on a display that lacks `FLAG_SUPPORTS_FREEFORM_WINDOW_MANAGEMENT`
 * (BYD Seal EU, Android 10).
 *
 * @see Phase4DisplayVerbs
 * @see Phase4ProcessVerbs
 * @since v1.1.9 build 174 — split from Phase4Verbs in v1.4.4-beta.
 */
object Phase4TaskVerbs {

    private const val TAG = "Phase4TaskVerbs"

    // ─── Cached ATM reflection methods ───────────────────────────────────

    @Volatile private var sGetTasks: Method? = null
    @Volatile private var sMoveTaskToDisplay: Method? = null
    @Volatile private var sResizeTask: Method? = null

    /** One current guardian generation per package; different Layout slots run independently. */
    private val sWatchdogs = FissionWatchdogRegistry()

    // ─── Reflection helper ────────────────────────────────────────────────

    private fun readFieldNoThrow(target: Any, fieldName: String): Any? {
        return try {
            val f: Field = target.javaClass.getField(fieldName)
            f.isAccessible = true
            f.get(target)
        } catch (ignore: Throwable) {
            null
        }
    }

    // ─── Android 12 / RootTask compatibility (DiLink 5) ──────────────────

    /**
     * Returns the stack/root-task list from ATM. Tries getAllStackInfos() (Android ≤11, DiLink 3)
     * first, then falls back to getAllRootTaskInfos() (Android 12+, DiLink 5) when the former is
     * absent.
     */
    @Throws(Throwable::class)
    private fun getAtmTaskList(iAtm: Any): List<*> {
        val res: Any? = try {
            iAtm.javaClass.getMethod("getAllStackInfos").invoke(iAtm)
        } catch (e: NoSuchMethodException) {
            // Android 12+ (DiLink 5): getAllStackInfos was renamed to getAllRootTaskInfos
            iAtm.javaClass.getMethod("getAllRootTaskInfos").invoke(iAtm)
        }
        if (res is List<*>) return res
        if (res != null && res.javaClass.isArray) {
            @Suppress("UNCHECKED_CAST")
            return (res as Array<Any?>).toList()
        }
        return emptyList<Any?>()
    }

    /**
     * Returns the "stack / root-task" ID from a StackInfo or RootTaskInfo object. Tries stackId
     * (StackInfo, Android ≤11, DiLink 3) then taskId (RootTaskInfo, Android 12+, DiLink 5).
     */
    private fun getStackOrRootTaskId(info: Any): Int {
        var v = readFieldNoThrow(info, "stackId")
        if (v is Int) return v
        v = readFieldNoThrow(info, "taskId")
        if (v is Int) return v
        return -1
    }

    /**
     * Returns child task IDs from a StackInfo or RootTaskInfo. Tries taskIds (Android ≤11,
     * DiLink 3) then childTaskIds (Android 12+, DiLink 5).
     */
    private fun getChildTaskIds(info: Any): IntArray? {
        var v = readFieldNoThrow(info, "taskIds")
        if (v is IntArray) return v
        v = readFieldNoThrow(info, "childTaskIds")
        if (v is IntArray) return v
        return null
    }

    // WindowConfiguration constants (android.app.WindowConfiguration, @hide).
    private const val WM_FULLSCREEN = 1
    private const val WM_SPLIT_SCREEN_PRIMARY = 3
    private const val WM_SPLIT_SCREEN_SECONDARY = 4
    private const val AT_HOME = 2

    /**
     * Windowing mode / activity type of a StackInfo (Android ≤11, DiLink 3) or a RootTaskInfo
     * (Android 12+, DiLink 5).
     *
     * Neither class exposes these as public fields — they live inside
     * `configuration.windowConfiguration`. Reading them with [readFieldNoThrow] (what
     * cleanFissionStacks did before 1.6.112) therefore ALWAYS returned null on every ROM: every
     * stack was classified "unreadable" and defensively kept, so the zombie-stack cleanup never
     * removed anything, ever (confirmed across 17 on-car bug reports: `removed=0` in 100% of them).
     * That let a stale split-screen-primary stack survive on the fission display, which makes the
     * next FREEFORM stack creation NPE inside WindowManager and the launch fail silently
     * (INC-20260714-215700, DiLink 3.0).
     *
     * Four routes, most direct first, so a ROM that shifts the class shape again still resolves:
     * (1) getter on the info object, (2) configuration.windowConfiguration getter, (3) the textual
     * `mWindowingMode=…` of the configuration's toString, (4) the legacy public field. Returns -1
     * when every route fails.
     */
    private fun readWindowConfigInt(info: Any, getter: String, legacyField: String,
                                    names: Array<String>): Int {
        // 1. Direct getter on the info object (TaskInfo.getWindowingMode(), Android 12+).
        try {
            val v = info.javaClass.getMethod(getter).invoke(info)
            if (v is Int) return v
        } catch (ignore: Throwable) { /* not on this ROM — next route */ }

        val cfg = readFieldNoThrow(info, "configuration")
        val wc = if (cfg == null) null else readFieldNoThrow(cfg, "windowConfiguration")

        // 2. configuration.windowConfiguration.<getter>() — StackInfo (A10) and TaskInfo (A12).
        if (wc != null) {
            try {
                val v = wc.javaClass.getMethod(getter).invoke(wc)
                if (v is Int) return v
            } catch (ignore: Throwable) { /* next route */ }
        }

        // 3. Textual fallback: WindowConfiguration.toString() prints "mWindowingMode=freeform".
        val dump = if (wc != null) wc.toString() else if (cfg != null) cfg.toString() else null
        if (dump != null) {
            val key = if (getter == "getWindowingMode") "mWindowingMode=" else "mActivityType="
            val at = dump.indexOf(key)
            if (at >= 0) {
                val tail = dump.substring(at + key.length)
                // Longest name first: "split-screen-secondary" must not match "split-screen-p…".
                var best = -1
                for (i in names.indices) {
                    if (tail.startsWith(names[i])
                            && (best < 0 || names[i].length > names[best].length)) {
                        best = i
                    }
                }
                if (best >= 0) return best
            }
        }

        // 4. Legacy public field (kept in case some ROM does expose it).
        val v = readFieldNoThrow(info, legacyField)
        if (v is Int) return v
        return -1
    }

    /** Windowing mode of a StackInfo / RootTaskInfo, or -1 when unreadable. */
    private fun readWindowingMode(info: Any): Int =
            readWindowConfigInt(info, "getWindowingMode", "windowingMode", arrayOf(
                    "undefined", "fullscreen", "pinned",
                    "split-screen-primary", "split-screen-secondary", "freeform"))

    /** Activity type of a StackInfo / RootTaskInfo, or -1 when unreadable. */
    private fun readActivityType(info: Any): Int =
            readWindowConfigInt(info, "getActivityType", "activityType", arrayOf(
                    "undefined", "standard", "home", "recents", "assistant"))

    /**
     * True when an `am start-activity` transcript is a FAILED start.
     *
     * Beyond the usual "Error:" line, system_server can throw straight through the shell command:
     * am then prints "Starting: Intent {…}" (which looks like success) followed by "Exception
     * occurred while executing:" and a stack trace — and no activity ever starts. DiLink 3.0 does
     * exactly that when a FREEFORM stack cannot be created on the fission display. Testing only for
     * "Error:" declared that start a success, so the bare-MAIN fallback was never tried
     * (INC-20260714-215700).
     *
     * A null transcript keeps its historical meaning: no output ⇒ assume started.
     */
    private fun amStartFailed(out: String?): Boolean = TaskLaunchRecovery.isStartFailure(out)

    /** Polls up to ~5 s for `packageName`'s task id to appear. Returns -1 if none. */
    private fun pollForTaskId(packageName: String): Int {
        var taskId = -1
        var i = 1
        while (i <= 16 && taskId <= 0) {
            try {
                Thread.sleep(300)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            taskId = findTaskIdForPackage(packageName)
            i++
        }
        return taskId
    }

    /** Starts the app on `displayId` WITHOUT --windowingMode (default = fullscreen stack), the
     *  last-resort that avoids the FREEFORM-stack WindowManager NPE on some DiLink 3 ROMs. */
    private fun startPlainOnDisplay(log: StringBuilder, displayId: Int,
                                    cmpFlat: String?, packageName: String) {
        val target = if (cmpFlat != null) "-n $cmpFlat"
                     else "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
        val cmd = "am start-activity -S -W" +
                " --display " + displayId +
                " --activity-no-animation" +
                " " + target
        Log.w(TAG, "FISSION retrying without --windowingMode (freeform landed no task)")
        log.append("(retrying without --windowingMode)\n$ ").append(cmd).append('\n')
        val out = execShell(cmd, 5000)
        log.append(out ?: "(no output)").append('\n')
    }

    /** A failed FREEFORM creation leaves a new empty split stack, so clean again before plain. */
    private fun retryPlainOnCleanDisplay(log: StringBuilder, displayId: Int,
                                         cmpFlat: String?, packageName: String): Int {
        return TaskLaunchRecovery.retryOnCleanDisplay(object : TaskLaunchRecovery.Operations {
            override fun cleanDisplay(): String {
                val cleanup = cleanFissionStacks(displayId)
                log.append("(re-clean after FREEFORM failure)\n").append(cleanup)
                return cleanup
            }

            override fun launchPlain() {
                startPlainOnDisplay(log, displayId, cmpFlat, packageName)
            }

            override fun pollTask(): Int = pollForTaskId(packageName)
        })
    }

    /**
     * Moves a stack/root task to the target display. Tries moveStackToDisplay (Android ≤11,
     * DiLink 3) then moveRootTaskToDisplay (Android 12+, DiLink 5).
     */
    private fun moveStackOrRootTask(iAtm: Any, stackOrTaskId: Int, displayId: Int): String {
        try {
            iAtm.javaClass.getMethod("moveStackToDisplay", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).invoke(iAtm, stackOrTaskId, displayId)
            return "OK moveStackToDisplay($stackOrTaskId,$displayId)"
        } catch (e: NoSuchMethodException) {
            try {
                iAtm.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType).invoke(iAtm, stackOrTaskId, displayId)
                return "OK moveRootTaskToDisplay($stackOrTaskId,$displayId)"
            } catch (t2: Throwable) {
                val c = unwrap(t2)
                return "ERR moveRootTaskToDisplay: " + c.javaClass.simpleName + " — " + c.message
            }
        } catch (t: Throwable) {
            val c = unwrap(t)
            return "ERR moveStackToDisplay: " + c.javaClass.simpleName + " — " + c.message
        }
    }

    /** Unwraps an InvocationTargetException so callers report the real cause, as the Java did. */
    private fun unwrap(t: Throwable): Throwable =
            if (t is InvocationTargetException && t.cause != null) t.cause!! else t

    // ─── Task query ───────────────────────────────────────────────────────

    /** Resolve the display containing `taskId` from StackInfo/RootTaskInfo. */
    private fun findDisplayIdForTask(iAtm: Any, taskId: Int): Int {
        try {
            for (info in getAtmTaskList(iAtm)) {
                if (info == null) continue
                var containsTask = getStackOrRootTaskId(info) == taskId
                val taskIds = getChildTaskIds(info)
                if (!containsTask && taskIds != null) {
                    for (id in taskIds) {
                        if (id == taskId) {
                            containsTask = true
                            break
                        }
                    }
                }
                if (!containsTask) continue
                val displayId = readFieldNoThrow(info, "displayId")
                if (displayId is Int) return displayId
            }
        } catch (ignore: Throwable) {
            // FOUND with an unreadable display maps to UNKNOWN on the app side.
        }
        return TaskLocation.UNKNOWN_DISPLAY_ID
    }

    /**
     * Locate a package task and its current display. A completed ATM query with no matching task
     * returns ABSENT; inability to query or determine the task identity returns UNKNOWN.
     */
    @JvmStatic
    fun findTaskLocationForPackage(packageName: String?): TaskLocation {
        if (packageName == null || packageName.isEmpty()) return TaskLocation.unknown()
        return try {
            val locations = queryTaskLocationsForPackage(packageName)
            if (locations.isEmpty()) TaskLocation.absent() else locations[0]
        } catch (t: Throwable) {
            Log.w(TAG, "findTaskLocationForPackage($packageName) failed: $t")
            TaskLocation.unknown()
        }
    }

    /** Resolves ATM and delegates to [topTaskOnDisplay]; null on any failure (read-only). */
    private fun topTaskOnDisplayForWatchdog(targetDisplayId: Int): String? {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            topTaskOnDisplay(iAtm, targetDisplayId)
        } catch (ignore: Throwable) {
            null
        }
    }

    private fun findWatchdogTaskLocation(packageName: String, targetDisplayId: Int): TaskLocation {
        return try {
            FissionWatchdogPolicy.selectTask(
                    queryTaskLocationsForPackage(packageName, true), targetDisplayId)
        } catch (error: Throwable) {
            Log.w(TAG, "WATCHDOG task query failed pkg=$packageName: $error")
            TaskLocation.unknown()
        }
    }

    /**
     * True only when the task is POSITIVELY identified as a home/launcher task.
     *
     * Delegates to [readActivityType], the four-route reader already in this file. Reading this the
     * "obvious" way is a known trap: the activity type is NOT a usable public field on the task
     * object, and a hand-rolled `topActivityType` read short-circuits on the boxed `0` =
     * `ACTIVITY_TYPE_UNDEFINED`, which would make every other route dead code and turn the HOME
     * filter into a silent no-op. That is exactly the failure mode [readWindowConfigInt] was
     * written for in 1.6.112.
     *
     * Fails OPEN: [readActivityType] returns -1 when every route fails, so a task whose type cannot
     * be read is NOT treated as home and is kept — today's behaviour on any ROM.
     */
    private fun isHomeTask(task: Any): Boolean = readActivityType(task) == AT_HOME

    /**
     * Short description of the TOP root task currently on `displayId`, or `null` when it cannot be
     * determined. Read-only diagnostics: nothing acts on this value.
     *
     * Why this exists: DashCast could not see the failure mode behind INC-20260804-171617 at all.
     * [TaskLocation] carries only a task id and a display id, so the watchdog can detect "my task
     * moved to another display" but is blind to "my task stayed on the right display and was
     * covered by someone else" — which is exactly what the OEM does when it re-fronts its own map.
     * `visible` is useless as a signal here (the OEM overlay is translucent and non-occluding, so
     * our task stays visible=true while paused underneath), hence reading the task ORDER instead.
     *
     * CAVEAT, deliberately not asserted as fact: this assumes `getAllRootTaskInfos()` /
     * `getAllStackInfos()` returns tasks front-to-back. That ordering has NOT yet been confirmed on
     * any BYD ROM — the first on-car capture is what will confirm or refute it, which is precisely
     * why this only logs.
     */
    private fun topTaskOnDisplay(iAtm: Any, displayId: Int): String? {
        return try {
            for (info in getAtmTaskList(iAtm)) {
                if (info == null) continue
                val rawDisplay = readFieldNoThrow(info, "displayId")
                if (rawDisplay !is Int || rawDisplay != displayId) continue
                val id = getStackOrRootTaskId(info)
                val top = readFieldNoThrow(info, "topActivity") as ComponentName?
                val pkg = top?.flattenToShortString() ?: "?"
                return "taskId=$id top=$pkg"
            }
            null
        } catch (ignore: Throwable) {
            null
        }
    }

    /** Every task of `packageName`, home tasks included — the contract all callers but the
     *  watchdog rely on. */
    @Throws(Throwable::class)
    private fun queryTaskLocationsForPackage(packageName: String): List<TaskLocation> =
            queryTaskLocationsForPackage(packageName, false)

    @Throws(Throwable::class)
    private fun queryTaskLocationsForPackage(
            packageName: String, skipHomeTasks: Boolean): List<TaskLocation> {
        val atmCls = Class.forName("android.app.ActivityTaskManager")
        val iAtm = atmCls.getMethod("getService").invoke(null)!!
        var getTasks = sGetTasks
        if (getTasks == null) {
            for (candidate in iAtm.javaClass.methods) {
                if ("getTasks" == candidate.name) {
                    getTasks = candidate
                    break
                }
            }
            sGetTasks = getTasks
        }
        if (getTasks == null) throw NoSuchMethodException("getTasks")

        val parameterTypes = getTasks.parameterTypes
        val args = arrayOfNulls<Any>(parameterTypes.size)
        for (index in parameterTypes.indices) {
            if (parameterTypes[index] == Int::class.javaPrimitiveType) {
                args[index] = if (index == 0) 64 else 0
            } else if (parameterTypes[index] == Boolean::class.javaPrimitiveType) {
                args[index] = false
            } else {
                args[index] = null
            }
        }
        val rawTasks = getTasks.invoke(iAtm, *args)
        if (rawTasks !is List<*>) {
            throw IllegalStateException("getTasks returned " +
                    (if (rawTasks == null) "null" else rawTasks.javaClass.name))
        }

        val locations = ArrayList<TaskLocation>()
        var identityUnknown = false
        for (task in rawTasks) {
            if (task == null) continue
            val topActivity = readFieldNoThrow(task, "topActivity") as ComponentName?
            val baseActivity = readFieldNoThrow(task, "baseActivity") as ComponentName?
            var packageMatches = topActivity != null && packageName == topActivity.packageName
            packageMatches = packageMatches ||
                    (baseActivity != null && packageName == baseActivity.packageName)
            if (!packageMatches) continue

            var rawTaskId = readFieldNoThrow(task, "taskId")
            if (rawTaskId == null) rawTaskId = readFieldNoThrow(task, "id")
            if (rawTaskId !is Int) {
                identityUnknown = true
                continue
            }
            val taskId = rawTaskId
            // Never hand a HOME task to the WATCHDOG. An OEM package can own several tasks (e.g.
            // com.byd.launchermap owns both the cluster MeterActivity and a head-unit home page);
            // FissionWatchdogPolicy.selectTask returns the first task found on another display, so
            // it latched onto the home task on display 0 and then re-anchored it 180 times, each
            // one refused by the framework ("Root task ... of activityType=2 already on
            // display=DefaultTaskDisplayArea. Can't have multiple"). That flooded ~5000 logcat
            // lines and evicted the framework evidence from the bug report (INC-20260804-171617).
            //
            // Scoped to the watchdog on purpose. This query is ALSO the backing store for
            // findTaskLocationForPackage → the eviction landing-wait, forceStopApp, moveAndResize
            // and the SurfaceDaemon slot verbs; hiding a task from those would turn a FOUND task
            // into ABSENT and, for a package whose only task is home-typed, burn the shared landing
            // budget before force-stopping anyway. Those callers keep seeing every task.
            //
            // Fails OPEN regardless: readActivityType returns -1 when unreadable, so a task whose
            // type cannot be determined is kept.
            if (skipHomeTasks && isHomeTask(task)) {
                Log.d(TAG, "watchdog: skip home task pkg=$packageName taskId=$taskId")
                continue
            }
            val rawDisplayId = readFieldNoThrow(task, "displayId")
            val displayId = if (rawDisplayId is Int) rawDisplayId
                            else findDisplayIdForTask(iAtm, taskId)
            locations.add(TaskLocation.found(taskId, displayId))
        }
        if (identityUnknown) locations.add(TaskLocation.unknown())
        return locations
    }

    /**
     * Lookup the taskId hosting `packageName` (any activity). Returns -1 if no such task exists.
     * Reads `ActivityTaskManager.getService().getTasks(maxNum,…)` and inspects each
     * `RecentTaskInfo.topActivity`. Enumerates all overloads of `getTasks` to handle BYD-specific
     * signature variants.
     */
    @JvmStatic
    fun findTaskIdForPackage(packageName: String?): Int {
        val location = findTaskLocationForPackage(packageName)
        return if (location.status == TaskLocation.Status.FOUND) location.taskId else -1
    }

    // ─── Task removal ─────────────────────────────────────────────────────

    /**
     * Remove `taskId` from the ActivityTaskManager recents stack via reflection
     * (`IActivityTaskManager.removeTask(int)`). Must be called before
     * [Phase4ProcessVerbs.forceStopPackage] to avoid leaving an orphan task on display 0 after
     * session teardown.
     *
     * Throws if the method cannot be found or the call fails — callers should fall back to
     * `am task remove` via ADB on any exception.
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun removeTask(taskId: Int) {
        val atmCls = Class.forName("android.app.ActivityTaskManager")
        val iAtm = atmCls.getMethod("getService").invoke(null)!!
        var removeTask: Method? = null
        for (cand in iAtm.javaClass.methods) {
            if ("removeTask" == cand.name) {
                val pt = cand.parameterTypes
                if (pt.size == 1 && pt[0] == Int::class.javaPrimitiveType) {
                    removeTask = cand
                    break
                }
            }
        }
        if (removeTask == null) {
            throw NoSuchMethodException("removeTask(int) not found on " + iAtm.javaClass)
        }
        removeTask.invoke(iAtm, taskId)
    }

    // ─── Task movement ────────────────────────────────────────────────────

    /**
     * Move an existing task to `displayId` via reflection. Enumerates `getMethods()` to catch any
     * BYD-renamed variant (moveTaskToDisplay / moveRootTaskToDisplay / moveTopActivityToDisplay …)
     * with 2 int params.
     */
    @JvmStatic
    fun moveTaskToDisplay(taskId: Int, displayId: Int): String {
        try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            var target = sMoveTaskToDisplay
            var methods: Array<Method>? = null
            if (target == null) {
                val preferred = arrayOf(
                        "moveRootTaskToDisplay",
                        "moveTaskToDisplay",
                        "moveTopActivityToDisplay",
                        "reparentTaskToDisplay")
                methods = iAtm.javaClass.methods
                outer@ for (wanted in preferred) {
                    for (m in methods) {
                        if (m.name != wanted) continue
                        val pt = m.parameterTypes
                        if (pt.size == 2 && pt[0] == Int::class.javaPrimitiveType
                                && pt[1] == Int::class.javaPrimitiveType) {
                            target = m
                            break@outer
                        }
                    }
                }
                if (target != null) sMoveTaskToDisplay = target
            }
            if (target == null) {
                if (methods == null) methods = iAtm.javaClass.methods
                val dump = StringBuilder("ERR moveTaskToDisplay: no (int,int) variant. Candidates: ")
                var first = true
                for (m in methods) {
                    val n = m.name
                    if (n.contains("Display") || n.contains("Stack") || n.contains("Task")) {
                        if (!first) dump.append(", ")
                        dump.append(n).append('(')
                        val pt = m.parameterTypes
                        for (i in pt.indices) {
                            if (i > 0) dump.append(',')
                            dump.append(pt[i].simpleName)
                        }
                        dump.append(')')
                        first = false
                    }
                }
                return dump.toString()
            }
            target.invoke(iAtm, taskId, displayId)
            return "OK " + target.name + "(" + taskId + "," + displayId + ")"
        } catch (t: Throwable) {
            val cause = unwrap(t)
            return "ERR moveTaskToDisplay: " + cause.javaClass.simpleName + " — " + cause.message
        }
    }

    /**
     * Moves a task using the direct task API when available, then falls back to the containing
     * stack/root-task API used by DiLink 3/Android 10. The incident ROM exposes
     * `moveStackToDisplay(int,int)` but no direct `moveTaskToDisplay(int,int)`.
     */
    @JvmStatic
    fun moveTaskToDisplayCompatible(taskId: Int, displayId: Int): String =
            TaskMoveResult.runWithFallback(
                    { moveTaskToDisplay(taskId, displayId) },
                    { moveTaskToDisplayViaStack(taskId, displayId) })

    /**
     * BYD Seal EU / Android 10 stack-based move:
     * 1) flip task to FREEFORM via AOSP setTaskWindowingMode (also flips the containing stack —
     *    required for subsequent resizeTask to be accepted).
     * 2) find the task's stackId + current displayId via getAllStackInfos().
     * 3) moveStackToDisplay(stackId, displayId), skipped if already on target.
     */
    @JvmStatic
    fun moveTaskToDisplayViaStack(taskId: Int, displayId: Int): String {
        val log = StringBuilder()
        try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!

            // Step A: flip task (and containing stack) to FREEFORM.
            try {
                var setWm: Method
                var which: String
                try {
                    setWm = iAtm.javaClass.getMethod("setTaskWindowingMode",
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType)
                    which = "setTaskWindowingMode"
                } catch (nsm: NoSuchMethodException) {
                    setWm = iAtm.javaClass.getMethod("setCustomTaskWindowingMode",
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType)
                    which = "setCustomTaskWindowingMode"
                }
                setWm.invoke(iAtm, taskId, 5 /*WINDOWING_MODE_FREEFORM*/, true /*toTop*/)
                log.append("OK ").append(which).append('(').append(taskId).append(",FREEFORM)")
            } catch (wmEx: Throwable) {
                val c = unwrap(wmEx)
                log.append("WARN setTaskWindowingMode: ").append(c.javaClass.simpleName)
                   .append(" — ").append(c.message)
            }
            log.append(" ; ")

            // Step B: find stackId AND its current displayId.
            // Tries getAllStackInfos (DiLink 3) then getAllRootTaskInfos (DiLink 5 / Android 12).
            var stackId = -1
            var currentDisplayId = -1
            try {
                val stacks = getAtmTaskList(iAtm)
                for (si in stacks) {
                    if (si == null) continue
                    val tids = getChildTaskIds(si) ?: continue
                    for (t in tids) {
                        if (t == taskId) {
                            stackId = getStackOrRootTaskId(si)
                            val did = readFieldNoThrow(si, "displayId")
                            if (did is Int) currentDisplayId = did
                            break
                        }
                    }
                    if (stackId != -1) break
                }
            } catch (lookupEx: Throwable) {
                log.append("WARN getAtmTaskList: ").append(lookupEx.javaClass.simpleName)
                   .append(" — ").append(lookupEx.message).append(" ; ")
            }
            log.append("stackId=").append(stackId).append(" currentDisplay=")
               .append(currentDisplayId).append(" ; ")
            if (stackId < 0) {
                log.append("ERR no stack for task=").append(taskId)
                return log.toString()
            }

            // Step C: moveStackToDisplay (DiLink 3) or moveRootTaskToDisplay (DiLink 5 / Android 12).
            if (currentDisplayId == displayId) {
                log.append("SKIP move (already on display ").append(displayId).append(')')
                return log.toString()
            }
            log.append(moveStackOrRootTask(iAtm, stackId, displayId))
            return log.toString()
        } catch (t: Throwable) {
            val cause = unwrap(t)
            log.append("ERR moveTaskToDisplayViaStack: ")
               .append(cause.javaClass.simpleName)
               .append(" — ").append(cause.message)
            return log.toString()
        }
    }

    // ─── Task resize ──────────────────────────────────────────────────────

    /**
     * Resize an existing task. RESIZE_MODE_FORCED = 1 (skips user-driven gate). Pass all-zero
     * bounds to clear (full display). Enumerates resize* signatures to adapt to BYD/AOSP variants.
     */
    @JvmStatic
    fun resizeTaskRect(taskId: Int, l: Int, t: Int, r: Int, b: Int): String {
        try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            val bounds = if (l == 0 && t == 0 && r == 0 && b == 0) null else Rect(l, t, r, b)

            var m = sResizeTask
            var args: Array<Any?>? = null
            var methods: Array<Method>? = null
            if (m == null) {
                methods = iAtm.javaClass.methods
                for (cand in methods) {
                    if (cand.name != "resizeTask") continue
                    val pt = cand.parameterTypes
                    if (pt.size == 3 && pt[0] == Int::class.javaPrimitiveType
                            && pt[1] == Rect::class.java
                            && pt[2] == Int::class.javaPrimitiveType) {
                        m = cand; break
                    }
                    if (pt.size == 2 && pt[0] == Int::class.javaPrimitiveType
                            && pt[1] == Rect::class.java) {
                        m = cand
                    }
                }
                if (m != null) sResizeTask = m
            }
            if (m != null) {
                args = if (m.parameterTypes.size == 3) arrayOf<Any?>(taskId, bounds, 1)
                       else arrayOf<Any?>(taskId, bounds)
            }
            if (m == null) {
                if (methods == null) methods = iAtm.javaClass.methods
                val dump = StringBuilder("ERR resizeTask: no variant. Candidates: ")
                var first = true
                for (cand in methods) {
                    if (!cand.name.lowercase(Locale.ROOT).contains("resize")) continue
                    if (!first) dump.append(", ")
                    dump.append(cand.name).append('(')
                    val pt = cand.parameterTypes
                    for (i in pt.indices) {
                        if (i > 0) dump.append(',')
                        dump.append(pt[i].simpleName)
                    }
                    dump.append(')')
                    first = false
                }
                return dump.toString()
            }
            val rv = m.invoke(iAtm, *args!!)
            // API 29 throws when canResizeTask() refuses (caught below); API 30+ logs and returns
            // false instead, and this used to be discarded — so on DiLink 5.0/5.1/AAOS a refused
            // resize was reported as OK, which is worse for triage than the ERR it looked like it
            // was avoiding. Proven in INC-20260804-171617: system_server logs "W ActivityTaskManager:
            // resizeTask not allowed on task=Task{...#122...}" and 2 ms later our own transcript
            // says "OK resizeTask(122,...)", nine times over. false == null is false, so the void
            // API-29 signature keeps its existing throw-driven path untouched.
            if (rv == false) {
                return "SKIP resizeTask: task is not in a resizable windowing mode" +
                        " (bounds already set by the preceding verb)"
            }
            return "OK " + m.name + "(" + taskId + "," +
                    (bounds?.toShortString() ?: "null") + ")"
        } catch (ex: Throwable) {
            val cause = unwrap(ex)
            val msg = cause.message
            // Expected outcome, not a failure: WindowConfiguration.canResizeTask() is FREEFORM-only,
            // and BYD's own docking verb — which runs immediately before this one carrying the same
            // bounds — leaves the task outside FREEFORM on DiLink 3. The rect still lands; the
            // closing getTaskBounds says so. Reported as SKIP so triage (and MoveAndResizeOutcome)
            // stop reading a platform rule as a defect. Any other failure keeps the ERR marker.
            if (cause is IllegalArgumentException
                    && msg != null && msg.contains("resizeTask not allowed on task=")) {
                return "SKIP resizeTask: task is not in a resizable windowing mode" +
                        " (bounds already set by the preceding verb)"
            }
            return "ERR resizeTask: " + cause.javaClass.simpleName +
                    " — " + (msg ?: cause.toString())
        }
    }

    // ─── Task focus / windowing mode ──────────────────────────────────────

    /** Set focused task — drives input and brings task to top of its display. */
    @JvmStatic
    fun setFocusedRootTask(taskId: Int): String {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            val m: Method = try {
                iAtm.javaClass.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType)
            } catch (nsm: NoSuchMethodException) {
                iAtm.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType)
            }
            m.invoke(iAtm, taskId)
            "OK " + m.name + "(" + taskId + ")"
        } catch (t: Throwable) {
            "ERR setFocusedRootTask: " + t.javaClass.simpleName + " — " + t.message
        }
    }

    /**
     * Force a task to be resizeable. resizeMode values (AOSP): 0=UNRESIZEABLE 1=CROP_WINDOWS
     * 2=RESIZEABLE 3=RESIZEABLE_AND_PIPABLE 4=FORCE_RESIZEABLE.
     */
    @JvmStatic
    fun setTaskResizeable(taskId: Int, resizeMode: Int): String {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            val m = iAtm.javaClass.getMethod("setTaskResizeable",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            m.invoke(iAtm, taskId, resizeMode)
            "OK setTaskResizeable($taskId,$resizeMode)"
        } catch (t: Throwable) {
            val c = unwrap(t)
            "ERR setTaskResizeable: " + c.javaClass.simpleName + " — " + c.message
        }
    }

    /**
     * Set a task's windowing mode to FREEFORM (5) using the best available ATM API. Needed after
     * moveStackToDisplay(), which creates a new FULLSCREEN stack on the target display and
     * overrides the task's prior FREEFORM mode.
     */
    @JvmStatic
    fun setTaskWindowingModeFreeform(taskId: Int): String {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            try {
                val m = iAtm.javaClass.getMethod("setTaskWindowingMode",
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType)
                m.invoke(iAtm, taskId, 5 /*WINDOWING_MODE_FREEFORM*/, true /*toTop*/)
                "OK setTaskWindowingMode($taskId,FREEFORM,true)"
            } catch (e1: NoSuchMethodException) {
                val m = iAtm.javaClass.getMethod("setCustomTaskWindowingMode",
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType)
                m.invoke(iAtm, taskId, 5, true)
                "OK setCustomTaskWindowingMode($taskId,FREEFORM,true)"
            }
        } catch (t: Throwable) {
            val c = unwrap(t)
            "ERR setTaskWindowingModeFreeform: " + c.javaClass.simpleName + " — " + c.message
        }
    }

    // ─── Launch orchestration ─────────────────────────────────────────────

    /**
     * Full OpenBYD 2.0 launchAndForce sequence: am start → poll task id → move + resize + focus,
     * with an async watchdog that re-anchors the task if it bounces back to display 0 (Waze
     * FLAG_ACTIVITY_LAUNCH_ADJACENT).
     *
     * The only path confirmed to land an app in FREEFORM mode on a display that lacks
     * `FLAG_SUPPORTS_FREEFORM_WINDOW_MANAGEMENT` (BYD Seal EU). Do NOT gate, add fallbacks, or add
     * mode checks — the cascade as a whole is the point. See
     * `doc_api/CLUSTER_RESIZE_SEQUENCE.md`.
     *
     * @return multi-line log of every step for caller-side rendering.
     */
    @JvmStatic
    fun launchAndForce(packageName: String, activityClass: String?,
                       displayId: Int, width: Int, height: Int): String {
        val log = StringBuilder()
        log.append("== launchAndForce ").append(packageName)
           .append(if (activityClass != null) "/$activityClass" else "")
           .append(" → display=").append(displayId)
           .append(' ').append(width).append('x').append(height).append(" ==\n")
        Log.i(TAG, "FISSION launchAndForce START pkg=$packageName displayId=$displayId " +
                width + "x" + height)
        try {
            // Pre-cleanup: nuke any zombie split-screen-primary / freeform stack.
            log.append(cleanFissionStacks(displayId))

            // Always force-stop: running task keeps existing stack's mode otherwise.
            log.append("$ am force-stop ").append(packageName).append('\n')
            val stopOut = execShell("am force-stop $packageName", 3000)
            log.append(stopOut ?: "(no output)").append('\n')

            // Resolve component if caller didn't provide one.
            var cmpFlat = if (activityClass != null) "$packageName/$activityClass" else null
            if (cmpFlat == null) {
                val resolveCmd = "cmd package resolve-activity --brief" +
                        " -a android.intent.action.MAIN" +
                        " -c android.intent.category.LAUNCHER" +
                        " " + packageName
                log.append("$ ").append(resolveCmd).append('\n')
                val resolveOut = execShell(resolveCmd, 3000)
                log.append(resolveOut ?: "(no output)").append('\n')
                if (resolveOut != null) {
                    for (raw in resolveOut.split(Regex("\\r?\\n"))) {
                        val line = raw.trim()
                        if (line.contains("/") && !line.startsWith("[err]")
                                && line != packageName) {
                            cmpFlat = line
                            break
                        }
                    }
                }
                log.append("resolved component = ").append(cmpFlat).append('\n')
            }

            var started = false
            var freeformStackFailure = false

            // Dilink5 Dashboard pattern: -S -W --windowingMode 5 --display N pkg/cls.
            if (cmpFlat != null) {
                val cmd = "am start-activity -S -W" +
                        " --windowingMode 5" +
                        " --display " + displayId +
                        " --activity-no-animation" +
                        " -n " + cmpFlat
                Log.i(TAG, "FISSION am start: $cmd")
                log.append("$ ").append(cmd).append('\n')
                val out = execShell(cmd, 5000)
                log.append(out ?: "(no output)").append('\n')
                started = !amStartFailed(out)
                freeformStackFailure = TaskLaunchRecovery.isFreeformStackFailure(out)
            }

            // Fallback: bare MAIN with -p when component resolution failed.
            // Do not repeat the same --windowingMode launch after the known BYD framework NPE:
            // it only recreates another poisoned split stack. Re-clean and go plain instead.
            if (!started && !freeformStackFailure) {
                val cmd = "am start-activity -S -W" +
                        " --windowingMode 5" +
                        " --display " + displayId +
                        " -a android.intent.action.MAIN" +
                        " -c android.intent.category.LAUNCHER" +
                        " --activity-no-animation" +
                        " -p " + packageName
                log.append("$ ").append(cmd).append('\n')
                val out = execShell(cmd, 5000)
                log.append(out ?: "(no output)").append('\n')
                started = !amStartFailed(out)
                freeformStackFailure = TaskLaunchRecovery.isFreeformStackFailure(out)
            }

            // Last-resort (no --windowingMode): on some DiLink 3.0 ROMs, creating a FREEFORM
            // stack on the fission display throws inside WindowManager (NPE in
            // ActivityStack.onConfigurationChanged) so NO activity starts. Dropping the windowing
            // mode lets the activity land in the display's default (fullscreen) stack — visible on
            // the cluster at full size — and the FREEFORM flip further down still gets its chance.
            // EARLY trigger: both --windowingMode 5 attempts clearly threw (am printed the error).
            var triedPlain = false
            var taskId: Int
            if (!started) {
                taskId = retryPlainOnCleanDisplay(log, displayId, cmpFlat, packageName)
                triedPlain = true
            } else {
                // Poll up to ~5 s for the task to appear.
                taskId = pollForTaskId(packageName)
            }
            log.append("findTask(post-poll) = ").append(taskId).append('\n')

            // LATE trigger: the freeform attempt reported "started" (no am error text) yet landed
            // NO task — the FREEFORM stack creation failed silently on this ROM (INC-20260716-091016,
            // where started=true skipped the old !started-gated last-resort). Retry once without the
            // windowing mode, keyed on the ACTUAL outcome (no task) rather than the am exit text.
            if (taskId <= 0 && !triedPlain) {
                taskId = retryPlainOnCleanDisplay(log, displayId, cmpFlat, packageName)
                log.append("findTask(post-fallback) = ").append(taskId).append('\n')
            }

            if (taskId <= 0) {
                log.append("FAIL: no task discovered for ").append(packageName).append('\n')
                return log.toString()
            }

            // Placement sequence mirrors DevTool handleLaunchAndForce exactly.
            // This is the ONLY sequence confirmed to work on BYD DiLink 3.0.
            log.append("  ").append(Phase4DisplayVerbs.setDisplayToSingleTaskInstance(displayId)).append('\n')
            log.append("  ").append(setTaskResizeable(taskId, 4 /*FORCE_RESIZEABLE*/)).append('\n')

            // Resolve real stackId via getAllStackInfos (DiLink 3) or getAllRootTaskInfos (DiLink 5 / Android 12).
            var stackId = -1
            try {
                val ac = Class.forName("android.app.ActivityTaskManager")
                val ia = ac.getMethod("getService").invoke(null)!!
                val ss = getAtmTaskList(ia)
                outer2@ for (si in ss) {
                    if (si == null) continue
                    val tids = getChildTaskIds(si) ?: continue
                    for (t in tids) {
                        if (t == taskId) {
                            stackId = getStackOrRootTaskId(si)
                            break@outer2
                        }
                    }
                }
                log.append("  stackId=").append(stackId).append('\n')
            } catch (ex: Throwable) {
                log.append("  WARN getAtmTaskList: ").append(ex.message).append('\n')
            }

            // FREEFORM pre-move: flip task+stack to FREEFORM before the move.
            log.append("  ").append(setTaskWindowingModeFreeform(taskId)).append('\n')

            // moveStackToDisplay (DiLink 3) or moveRootTaskToDisplay (DiLink 5 / Android 12).
            if (stackId >= 0) {
                try {
                    val ac = Class.forName("android.app.ActivityTaskManager")
                    val ia = ac.getMethod("getService").invoke(null)!!
                    log.append("  ").append(moveStackOrRootTask(ia, stackId, displayId)).append('\n')
                } catch (ex: Throwable) {
                    val c = unwrap(ex)
                    log.append("  moveStackOrRootTask: ").append(c.javaClass.simpleName)
                       .append(" — ").append(c.message).append('\n')
                }
            }

            // FREEFORM post-move: moveStackToDisplay creates a FULLSCREEN stack; re-apply FREEFORM.
            log.append("  ").append(setTaskWindowingModeFreeform(taskId)).append('\n')

            log.append("  ").append(resizeTaskRect(taskId, 0, 0, width, height)).append('\n')
            log.append("  ").append(setFocusedRootTask(taskId)).append('\n')

            val watchdogStatus = startFissionWatchdog(packageName, taskId, displayId, width, height)
            log.append(watchdogStatus).append('\n')
            log.append("FINISH: launchAndForce complete.\n")
            Log.i(TAG, "FISSION launchAndForce DONE pkg=$packageName taskId=$taskId " +
                    "displayId=$displayId watchdog=$watchdogStatus")
        } catch (t: Throwable) {
            log.append("EXCEPTION: ").append(t).append('\n')
            Log.e(TAG, "FISSION launchAndForce EXCEPTION: $t")
        }
        return log.toString()
    }

    private fun startFissionWatchdog(packageName: String, initialTaskId: Int,
                                     targetDisplayId: Int, width: Int, height: Int): String {
        val generation = sWatchdogs.start(packageName)
        val policy = FissionWatchdogPolicy()
        val watchdog = Thread({
            var currentTaskId = initialTaskId
            var reanchorCount = 0
            // Last observed top task on the cluster display — logged only when it CHANGES, so this
            // costs a handful of lines per session instead of one per 500 ms poll.
            var lastTop: String? = null
            try {
                for (poll in 1..FissionWatchdogPolicy.MAX_POLLS) {
                    try {
                        Thread.sleep(FissionWatchdogPolicy.POLL_INTERVAL_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    if (!sWatchdogs.isCurrent(packageName, generation)) {
                        Log.d(TAG, "WATCHDOG superseded pkg=$packageName generation=$generation")
                        ProxyDaemonMain.log("WATCHDOG superseded pkg=$packageName" +
                                " generation=$generation poll=$poll reanchors=$reanchorCount")
                        return@Thread
                    }

                    val location = findWatchdogTaskLocation(packageName, targetDisplayId)
                    val match = location.matchDisplay(targetDisplayId)

                    // Z-order observation only — never acted upon. Records WHO is on top of the
                    // cluster display, which is the one thing the report could not tell us when the
                    // OEM re-fronted its own map over a successfully launched app.
                    // Sampled every 4th poll (2 s): the OEM's re-front floor is ~1.1 s, so 2 s loses
                    // no transition worth naming, and it keeps three quarters of the extra binder
                    // traffic off DL3/DL4 — platforms that gain nothing from this DL5.0 probe.
                    val top = if (poll % 4 == 0) topTaskOnDisplayForWatchdog(targetDisplayId) else null
                    if (top != null && top != lastTop) {
                        Log.i(TAG, "WATCHDOG cluster-top display=$targetDisplayId" +
                                " poll=$poll" +
                                " now=[" + top + "]" +
                                " was=[" + (lastTop ?: "-") + "]" +
                                " ours=" + packageName)
                        lastTop = top
                    }
                    if (location.status == TaskLocation.Status.FOUND
                            && location.taskId != currentTaskId) {
                        Log.i(TAG, "WATCHDOG adopted new task pkg=$packageName" +
                                " oldTask=$currentTaskId newTask=" + location.taskId)
                        ProxyDaemonMain.log("WATCHDOG adopted task pkg=$packageName" +
                                " old=$currentTaskId new=" + location.taskId +
                                " display=" + location.displayId + " poll=" + poll)
                        currentTaskId = location.taskId
                    }

                    val action = policy.onPoll(poll, match)
                    if (action == FissionWatchdogPolicy.Action.COMPLETE) {
                        ProxyDaemonMain.log("WATCHDOG complete pkg=$packageName" +
                                " task=$currentTaskId polls=$poll" +
                                " reanchors=$reanchorCount final=$match")
                        Log.i(TAG, "WATCHDOG complete pkg=$packageName task=$currentTaskId" +
                                " polls=$poll reanchors=$reanchorCount final=$match")
                        return@Thread
                    }
                    if (action != FissionWatchdogPolicy.Action.REANCHOR || currentTaskId <= 0) {
                        continue
                    }

                    reanchorCount++
                    val move = moveTaskToDisplayViaStack(currentTaskId, targetDisplayId)
                    val mode = setTaskWindowingModeFreeform(currentTaskId)
                    val resize = resizeTaskRect(currentTaskId, 0, 0, width, height)
                    val focus = setFocusedRootTask(currentTaskId)
                    // Thinned, not dropped: the terminal WATCHDOG complete line carries the
                    // total, and the transcript reaches a report as tail -200.
                    if (FissionWatchdogPolicy.shouldMirrorReanchor(reanchorCount)) {
                        ProxyDaemonMain.log("WATCHDOG re-anchor pkg=$packageName" +
                                " task=$currentTaskId from=" + location.displayId +
                                " to=$targetDisplayId poll=$poll n=$reanchorCount" +
                                " move=" + FissionWatchdogPolicy.brief(move) +
                                " focus=" + FissionWatchdogPolicy.brief(focus))
                    }
                    Log.i(TAG, "WATCHDOG re-anchor pkg=$packageName" +
                            " task=$currentTaskId" +
                            " from=" + location.displayId +
                            " to=$targetDisplayId" +
                            " poll=$poll" +
                            " move=[" + move + "]" +
                            " mode=[" + mode + "]" +
                            " resize=[" + resize + "]" +
                            " focus=[" + focus + "]")
                }
            } catch (error: Throwable) {
                Log.w(TAG, "WATCHDOG unexpected pkg=$packageName: $error")
                ProxyDaemonMain.log("WATCHDOG unexpected pkg=$packageName: $error")
            } finally {
                sWatchdogs.finish(packageName, generation)
            }
        }, "fission-watchdog-$initialTaskId")
        watchdog.isDaemon = true
        return try {
            watchdog.start()
            "WATCHDOG started pkg=$packageName" +
                    " task=$initialTaskId" +
                    " display=$targetDisplayId" +
                    " guard=" + FissionWatchdogPolicy.INITIAL_GUARD_POLLS *
                    FissionWatchdogPolicy.POLL_INTERVAL_MS / 1000 + "s" +
                    " max=" + FissionWatchdogPolicy.MAX_POLLS *
                    FissionWatchdogPolicy.POLL_INTERVAL_MS / 1000 + "s"
        } catch (startError: Throwable) {
            sWatchdogs.finish(packageName, generation)
            Log.w(TAG, "WATCHDOG start failed pkg=$packageName: $startError")
            "WATCHDOG failed pkg=$packageName error=" + startError.javaClass.simpleName
        }
    }

    /** Cancels the latest guardian before an intentional move/release during Layout teardown. */
    @JvmStatic
    fun cancelFissionWatchdog(packageName: String?): Boolean =
            if (packageName == null) sWatchdogs.cancelAll() > 0
            else sWatchdogs.cancel(packageName)

    // ─── Stack management ─────────────────────────────────────────────────

    /** [getAtmTaskList] that never throws; `null` means "could not be read". */
    private fun getAtmTaskListNoThrow(iAtm: Any): List<*>? {
        return try {
            getAtmTaskList(iAtm)
        } catch (ignore: Throwable) {
            null
        }
    }

    /**
     * Re-reads the stack list and reports whether `sid` is STILL there.
     *
     * `IActivityTaskManager.removeStack(int)` does NOT delete the stack object: it finishes the
     * tasks *inside* the stack. On an already-empty orphan it is therefore a silent no-op that
     * returns normally, and cleanFissionStacks used to count that as a removal — on-car it logged
     * `removed=1` eleven consecutive times while the orphaned split-screen-primary stack survived
     * every pass, and the survivor kept NPE-ing WindowManager (`ActivityStack.getBounds()` →
     * `onConfigurationChanged:663`) on the next FREEFORM cluster launch. Nothing may be counted as
     * removed until the stack list itself confirms the disappearance.
     *
     * @return `TRUE` still present, `FALSE` gone, `null` list unreadable.
     */
    private fun stackStillPresent(iAtm: Any, sid: Int): Boolean? {
        val fresh = getAtmTaskListNoThrow(iAtm) ?: return null
        for (si in fresh) {
            if (si == null) continue
            if (getStackOrRootTaskId(si) == sid) return true
        }
        return false
    }

    /**
     * MANDATORY SAFETY GUARD for the `removeStacksInWindowingModes` escalation: that verb is
     * display-agnostic and dissolves EVERY split-screen stack of the system. A live cluster
     * projection legitimately sits in a SPLIT_SCREEN_PRIMARY stack on DiLink 3 ([moveAndResize] →
     * setTaskWindowingModeWithBounds drives BYD's setCustomTaskWindowingModeSplitScreenPrimary with
     * modes {5,3,0}), so escalating while any split stack still holds a task would kill the user's
     * running projection.
     *
     * Checks EVERY display, not just the fission one. A split stack whose child-task array cannot
     * be read counts as NON-empty (defensive), exactly like the per-stack keep below.
     *
     * @return `true` only when every split-screen stack in the system is task-less.
     */
    private fun allSplitStacksEmpty(stacks: List<*>?): Boolean {
        if (stacks == null || stacks.isEmpty()) return false
        for (si in stacks) {
            if (si == null) continue
            val wm = readWindowingMode(si)
            if (wm != WM_SPLIT_SCREEN_PRIMARY && wm != WM_SPLIT_SCREEN_SECONDARY) continue
            val tids = getChildTaskIds(si)
            if (tids == null || tids.size != 0) return false   // unreadable ⇒ assume live
        }
        return true
    }

    /**
     * Reflective, never-throwing call to `removeStacksInWindowingModes(int[])` (Android ≤11 /
     * DiLink 3) or its Android-12+ name `removeRootTasksInWindowingModes` (DiLink 5). Unlike
     * `removeStack(int)` this routes to `ActivityStackSupervisor.removeStack(stack)` and destroys
     * the stack OBJECT — the only Q-surface verb that can dissolve a 0-task stack.
     *
     * Callers MUST gate this on [allSplitStacksEmpty].
     *
     * @return the verb actually invoked, or `null` when absent / it threw.
     */
    private fun removeStacksInWindowingModesNoThrow(iAtm: Any, modes: IntArray,
                                                    log: StringBuilder): String? {
        for (verb in arrayOf("removeStacksInWindowingModes", "removeRootTasksInWindowingModes")) {
            for (cand in iAtm.javaClass.methods) {
                if (verb != cand.name) continue
                val pt = cand.parameterTypes
                if (pt.size != 1 || pt[0] != IntArray::class.java) continue
                try {
                    cand.invoke(iAtm, modes)
                    return verb
                } catch (rex: Throwable) {
                    val c = unwrap(rex)
                    log.append("  ERR    ").append(verb).append(": ")
                       .append(c.javaClass.simpleName)
                       .append(": ").append(c.message).append('\n')
                    return null
                }
            }
        }
        return null
    }

    /**
     * Destroy the zombie stacks on `displayId` (the fission/cluster display). Recovery verb for
     * when a prior session left a stack in split-screen-primary mode, causing "Can only have one
     * child on stack…mode=split-screen-primary" — or, on DiLink 3.0, a WindowManager NPE while
     * creating the next FREEFORM stack — on the next launch.
     *
     * Removes: any non-fullscreen, non-home stack that holds NO task (a stale split-screen one
     * included). Keeps fullscreen stacks, home stacks, and EVERY task-holding stack — so it can
     * never tear down the running projection, which on DiLink 3 legitimately sits in a
     * split-screen-primary stack after a resize. Every call site is a pre-launch cleanup.
     *
     * Safe to call repeatedly — on a clean display the loop finds nothing to remove.
     *
     * @return multi-line log of every stack inspected and removed.
     */
    @JvmStatic
    fun cleanFissionStacks(displayId: Int): String {
        val log = StringBuilder()
        log.append("== cleanFissionStacks display=").append(displayId).append(" ==\n")
        try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            // DiLink 3: getAllStackInfos / DiLink 5 Android 12: getAllRootTaskInfos
            val stacks = getAtmTaskList(iAtm)
            if (stacks.isEmpty()) { log.append("no stacks returned\n"); return log.toString() }

            // DiLink 3: removeStack(int) / DiLink 5 Android 12: removeTask(int)
            var removeStack: Method? = null
            outer@ for (verb in arrayOf("removeStack", "removeTask")) {
                for (cand in iAtm.javaClass.methods) {
                    if (verb != cand.name) continue
                    val pt = cand.parameterTypes
                    if (pt.size == 1 && pt[0] == Int::class.javaPrimitiveType) {
                        removeStack = cand; break@outer
                    }
                }
            }
            if (removeStack == null) {
                log.append("WARN: no removeStack/removeTask(int) on ATM proxy\n")
            }

            var removed = 0
            var kept = 0
            // removeStacksInWindowingModes is system-wide: one escalation per pass is enough
            // (it dissolves every empty split stack at once) and keeps the blast radius minimal.
            var escalated = false
            for (si in stacks) {
                if (si == null) continue
                // DiLink 3: stackId field / DiLink 5 Android 12: taskId field
                var sid: Int? = null
                run {
                    var v = readFieldNoThrow(si, "stackId")
                    if (v == null) v = readFieldNoThrow(si, "taskId")
                    if (v is Int) sid = v
                }
                val did = readFieldNoThrow(si, "displayId") as Int?
                val stackId = sid
                if (stackId == null || did == null) continue
                if (did != displayId) continue
                val wmV = readWindowingMode(si)
                val atV = readActivityType(si)
                val tids = getChildTaskIds(si)
                // tids==null means the child-task-id read FAILED (a ROM whose task-array field
                // is not named as expected, or a transient reflection failure) — NOT that the
                // stack is empty. Treating it as "0 tasks → removable" could blank a LIVE
                // cluster/HUD projection whose tasks we simply couldn't read, so keep it
                // defensively (mirrors the wm/at unreadable keep below). Only a readable,
                // genuinely task-less stack reaches removeStack().
                if (tids == null) {
                    log.append("  keep?  stackId=").append(stackId)
                       .append(" wm=").append(wmV).append(" at=").append(atV)
                       .append(" (task list unreadable, defensive keep)\n")
                    kept++
                    continue
                }
                val nTasks = tids.size

                // FULLSCREEN: leave (a normal projection lives here).
                // HOME: leave (the display's fallback Activity).
                if (wmV == WM_FULLSCREEN || atV == AT_HOME) {
                    log.append("  keep   stackId=").append(stackId)
                       .append(" wm=").append(wmV).append(" at=").append(atV)
                       .append(" tasks=").append(nTasks).append('\n')
                    kept++
                    continue
                }
                // Both unreadable: the class shape differs on this ROM — cannot safely
                // classify, so keep (never blind-empty the display). Should no longer
                // happen now that readWindowConfigInt() looks inside configuration.
                if (wmV == -1 && atV == -1) {
                    log.append("  keep?  stackId=").append(stackId)
                       .append(" wm=-1 at=-1 (unreadable, defensive keep)\n")
                    kept++
                    continue
                }
                // A TASK-LESS, non-fullscreen, non-home stack on the fission display is a
                // zombie: it can never be the live projection, and a stale split-screen-primary
                // one poisons the display's split bookkeeping, so creating the next FREEFORM
                // stack throws NPE inside WindowManager and the launch fails silently
                // (INC-20260714-215700 — dump: stackId=5 mode=split-screen-primary
                // visible=false, 0 tasks). That is the zombie this verb exists to destroy.
                val splitStack = (wmV == WM_SPLIT_SCREEN_PRIMARY || wmV == WM_SPLIT_SCREEN_SECONDARY)
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
                    log.append("  keep   stackId=").append(stackId)
                       .append(" wm=").append(wmV).append(" at=").append(atV)
                       .append(" tasks=").append(nTasks)
                       .append(if (splitStack) " (live split-screen — task-holding, kept)\n"
                               else " (live)\n")
                    kept++
                    continue
                }
                if (removeStack == null) {
                    log.append("  SKIP   stackId=").append(stackId)
                       .append(" wm=").append(wmV).append(" (no removeStack verb)\n")
                    continue
                }
                try {
                    removeStack.invoke(iAtm, stackId)
                    log.append("  REMOVE stackId=").append(stackId)
                       .append(" wm=").append(wmV).append(" at=").append(atV)
                       .append(" tasks=0")
                       .append(if (splitStack) " (empty split-screen zombie)" else " (empty zombie)")
                       .append('\n')
                    // removeStack(int) finishes the tasks IN a stack — on a 0-task orphan it is
                    // a silent no-op, so the invoke returning normally proves NOTHING. Verify
                    // against the live stack list before claiming (or counting) a removal.
                    var present = stackStillPresent(iAtm, stackId)
                    if (present == false) {
                        log.append("  REMOVE-VERIFIED stackId=").append(stackId).append('\n')
                        removed++
                    } else if (present == null) {
                        log.append("  REMOVE-UNVERIFIED stackId=").append(stackId)
                           .append(" (stack list unreadable — not counted)\n")
                    } else {
                        // Still there. Escalate ONCE to the only Q-surface verb that destroys
                        // the stack OBJECT instead of its tasks — hard-gated on "no split-screen
                        // stack anywhere holds a task" so a live projection can never be killed.
                        if (!escalated) {
                            escalated = true
                            if (allSplitStacksEmpty(getAtmTaskListNoThrow(iAtm))) {
                                log.append("  ESCALATE removeStacksInWindowingModes{")
                                   .append(WM_SPLIT_SCREEN_PRIMARY).append(',')
                                   .append(WM_SPLIT_SCREEN_SECONDARY)
                                   .append("} (all split stacks task-less)\n")
                                val verb = removeStacksInWindowingModesNoThrow(iAtm,
                                        intArrayOf(WM_SPLIT_SCREEN_PRIMARY,
                                                   WM_SPLIT_SCREEN_SECONDARY), log)
                                if (verb == null) {
                                    log.append("  ESCALATE-UNAVAILABLE (no " +
                                            "removeStacksInWindowingModes(int[]) on ATM)\n")
                                } else {
                                    present = stackStillPresent(iAtm, stackId)
                                }
                            } else {
                                log.append("  ESCALATE-SKIPPED stackId=").append(stackId)
                                   .append(" (a split-screen stack still holds tasks — refusing" +
                                           " to dissolve a live projection)\n")
                            }
                        }
                        if (present == false) {
                            log.append("  REMOVE-VERIFIED stackId=").append(stackId)
                               .append(" (escalated)\n")
                            removed++
                        } else {
                            log.append("  REMOVE-INEFFECTIVE stackId=").append(stackId).append('\n')
                        }
                    }
                } catch (rex: Throwable) {
                    val c = unwrap(rex)
                    log.append("  ERR    stackId=").append(stackId).append(": ")
                       .append(c.javaClass.simpleName)
                       .append(": ").append(c.message).append('\n')
                }
            }
            log.append("done — removed=").append(removed).append(" kept=").append(kept).append('\n')
        } catch (t: Throwable) {
            log.append("EXCEPTION: ").append(t).append('\n')
        }
        return log.toString()
    }

    /**
     * Look up the stack/root-task ID containing `taskId`. Tries getAllStackInfos (DiLink 3) then
     * getAllRootTaskInfos (DiLink 5 / Android 12). Returns -1 if not found.
     */
    @JvmStatic
    fun findStackIdForTask(taskId: Int): Int {
        try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            for (si in getAtmTaskList(iAtm)) {
                if (si == null) continue
                val tids = getChildTaskIds(si) ?: continue
                for (t in tids) if (t == taskId) return getStackOrRootTaskId(si)
            }
        } catch (ignore: Throwable) {}
        return -1
    }

    /**
     * Flip a stack's windowing mode via every known ATM verb. Tries, in order:
     * setActivityStackWindowingMode, setStackWindowingMode (multiple arities),
     * setActivityStackWindowingModeForced.
     */
    @JvmStatic
    fun setStackWindowingMode(stackId: Int, mode: Int): String {
        try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            val names = arrayOf(
                    "setActivityStackWindowingMode",
                    "setStackWindowingMode",
                    "setActivityStackWindowingModeForced")
            for (name in names) {
                for (cand in iAtm.javaClass.methods) {
                    if (cand.name != name) continue
                    val pt = cand.parameterTypes
                    try {
                        if (pt.size == 2 && pt[0] == Int::class.javaPrimitiveType
                                && pt[1] == Int::class.javaPrimitiveType) {
                            cand.invoke(iAtm, stackId, mode)
                            return "OK $name($stackId,$mode)"
                        }
                        if (pt.size == 3 && pt[0] == Int::class.javaPrimitiveType
                                && pt[1] == Int::class.javaPrimitiveType
                                && pt[2] == Boolean::class.javaPrimitiveType) {
                            cand.invoke(iAtm, stackId, mode, true)
                            return "OK $name($stackId,$mode,true)"
                        }
                    } catch (inv: Throwable) {
                        val c = unwrap(inv)
                        return "ERR $name: " + c.javaClass.simpleName + " — " + c.message
                    }
                }
            }
            return "SKIP setStackWindowingMode: no candidate method"
        } catch (t: Throwable) {
            return "ERR setStackWindowingMode: " + t.javaClass.simpleName + " — " + t.message
        }
    }

    /**
     * Resize a stack directly. Does NOT hit the `canResizeTask()` gate (which requires FREEFORM
     * windowing mode). Useful when the display lacks `FLAG_SUPPORTS_FREEFORM`.
     */
    @JvmStatic
    fun resizeStackRect(stackId: Int, l: Int, t: Int, r: Int, b: Int): String {
        try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            val bounds = if (l == 0 && t == 0 && r == 0 && b == 0) null else Rect(l, t, r, b)
            val methods = iAtm.javaClass.methods
            var m: Method? = null
            var args: Array<Any?>? = null
            for (cand in methods) {
                if (cand.name != "resizeStack") continue
                val pt = cand.parameterTypes
                // AOSP Android 10 signature: (int, Rect, boolean, boolean, boolean, int)
                if (pt.size == 6 && pt[0] == Int::class.javaPrimitiveType
                        && pt[1] == Rect::class.java
                        && pt[2] == Boolean::class.javaPrimitiveType
                        && pt[3] == Boolean::class.javaPrimitiveType
                        && pt[4] == Boolean::class.javaPrimitiveType
                        && pt[5] == Int::class.javaPrimitiveType) {
                    m = cand
                    args = arrayOf<Any?>(stackId, bounds, true, true, false, -1)
                    break
                }
                if (pt.size == 2 && pt[0] == Int::class.javaPrimitiveType
                        && pt[1] == Rect::class.java) {
                    m = cand
                    args = arrayOf<Any?>(stackId, bounds)
                }
            }
            if (m == null) return "SKIP resizeStack: no matching variant"
            m.invoke(iAtm, *args!!)
            return "OK resizeStack(" + stackId + "," +
                    (bounds?.toShortString() ?: "null") + ")"
        } catch (ex: Throwable) {
            val cause = unwrap(ex)
            return "ERR resizeStack: " + cause.javaClass.simpleName + " — " + cause.message
        }
    }

    // ─── Diagnostics ──────────────────────────────────────────────────────

    /** Dump every ATM method whose name matches one of the given lowercase substrings. */
    @JvmStatic
    fun dumpAtmMethods(substrings: Array<String>): String {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            val out = StringBuilder("ATM methods matching ")
                    .append(substrings.contentToString()).append(":\n")
            val sorted = TreeSet<String>()
            for (m in iAtm.javaClass.methods) {
                val nm = m.name.lowercase(Locale.ROOT)
                var match = false
                for (s in substrings) if (nm.contains(s)) { match = true; break }
                if (!match) continue
                val sig = StringBuilder(m.name).append('(')
                val pt = m.parameterTypes
                for (i in pt.indices) {
                    if (i > 0) sig.append(',')
                    sig.append(pt[i].simpleName)
                }
                sig.append(')')
                sorted.add(sig.toString())
            }
            for (s in sorted) out.append("  ").append(s).append('\n')
            out.toString()
        } catch (t: Throwable) {
            "ERR dumpAtmMethods: $t"
        }
    }

    /** Read a task's current bounds via getTaskBounds(int) — verifies whether a resize took effect. */
    @JvmStatic
    fun getTaskBoundsVerb(taskId: Int): String {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            val m = iAtm.javaClass.getMethod("getTaskBounds", Int::class.javaPrimitiveType)
            val res = m.invoke(iAtm, taskId)
            "getTaskBounds(" + taskId + ") = " +
                    (if (res is Rect) res.toShortString() else java.lang.String.valueOf(res))
        } catch (t: Throwable) {
            val c = unwrap(t)
            "ERR getTaskBounds: " + c.javaClass.simpleName + " — " + c.message
        }
    }

    /**
     * BYD-specific verb that takes a Rect alongside a windowing mode hint. Present on BYD Seal EU /
     * Android 10 — used by BYD's own WindowManagement to place floating windows on the fission
     * cluster display.
     *
     * Tries modes 5=FREEFORM, 3=SPLIT_SCREEN_PRIMARY, 0 in order. Returns the first OK result, or
     * the last ERR.
     */
    @JvmStatic
    fun setTaskWindowingModeWithBounds(taskId: Int, l: Int, t: Int, r: Int, b: Int): String {
        val log = StringBuilder()
        try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)!!
            val bounds = Rect(l, t, r, b)

            val names = arrayOf(
                    "setCustomTaskWindowingModeSplitScreenPrimary",
                    "setTaskWindowingModeSplitScreenPrimary")
            val modes = intArrayOf(5, 3, 0)

            var target: Method? = null
            var targetName: String? = null
            for (nm in names) {
                for (cand in iAtm.javaClass.methods) {
                    if (cand.name != nm) continue
                    val pt = cand.parameterTypes
                    if (pt.size == 6
                            && pt[0] == Int::class.javaPrimitiveType
                            && pt[1] == Int::class.javaPrimitiveType
                            && pt[2] == Boolean::class.javaPrimitiveType
                            && pt[3] == Boolean::class.javaPrimitiveType
                            && pt[4] == Rect::class.java
                            && pt[5] == Boolean::class.javaPrimitiveType) {
                        target = cand
                        targetName = nm
                        break
                    }
                }
                if (target != null) break
            }
            if (target == null) return "SKIP setTaskWindowingModeWithBounds: no matching variant"

            var lastEx: Throwable?
            for (mode in modes) {
                try {
                    target.invoke(iAtm, taskId, mode,
                            true /*onTop*/, false /*animate*/, bounds, false /*showRecents*/)
                    log.append("OK ").append(targetName).append('(')
                       .append(taskId).append(",mode=").append(mode)
                       .append(',').append(bounds.toShortString()).append(')')
                    return log.toString()
                } catch (ex: Throwable) {
                    lastEx = unwrap(ex)
                    log.append("[").append(mode).append("→")
                       .append(lastEx.javaClass.simpleName)
                       .append(": ").append(lastEx.message).append("] ")
                }
            }
            return "ERR $targetName all modes failed: $log"
        } catch (ex: Throwable) {
            return "ERR setTaskWindowingModeWithBounds: " +
                    ex.javaClass.simpleName + " — " + ex.message
        }
    }

    /**
     * Move + resize a task on the BYD cluster fission display.
     *
     * This is the EXACT v1.2.61 sequence, validated in v1.2.70 as the only pipeline that reliably
     * repositions Waze on every slider drag without crashing system_server. See
     * `doc_api/CLUSTER_RESIZE_SEQUENCE.md`.
     *
     * **Do not** add if/else gating, mode checks, or fallbacks — every previous "smart" variant
     * regressed because StackInfo.windowingMode is unreadable on this ROM (always -1). The cascade
     * as a whole is the point.
     */
    @JvmStatic
    fun moveAndResize(packageName: String, displayId: Int,
                      l: Int, t: Int, r: Int, b: Int): String {
        val log = StringBuilder()
        log.append("== moveAndResize ").append(packageName)
           .append(" → display=").append(displayId)
           .append(" rect=[").append(l).append(',').append(t).append(',')
           .append(r).append(',').append(b).append("] ==\n")
        try {
            val taskId = findTaskIdForPackage(packageName)
            log.append("findTask = ").append(taskId).append('\n')
            if (taskId <= 0) {
                log.append("FAIL: no task for ").append(packageName)
                   .append(" — launch the app first via launchAndForce.\n")
                return log.toString()
            }
            // EXACT v1.2.61 sequence — every verb contributes; the cascade as a whole is the point.
            log.append("  ").append(Phase4DisplayVerbs.setDisplayToSingleTaskInstance(displayId)).append('\n')
            log.append("  ").append(moveTaskToDisplayViaStack(taskId, displayId)).append('\n')
            log.append("  ").append(setTaskResizeable(taskId, 4)).append('\n')
            val stackId = findStackIdForTask(taskId)
            log.append("stackId = ").append(stackId).append('\n')
            if (stackId > 0) {
                log.append("  ").append(setStackWindowingMode(stackId, 5)).append('\n')
                log.append("  ").append(resizeStackRect(stackId, l, t, r, b)).append('\n')
            }
            log.append("  ").append(setTaskWindowingModeWithBounds(taskId, l, t, r, b)).append('\n')
            log.append("  ").append(resizeTaskRect(taskId, l, t, r, b)).append('\n')
            log.append("  ").append(setFocusedRootTask(taskId)).append('\n')
            log.append("  ").append(getTaskBoundsVerb(taskId)).append('\n')
            log.append("FINISH: moveAndResize complete.\n")
        } catch (ex: Throwable) {
            log.append("EXCEPTION: ").append(ex).append('\n')
        }
        return log.toString()
    }

    // ─── Shell helper ─────────────────────────────────────────────────────

    private fun execShell(command: String, timeoutMs: Int): String? {
        var p: Process? = null
        try {
            // redirectErrorStream(true): stderr is merged into stdout so a single reader
            // drains ONE stream. The previous code read stdout fully THEN stderr, so a
            // child that filled its 64KB stderr pipe before closing stdout deadlocked the
            // reader (and stalled this binder thread until the waitFor timeout).
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            p = pb.start()
            val proc = p
            // StringBuffer (not StringBuilder): the reader thread appends while the caller
            // reads out.toString() after a BOUNDED join, so the buffer is touched from two
            // threads — StringBuffer's synchronized append/toString removes that data race.
            val out = StringBuffer()
            val reader = Thread {
                val buf = ByteArray(4096)
                try {
                    proc.inputStream.use { input ->
                        var n = input.read(buf)
                        while (n > 0) {
                            out.append(String(buf, 0, n))
                            n = input.read(buf)
                        }
                    }
                } catch (ignore: Throwable) {}
            }
            reader.start()
            val finished = proc.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                try { p.destroyForcibly() } catch (ignore: Throwable) {}
                return "[execShell timeout ${timeoutMs}ms]"
            }
            reader.join(maxOf(200, timeoutMs / 4).toLong())
            return out.toString().trim()
        } catch (t: Throwable) {
            return "[execShell EXCEPTION] $t"
        } finally {
            if (p != null) try { p.destroy() } catch (ignore: Throwable) {}
        }
    }
}
