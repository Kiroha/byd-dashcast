package com.byd.dashcast.beta.proxy;

import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Phase4ProcessVerbs — process-management verbs that run inside the daemon
 * process (uid 2000).
 *
 * <p>Covers three concern groups:
 * <ol>
 *   <li><b>/proc scan</b> — pure-Java {@code pidof} replacement
 *       ({@link #getPidsByPackage}).</li>
 *   <li><b>AutoContainer</b> — typed Binder transactions to the BYD
 *       {@code AutoContainer} service ({@link #autoContainerSendInfo}).</li>
 *   <li><b>IActivityManager</b> — {@code forceStopPackage} via reflection.</li>
 * </ol>
 *
 * @see Phase4DisplayVerbs
 * @see Phase4TaskVerbs
 * @since v1.1.9 build 174 — split from Phase4Verbs in v1.4.4-beta.
 */
public final class Phase4ProcessVerbs {

    private Phase4ProcessVerbs() {}

    // ─── AutoContainer cache ──────────────────────────────────────────────

    private static final String AUTOCONTAINER_SVC = "AutoContainer";
    private static final int    TXN_SEND_INFO      = 2;

    private static volatile IBinder sAutoContainerBinder;
    private static volatile String  sAutoContainerDescriptor;

    private static final IBinder.DeathRecipient sAutoContainerDeath = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            synchronized (Phase4ProcessVerbs.class) {
                sAutoContainerBinder     = null;
                sAutoContainerDescriptor = null;
            }
        }
    };

    /**
     * Resolve (and cache) the live IBinder for the {@code AutoContainer} service.
     * Descriptor is read at runtime so OEM rebrands still work.
     * Cache is invalidated via a DeathRecipient when the host process dies.
     */
    private static IBinder autoContainerBinder() throws Throwable {
        IBinder b = sAutoContainerBinder;
        if (b != null && b.isBinderAlive()) return b;
        synchronized (Phase4ProcessVerbs.class) {
            b = sAutoContainerBinder;
            if (b != null && b.isBinderAlive()) return b;
            Class<?> sm = Class.forName("android.os.ServiceManager");
            b = (IBinder) sm.getMethod("getService", String.class).invoke(null, AUTOCONTAINER_SVC);
            if (b == null) throw new IllegalStateException("no '" + AUTOCONTAINER_SVC + "' service");
            String descr;
            Parcel d0 = Parcel.obtain();
            Parcel r0 = Parcel.obtain();
            try {
                b.transact(IBinder.INTERFACE_TRANSACTION, d0, r0, 0);
                descr = r0.readString();
            } finally {
                r0.recycle();
                d0.recycle();
            }
            if (descr == null || descr.isEmpty()) {
                throw new IllegalStateException(AUTOCONTAINER_SVC + " advertised empty descriptor");
            }
            try { b.linkToDeath(sAutoContainerDeath, 0); }
            catch (Throwable t) { return b; }
            sAutoContainerDescriptor = descr;
            sAutoContainerBinder = b;
            return b;
        }
    }

    // ─── IActivityManager cache ───────────────────────────────────────────

    private static volatile Object sActivityManager;
    private static volatile Method sForceStopPackage;

    private static Object activityManager() throws Throwable {
        Object am = sActivityManager;
        if (am != null) return am;
        synchronized (Phase4ProcessVerbs.class) {
            am = sActivityManager;
            if (am != null) return am;
            try {
                Class<?> c = Class.forName("android.app.ActivityManager");
                am = c.getMethod("getService").invoke(null);
            } catch (Throwable ignore) {
                Class<?> c = Class.forName("android.app.ActivityManagerNative");
                am = c.getMethod("getDefault").invoke(null);
            }
            if (am == null) throw new IllegalStateException("no IActivityManager");
            sActivityManager = am;
            return am;
        }
    }

    private static Method forceStopMethod(Object am) throws Throwable {
        Method m = sForceStopPackage;
        if (m != null) return m;
        synchronized (Phase4ProcessVerbs.class) {
            m = sForceStopPackage;
            if (m != null) return m;
            for (Method cand : am.getClass().getMethods()) {
                if (!"forceStopPackage".equals(cand.getName())) continue;
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length == 2 && pt[0] == String.class && pt[1] == int.class) {
                    m = cand; break;
                }
            }
            if (m == null) throw new NoSuchMethodException("no forceStopPackage(String,int)");
            sForceStopPackage = m;
            return m;
        }
    }

    // ─── /proc scan buffer ───────────────────────────────────────────────

    private static final ThreadLocal<byte[]> sCmdlineBuf = new ThreadLocal<byte[]>() {
        @Override protected byte[] initialValue() { return new byte[256]; }
    };

    // ─── Verbs ────────────────────────────────────────────────────────────

    /**
     * Equivalent of {@code pidof <packageName>} — pure-Java scan of
     * {@code /proc/<pid>/cmdline}.
     *
     * <p>Probe P8 (build 173): returns in &lt; 1 ms with 241 live processes on
     * BYD Seal EU. Replaces a {@code sh -c "pidof …"} fork (48–181 ms).
     *
     * @return space-separated PIDs, or {@code ""} if none match.
     */
    public static String getPidsByPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return "";
        byte[] buf = sCmdlineBuf.get();
        StringBuilder pids = null;
        for (File d : dirs) {
            String name = d.getName();
            if (!d.isDirectory()) continue;
            boolean numeric = true;
            for (int i = 0, n = name.length(); i < n; i++) {
                char c = name.charAt(i);
                if (c < '0' || c > '9') { numeric = false; break; }
            }
            if (!numeric) continue;
            File cmd = new File(d, "cmdline");
            if (!cmd.canRead()) continue;
            int read;
            try (FileInputStream fis = new FileInputStream(cmd)) {
                read = fis.read(buf);
            } catch (Throwable ignore) { continue; }
            if (read <= 0) continue;
            int end = 0;
            while (end < read && buf[end] != 0) end++;
            if (end == 0) continue;
            String argv0 = new String(buf, 0, end);
            if (argv0.equals(packageName)
                    || (argv0.length() > packageName.length()
                        && argv0.startsWith(packageName)
                        && argv0.charAt(packageName.length()) == ':')) {
                if (pids == null) pids = new StringBuilder(name.length());
                else pids.append(' ');
                pids.append(name);
            }
        }
        return pids == null ? "" : pids.toString();
    }

    /**
     * Equivalent of {@code service call AutoContainer 2 i32 type i32 info s16 str}.
     *
     * <p>Probe P13 (build 176): {@code transact(2, …)} accepted from uid 2000 on
     * BYD Seal EU with descriptor {@code android.os.IAutoContainer}.
     */
    public static void autoContainerSendInfo(int type, int info, String str) throws Throwable {
        IBinder b = autoContainerBinder();
        String descr = sAutoContainerDescriptor;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descr);
            data.writeInt(type);
            data.writeInt(info);
            data.writeString(str == null ? "" : str);
            b.transact(TXN_SEND_INFO, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Force-stop a package via IActivityManager reflection. */
    public static void forceStopPackage(String packageName, int userId) throws Throwable {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("packageName empty");
        }
        Object am = activityManager();
        Method m = forceStopMethod(am);
        m.invoke(am, packageName, userId);
    }
}
