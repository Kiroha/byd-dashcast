package com.byd.dashcast.proxy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

import com.byd.dashcast.util.AppLogger;

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

    /** Our own package. Used to make the wrapped context report the app's
     *  identity to the BYD SDK (see {@link #adoptIdentity}). */
    public static final String SELF_PKG = "com.byd.dashcast";

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
                sCached = adoptIdentity(sys);
                sLastError = null;
                AppLogger.i(TAG, "system context acquired: pkg=" + sys.getPackageName()
                        + " → wrapped identity=" + sCached.getPackageName());
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
        return wrap(base, null);
    }

    /**
     * Wrap {@code base} so every permission check returns GRANTED, optionally
     * forcing {@link Context#getPackageName()} to report {@code spoofPkg}.
     *
     * <p>Spoofing the package name is what lets the BYD SDK's internal
     * identity check pass for surfaces that gate on the <em>calling package</em>
     * rather than (or in addition to) the calling uid. This mirrors
     * {@code BydContextWrapper.getPackageName()} in OpenBYD 2.2, which reports
     * the app package instead of the system context's {@code "android"}.
     *
     * @param base     context to wrap (usually the system context, or a
     *                 {@code createPackageContext} of our own package)
     * @param spoofPkg package name to report from {@link Context#getPackageName()},
     *                 or {@code null} to delegate to {@code base}
     */
    public static Context wrap(Context base, final String spoofPkg) {
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
            // Report the app's package identity to any SDK code that keys off
            // getPackageName() (defensive: also covers the fallback path where
            // createPackageContext failed and base is the raw system context).
            @Override public String getPackageName() {
                return spoofPkg != null ? spoofPkg : super.getPackageName();
            }
            // Propagate the wrapper to derived contexts: the BYD SDK sometimes calls
            // context.getApplicationContext().checkXxx() rather than context.checkXxx() directly.
            // Without this override the bypass would not apply to that second-hop call.
            @Override public Context getApplicationContext() {
                Context appCtx = super.getApplicationContext();
                return appCtx == null ? this : SystemContextHelper.wrap(appCtx, spoofPkg);
            }
        };
    }

    /**
     * Build a permission-bypass context that ALSO adopts our own package
     * identity — the faithful equivalent of OpenBYD 2.2's
     * {@code SystemContext.get()} (createPackageContext + BydContextWrapper).
     *
     * <p>Two layers of identity adoption:
     * <ol>
     *   <li>{@code createPackageContext(SELF_PKG, INCLUDE_CODE | IGNORE_SECURITY)}
     *       makes {@code getApplicationInfo()} / {@code getPackageManager()}
     *       resolve to our package. {@code IGNORE_SECURITY} is what allows this
     *       from the system context despite the signature mismatch.</li>
     *   <li>{@link #wrap(Context, String)} forces {@code getPackageName()} to
     *       {@link #SELF_PKG} and keeps every permission check at GRANTED.</li>
     * </ol>
     *
     * <p>Regression-safe: if {@code createPackageContext} throws (hardened ROM),
     * we fall back to wrapping the raw system context — identical to the prior
     * behaviour, plus the {@code getPackageName} spoof.
     *
     * @param systemContext the raw system context from {@code getSystemContext()}
     * @return a non-null wrapped, identity-adopting context
     */
    public static Context adoptIdentity(Context systemContext) {
        Context base = systemContext;
        try {
            base = systemContext.createPackageContext(
                    SELF_PKG,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            AppLogger.i(TAG, "adoptIdentity: createPackageContext(" + SELF_PKG + ") ok");
        } catch (Throwable t) {
            AppLogger.w(TAG, "adoptIdentity: createPackageContext(" + SELF_PKG
                    + ") failed, using raw system context — " + t);
        }
        return wrap(base, SELF_PKG);
    }

    /** Clear the cache — used by tests that want to re-run the reflection from scratch. */
    public static void clearCache() {
        synchronized (SystemContextHelper.class) {
            sCached = null;
            sLastError = null;
        }
    }
}
