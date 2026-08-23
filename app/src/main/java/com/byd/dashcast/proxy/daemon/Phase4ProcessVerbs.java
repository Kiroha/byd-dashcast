package com.byd.dashcast.proxy.daemon;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Phase4ProcessVerbs — process-management verbs that run inside the daemon
 * process (uid 2000).
 *
 * <p>Covers three concern groups:
 * <ol>
 *   <li><b>/proc scan</b> — pure-Java {@code pidof} replacement
 *       ({@link #getPidsByPackage}).</li>
 *   <li><b>AutoContainer</b> — typed Binder transactions to the BYD
 *       {@code AutoContainer} service ({@link #autoContainerSendInfo},
 *       {@link #autoContainerSendInfo2}, {@link #autoContainerRegisterCallback}).</li>
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

    private static final String AUTOCONTAINER_SVC       = "AutoContainer";
    private static final int    TXN_SEND_INFO           = 2;
    private static final int    TXN_SEND_INFO2          = 3;
    private static final int    TXN_REGISTER_CALLBACK   = 4;

    /**
     * The binder and the descriptor it advertised, together.
     *
     * They used to be two independent static fields, read at two different moments, and that cost
     * correctness twice. The `catch { return b; }` on linkToDeath below returned a usable binder
     * while never assigning the descriptor, so on a ROM where linkToDeath fails every subsequent
     * transaction wrote a NULL interface token. And even on the happy path, binderDied() could null
     * the descriptor between a caller obtaining the binder and reading the descriptor for it.
     * One immutable pair, resolved once, closes both.
     */
    private static final class Container {
        final IBinder binder;
        final String descriptor;
        Container(IBinder binder, String descriptor) { this.binder = binder; this.descriptor = descriptor; }
    }

    private static volatile Container sAutoContainer;

    private static final IBinder.DeathRecipient sAutoContainerDeath = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            synchronized (Phase4ProcessVerbs.class) {
                sAutoContainer = null;
            }
        }
    };

    /**
     * Resolve (and cache) the live IBinder for the {@code AutoContainer} service.
     * Descriptor is read at runtime so OEM rebrands still work.
     * Cache is invalidated via a DeathRecipient when the host process dies.
     */
    private static Container autoContainer() throws Throwable {
        Container c = sAutoContainer;
        if (c != null && c.binder.isBinderAlive()) return c;
        synchronized (Phase4ProcessVerbs.class) {
            c = sAutoContainer;
            if (c != null && c.binder.isBinderAlive()) return c;
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, AUTOCONTAINER_SVC);
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
            Container fresh = new Container(b, descr);
            // linkToDeath is best-effort: it throws when the binder died between getService and
            // here. The pair is still complete and usable, so hand it back — it is simply not
            // cached, and the isBinderAlive() check above re-resolves on the next call. What must
            // NOT happen, and used to, is returning a binder with no descriptor to go with it.
            try {
                b.linkToDeath(sAutoContainerDeath, 0);
            } catch (Throwable t) {
                return fresh;
            }
            sAutoContainer = fresh;
            return fresh;
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
        transactAutoContainerSendInfo(type, info, str, false);
    }

    /** Same transaction as {@link #autoContainerSendInfo}, preserving the native result code. */
    public static int autoContainerSendInfoResult(int type, int info, String str) throws Throwable {
        return transactAutoContainerSendInfo(type, info, str, true);
    }

    private static int transactAutoContainerSendInfo(int type, int info, String str,
                                                     boolean readResult) throws Throwable {
        Container c = autoContainer();
        IBinder b = c.binder;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(c.descriptor);
            data.writeInt(type);
            data.writeInt(info);
            data.writeString(str == null ? "" : str);
            if (!b.transact(TXN_SEND_INFO, data, reply, 0)) {
                throw new IllegalStateException("AutoContainer sendInfo transaction not handled");
            }
            reply.readException();
            if (!readResult) return 0;
            if (reply.dataAvail() < Integer.BYTES) {
                throw new IllegalStateException("AutoContainer sendInfo returned no result code");
            }
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Equivalent of {@code AutoContainer.sendInfo2(type, data)} (AIDL transaction 3) — same binder
     * the OEM's own nav app uses to push a serialized {@code NaviInfo} FlatBuffer (type=4) to the
     * HUD. Reuses the cached binder resolved by {@link #autoContainer()}.
     */
    public static void autoContainerSendInfo2(int type, byte[] data) throws Throwable {
        Container c = autoContainer();
        IBinder b = c.binder;
        Parcel dataParcel = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            dataParcel.writeInterfaceToken(c.descriptor);
            dataParcel.writeInt(type);
            dataParcel.writeByteArray(data);
            if (!b.transact(TXN_SEND_INFO2, dataParcel, reply, 0)) {
                throw new IllegalStateException("AutoContainer sendInfo2 transaction not handled");
            }
            reply.readException();
        } finally {
            reply.recycle();
            dataParcel.recycle();
        }
    }

    /**
     * Force-stop a package via IActivityManager reflection.
     *
     * <p>Retries once on a dead proxy. The AutoContainer cache a few lines up invalidates itself
     * through a DeathRecipient; this one never did, so after a system_server restart — which this
     * daemon outlives, being a separate shell-started process — the cached proxy stayed dead and
     * every force-stop failed silently until the daemon itself respawned. Force-stopping a package
     * is idempotent, so a single retry costs nothing when the first attempt genuinely landed.
     */
    public static void forceStopPackage(String packageName, int userId) throws Throwable {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("packageName empty");
        }
        try {
            invokeForceStop(packageName, userId);
        } catch (Throwable t) {
            if (!isDeadObject(t)) throw t;
            synchronized (Phase4ProcessVerbs.class) {
                sActivityManager = null;
                sForceStopPackage = null;   // the Method belongs to the dead proxy's class
            }
            invokeForceStop(packageName, userId);
        }
    }

    private static void invokeForceStop(String packageName, int userId) throws Throwable {
        Object am = activityManager();
        Method m = forceStopMethod(am);
        try {
            m.invoke(am, packageName, userId);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            // Unwrap so the caller — and isDeadObject above — sees the real failure, not the
            // reflection wrapper.
            throw ite.getCause() != null ? ite.getCause() : ite;
        }
    }

    /** True for a binder that died under us, at any depth of the cause chain. */
    private static boolean isDeadObject(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof android.os.DeadObjectException) return true;
            if (c.getClass().getName().endsWith("DeadSystemException")) return true;
            if (c == c.getCause()) break;
        }
        return false;
    }

    // ─── AutoContainer callback (diagnostic, never called before this release) ────────────

    /** Kept alive for the daemon process lifetime — a dropped local reference would not by
     *  itself unregister the far side, but there is no reason to rely on that; a static field
     *  costs nothing and removes the question. Re-armed on every daemon respawn (a fresh
     *  process has no memory of a prior registration — the OEM service does not persist it
     *  across our process death either, since it is keyed by this binder's own identity). */
    private static volatile ContainerCallbackBinder sRegisteredCallback;

    /** Returned by {@link #autoContainerRegisterCallback()} when the native reply carries no
     *  result int at all — distinct from any real result code (including 0, which is meaningful
     *  elsewhere in this file: {@link #autoContainerSendInfoResult} uses it for "accepted"). The
     *  reply shape for this specific call was never confirmed on-car; silently defaulting to 0
     *  would make "genuinely accepted" and "reply layout guess is wrong" indistinguishable. */
    public static final int REGISTER_CALLBACK_NO_RESULT_FIELD = Integer.MIN_VALUE;

    /**
     * Registers this daemon's own callback with {@code AutoContainer.registerCallback}
     * (AIDL transaction 4 — documented in {@code ClusterManager.kt} since the DL3 RE pass that
     * found it, never called until now). Every push the native service makes afterward
     * ({@code serviceDied}/{@code receivedJson/Info/Info2}) is logged into the daemon's own
     * transcript for as long as this process lives — diagnostic only, does not feed any
     * production code path.
     */
    public static int autoContainerRegisterCallback() throws Throwable {
        ContainerCallbackBinder cb = sRegisteredCallback;
        if (cb == null) {
            synchronized (Phase4ProcessVerbs.class) {
                cb = sRegisteredCallback;
                if (cb == null) {
                    cb = new ContainerCallbackBinder();
                    sRegisteredCallback = cb;
                }
            }
        }
        Container c = autoContainer();
        IBinder b = c.binder;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(c.descriptor);
            data.writeStrongBinder(cb);
            if (!b.transact(TXN_REGISTER_CALLBACK, data, reply, 0)) {
                throw new IllegalStateException("AutoContainer registerCallback transaction not handled");
            }
            reply.readException();
            return reply.dataAvail() >= Integer.BYTES
                    ? reply.readInt() : REGISTER_CALLBACK_NO_RESULT_FIELD;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Raw Binder (not the hidden {@code IContainerCallback$Stub}) receiving pushes from the OEM's
     * AIDL-generated proxy. Transaction codes below (1=serviceDied, 2=receivedJson,
     * 3=receivedInfo, 4=receivedInfo2) are inferred from the callback implementation's method
     * order in the OEM's own decompiled bytecode, NOT independently confirmed against the AIDL
     * compiler's actual numbering — every call therefore logs its raw code and a hex dump of the
     * untouched data first, so a wrong guess here can still be decoded later from a bug report.
     * Rate-limited: a dying/reconnecting native service could otherwise flood the one log section
     * that is captured in full (unlike logcat, which the project has already lost evidence to
     * once from an unrelated flood — see Phase 0 / INC-20260804-171617).
     */
    private static final class ContainerCallbackBinder extends Binder {
        private long mLastLogAt;
        private int mSuppressedSinceLog;
        private static final long MIN_LOG_INTERVAL_MS = 5_000L;

        @Override
        protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            // marshall() is ILLEGAL on a parcel carrying binder objects, and it sat OUTSIDE the
            // try below — so a push that happened to carry one threw out of onTransact, losing the
            // log line this callback exists for and answering the OEM with an exception instead of
            // writeNoException(). Exactly the defect AUD-225 fixed in FissionHostSvcVerbs; it was
            // corrected there and left here. Best-effort now, and the throw is itself evidence: it
            // can only happen when a Binder is present.
            String rawHex;
            try {
                byte[] raw = data.marshall();
                rawHex = "raw(" + raw.length + "B)=" + toHex(raw);
            } catch (Throwable marshallFail) {
                rawHex = "raw=<unavailable: " + marshallFail.getClass().getSimpleName()
                        + " — the push carries binder objects>";
            }
            data.setDataPosition(0);
            long now = System.currentTimeMillis();
            boolean logIt = (now - mLastLogAt) >= MIN_LOG_INTERVAL_MS;
            if (logIt) {
                if (mSuppressedSinceLog > 0) {
                    ProxyDaemonMain.log("AutoContainer callback: (" + mSuppressedSinceLog
                            + " earlier push(es) suppressed, min " + MIN_LOG_INTERVAL_MS + "ms apart)");
                }
                mLastLogAt = now;
                mSuppressedSinceLog = 0;
                StringBuilder sb = new StringBuilder("AutoContainer callback: code=").append(code)
                        .append(' ').append(rawHex);
                try {
                    // Skip whatever the caller wrote as an interface token — not validated against
                    // any expected descriptor, just consumed so the following best-effort reads
                    // line up. writeInterfaceToken()'s real wire format is TWO fields, not one: a
                    // leading int32 (IPCThreadState strict-mode policy word) THEN the String16
                    // descriptor — a lone readString() misreads that policy int as the string's
                    // length prefix and desyncs every field that follows (caught by review).
                    data.readInt();
                    data.readString();
                    switch (code) {
                        case 1: sb.append(" [serviceDied()]"); break;
                        case 2: sb.append(" [receivedJson type=").append(data.readInt())
                                  .append(" json=").append(data.readString()).append(']'); break;
                        case 3: sb.append(" [receivedInfo type=").append(data.readInt())
                                  .append(" infoInt=").append(data.readInt())
                                  .append(" infoStr=").append(data.readString()).append(']'); break;
                        case 4: {
                            int t = data.readInt();
                            byte[] payload = data.createByteArray();
                            sb.append(" [receivedInfo2 type=").append(t)
                              .append(" dataLen=").append(payload == null ? -1 : payload.length)
                              .append(']');
                            break;
                        }
                        default: sb.append(" [unknown code — see raw hex above]");
                    }
                } catch (Throwable decodeFail) {
                    sb.append(" (decode failed: ").append(decodeFail.getClass().getSimpleName()).append(')');
                }
                ProxyDaemonMain.log(sb.toString());
            } else {
                mSuppressedSinceLog++;
            }
            if ((flags & IBinder.FLAG_ONEWAY) == 0 && reply != null) {
                reply.writeNoException();
            }
            return true;
        }

        private static String toHex(byte[] b) {
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        }
    }
}
