package com.byd.dashcast.proxy

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper

import com.byd.dashcast.util.AppLogger

import java.lang.reflect.Method

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
// The cached system Context is a deliberate process-lifetime singleton (reflection is
// expensive and the system context never changes within a process); it is the system
// server's context, not an Activity, so there is no Activity/View leak. Suppression sits
// on the object because lint anchors StaticFieldLeak to the enclosing static holder.
@SuppressLint("StaticFieldLeak")
object SystemContextHelper {

    private const val TAG = "SystemContextHelper"

    /** Our own package. Used to make the wrapped context report the app's
     *  identity to the BYD SDK (see {@link #adoptIdentity}). */
    const val SELF_PKG = "com.byd.dashcast"

    /** Singleton cache — reflection is expensive, the system context never changes within a process. */
    @Volatile
    private var sCached: Context? = null

    @Volatile
    private var sLastError: Throwable? = null

    /**
     * Returns a {@link Context} representing the system server, wrapped so that
     * every permission check returns {@code PackageManager.PERMISSION_GRANTED}.
     *
     * @return non-null wrapped system context on success.
     * @throws Exception if {@code ActivityThread} or {@code getSystemContext}
     *                   reflection fails. The caller is responsible for
     *                   catching and falling back.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun get(): Context {
        val cached = sCached
        if (cached != null) return cached
        synchronized(SystemContextHelper::class.java) {
            sCached?.let { return it }
            // ActivityThread.systemMain() instantiates an internal Handler, which
            // requires a Looper on the current thread. Match the BydAgent
            // reference pattern (external_code/BydAgent.java) and prepare one if
            // the caller is a background thread without a Looper.
            if (Looper.myLooper() == null) {
                try { Looper.prepare() }
                catch (ignore: Throwable) { /* another thread or already prepared */ }
            }
            try {
                val at: Class<*> = Class.forName("android.app.ActivityThread")
                val m1: Method = at.getMethod("systemMain")
                val thread = m1.invoke(null)
                val m2: Method = at.getMethod("getSystemContext")
                val sys = m2.invoke(thread) as Context?
                if (sys == null) {
                    throw IllegalStateException("ActivityThread.getSystemContext() returned null")
                }
                val wrapped = adoptIdentity(sys)
                sCached = wrapped
                sLastError = null
                AppLogger.i(TAG, "system context acquired: pkg=" + sys.packageName
                        + " → wrapped identity=" + wrapped.packageName)
                return wrapped
            } catch (t: Throwable) {
                sLastError = t
                AppLogger.e(TAG, "failed to acquire system context", if (t is Exception) t else Exception(t))
                if (t is Exception) throw t
                throw Exception(t)
            }
        }
    }

    /**
     * Quick test: returns true if the system context can be obtained, without
     * caching on failure and without raising — used by the Diag tests.
     */
    @JvmStatic
    fun isAvailable(): Boolean {
        return try { get(); sCached != null }
        catch (t: Throwable) { false }
    }

    /** Last error encountered while trying to obtain the system context, or {@code null}. */
    @JvmStatic
    fun getLastError(): Throwable? = sLastError

    /**
     * Wrap the given Context so that every permission check returns GRANTED.
     * Used internally; exposed for tests that want to wrap an arbitrary base.
     */
    @JvmStatic
    fun wrap(base: Context): Context {
        return wrap(base, null)
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
    @JvmStatic
    fun wrap(base: Context, spoofPkg: String?): Context {
        return object : ContextWrapper(base) {
            override fun checkSelfPermission(p: String): Int = PackageManager.PERMISSION_GRANTED
            override fun checkPermission(p: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_GRANTED
            override fun checkCallingPermission(p: String): Int = PackageManager.PERMISSION_GRANTED
            override fun checkCallingOrSelfPermission(p: String): Int = PackageManager.PERMISSION_GRANTED
            override fun enforceCallingOrSelfPermission(p: String, m: String?) {}
            override fun enforceCallingPermission(p: String, m: String?) {}
            override fun enforcePermission(p: String, pid: Int, uid: Int, m: String?) {}
            override fun enforceUriPermission(u: Uri?, pid: Int, uid: Int, mod: Int, m: String?) {}
            override fun enforceUriPermission(u: Uri?, r: String?, w: String?, pid: Int, uid: Int, mod: Int, m: String?) {}
            // Report the app's package identity to any SDK code that keys off
            // getPackageName() (defensive: also covers the fallback path where
            // createPackageContext failed and base is the raw system context).
            override fun getPackageName(): String {
                return spoofPkg ?: super.getPackageName()
            }
            // Propagate the wrapper to derived contexts: the BYD SDK sometimes calls
            // context.getApplicationContext().checkXxx() rather than context.checkXxx() directly.
            // Without this override the bypass would not apply to that second-hop call.
            override fun getApplicationContext(): Context {
                val appCtx = super.getApplicationContext()
                return if (appCtx == null) this else SystemContextHelper.wrap(appCtx, spoofPkg)
            }
        }
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
    @JvmStatic
    fun adoptIdentity(systemContext: Context): Context {
        var base = systemContext
        try {
            base = systemContext.createPackageContext(
                    SELF_PKG,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            AppLogger.i(TAG, "adoptIdentity: createPackageContext(" + SELF_PKG + ") ok")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "adoptIdentity: createPackageContext(" + SELF_PKG
                    + ") failed, using raw system context — " + t)
        }
        return wrap(base, SELF_PKG)
    }

    /** Clear the cache — used by tests that want to re-run the reflection from scratch. */
    @JvmStatic
    fun clearCache() {
        synchronized(SystemContextHelper::class.java) {
            sCached = null
            sLastError = null
        }
    }
}
