package com.byd.dashcast.proxy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

import com.byd.dashcast.AppLogger;

import java.lang.reflect.Method;

/**
 * SystemContextHelper — Component B of the Beta Engine.
 *
 * <p>Obtains a system-uid {@link Context} via reflection on
 * {@code android.app.ActivityThread.systemMain().getSystemContext()} and wraps
 * it with permission self-checks that always return GRANTED.
 *
 * <p>Used as the {@code Context} parameter when calling
 * {@code BYDAuto*Device.getInstance(ctx)} so that the SDK's internal permission
 * checks (which would otherwise return {@code null} on our APK) are bypassed.
 *
 * <p>This is purely a reflection-based helper — no native code, no daemon —
 * and works inside the normal app process. Failure modes:
 * <ul>
 *   <li>{@code Class.forName} returns {@link ClassNotFoundException} on a
 *       hardened ROM that strips {@code ActivityThread}.</li>
 *   <li>{@code systemMain} or {@code getSystemContext} not visible
 *       ({@link NoSuchMethodException}).</li>
 *   <li>{@code getSystemContext} returns {@code null} if our process was not
 *       started in a way that registered with the system server.</li>
 * </ul>
 * All failures are logged and propagated; the gateway is responsible for the
 * fallback decision.
 *
 * @see <a href="file:../../../external_code/BydAgent.java">BydAgent.java</a> — original pattern
 */
public final class SystemContextHelper {

    private SystemContextHelper() {}

    private static final String TAG = "SystemContextHelper";

    /** Singleton cache — reflection is expensive, the system context never changes within a process. */
    @SuppressLint("StaticFieldLeak")
    private static volatile Context sCached = null;
    private static volatile Throwable sLastError = null;

    /**
     * Returns a {@link Context} representing the system server, wrapped so that
     * every permission check returns {@code PackageManager.PERMISSION_GRANTED}.
     *
     * @return non-null wrapped system context on success.
     * @throws Exception if {@code ActivityThread} or {@code getSystemContext}
     *                   reflection fails. The caller is responsible for
     *                   catching and falling back.
     */
    public static Context get() throws Exception {
        Context c = sCached;
        if (c != null) return c;
        synchronized (SystemContextHelper.class) {
            if (sCached != null) return sCached;
            // ActivityThread.systemMain() instantiates an internal Handler, which
            // requires a Looper on the current thread. Match the BydAgent
            // reference pattern (external_code/BydAgent.java) and prepare one if
            // the caller is a background thread without a Looper.
            if (Looper.myLooper() == null) {
                try { Looper.prepare(); }
                catch (Throwable ignore) { /* another thread or already prepared */ }
            }
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Method   m1 = at.getMethod("systemMain");
                Object   thread = m1.invoke(null);
                Method   m2 = at.getMethod("getSystemContext");
                Context  sys = (Context) m2.invoke(thread);
                if (sys == null) {
                    throw new IllegalStateException("ActivityThread.getSystemContext() returned null");
                }
                sCached = wrap(sys);
                sLastError = null;
                AppLogger.i(TAG, "system context acquired: pkg=" + sys.getPackageName());
                return sCached;
            } catch (Throwable t) {
                sLastError = t;
                AppLogger.e(TAG, "failed to acquire system context", t instanceof Exception ? (Exception) t : new Exception(t));
                if (t instanceof Exception) throw (Exception) t;
                throw new Exception(t);
            }
        }
    }

    /**
     * Quick test: returns true if the system context can be obtained, without
     * caching on failure and without raising — used by the Diag tests.
     */
    public static boolean isAvailable() {
        try { get(); return sCached != null; }
        catch (Throwable t) { return false; }
    }

    /** Last error encountered while trying to obtain the system context, or {@code null}. */
    public static Throwable getLastError() { return sLastError; }

    /**
     * Wrap the given Context so that every permission check returns GRANTED.
     * Used internally; exposed for tests that want to wrap an arbitrary base.
     */
    public static Context wrap(Context base) {
        return new ContextWrapper(base) {
            @Override public int checkSelfPermission(String p)              { return PackageManager.PERMISSION_GRANTED; }
            @Override public int checkPermission(String p, int pid, int uid) { return PackageManager.PERMISSION_GRANTED; }
            @Override public int checkCallingPermission(String p)           { return PackageManager.PERMISSION_GRANTED; }
            @Override public int checkCallingOrSelfPermission(String p)     { return PackageManager.PERMISSION_GRANTED; }
            @Override public void enforceCallingOrSelfPermission(String p, String m) {}
            @Override public void enforceCallingPermission(String p, String m)       {}
            @Override public void enforcePermission(String p, int pid, int uid, String m) {}
            @Override public void enforceUriPermission(android.net.Uri u, int pid, int uid, int mod, String m) {}
            @Override public void enforceUriPermission(android.net.Uri u, String r, String w, int pid, int uid, int mod, String m) {}
            // Propagate the wrapper to derived contexts: the BYD SDK sometimes calls
            // context.getApplicationContext().checkXxx() rather than context.checkXxx() directly.
            // Without this override the bypass would not apply to that second-hop call.
            @Override public Context getApplicationContext() {
                Context appCtx = super.getApplicationContext();
                return appCtx == null ? this : SystemContextHelper.wrap(appCtx);
            }
        };
    }

    /** Clear the cache — used by tests that want to re-run the reflection from scratch. */
    public static void clearCache() {
        synchronized (SystemContextHelper.class) {
            sCached = null;
            sLastError = null;
        }
    }
}
