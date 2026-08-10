package com.byd.dashcast.proxy.daemon;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

/**
 * FissionHostSvcVerbs — read-only probe of the native {@code FissionHostSvc} Binder service
 * (from {@code libfission_services.so}, DL3 only — never observed on DL4/DL5.0/DL5.1/AAOS).
 *
 * <p>Reverse-engineered from a real DL3 pull (disassembly of {@code BnFissionHostService},
 * confirmed by a second, independent binary: the OEM's own
 * {@code AutoContainerNative::getQtProjectionDispInfoNative} calls this exact service with this
 * exact transaction code to feed the Qt cluster renderer). Its {@code getAutoCarDisplay}
 * (transaction 101) is a pure getter — copies 5 in-memory fields (type, name, width, height, a
 * producer Binder) into the reply with no side effect — reading it can never disturb whatever
 * currently owns the registry.
 *
 * <p><b>What this does NOT do</b>: no interface descriptor for this native service has been
 * confirmed (unlike {@code AutoContainer}, a hidden Java AIDL interface whose descriptor can be
 * queried live via {@code INTERFACE_TRANSACTION} the same way {@link Phase4ProcessVerbs} already
 * does — that trick does not apply here because the RE evidence is ambiguous about whether this
 * hand-rolled C++ service even validates an interface token). The reply layout (int, String16,
 * int, int, StrongBinder) is a disassembly READING, not a spec — every call therefore returns the
 * raw reply bytes as hex FIRST, unconditionally, so a wrong decode never loses the evidence.
 */
final class FissionHostSvcVerbs {

    private FissionHostSvcVerbs() {}

    private static final String SVC_NAME = "FissionHostSvc";
    private static final int TXN_GET_AUTOCAR_DISPLAY = 101;

    private static volatile IBinder sBinder;

    private static final IBinder.DeathRecipient sDeath = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            sBinder = null;
        }
    };

    /** @return the live binder, or {@code null} if the service is not registered on this ROM. */
    private static IBinder resolve() {
        IBinder b = sBinder;
        if (b != null && b.isBinderAlive()) return b;
        synchronized (FissionHostSvcVerbs.class) {
            b = sBinder;
            if (b != null && b.isBinderAlive()) return b;
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                b = (IBinder) sm.getMethod("getService", String.class).invoke(null, SVC_NAME);
            } catch (Throwable ignore) {
                return null;
            }
            if (b == null) return null;
            try { b.linkToDeath(sDeath, 0); } catch (Throwable ignore) { /* still usable */ }
            sBinder = b;
            return b;
        }
    }

    /**
     * Reads the registry once. Never throws for "service absent" — that IS the diagnostic answer
     * on a non-DL3 car — only for a genuine transport failure.
     */
    static String getAutoCarDisplay() throws Throwable {
        IBinder b = resolve();
        if (b == null) {
            return "SERVICE NOT FOUND: '" + SVC_NAME + "' — not registered on this ROM"
                    + " (expected on DL4/DL5.0/DL5.1/AAOS; expected PRESENT on DL3)";
        }
        return sampleOnce(b);
    }

    /** One read + decode, shared by {@link #getAutoCarDisplay()} and the tracer. */
    private static String sampleOnce(IBinder b) throws Throwable {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            // No confirmed descriptor for this native service (see class doc) — the token is
            // written defensively, matching the RE author's own advice, and costs nothing since
            // there are no further args to misalign if the server does not consume it.
            data.writeInterfaceToken("android.IFissionHostService");
            boolean handled = b.transact(TXN_GET_AUTOCAR_DISPLAY, data, reply, 0);
            if (!handled) return "NOT HANDLED (transact returned false — wrong code or dead service)";
            byte[] raw = reply.marshall();
            reply.setDataPosition(0);
            StringBuilder out = new StringBuilder();
            out.append("raw(").append(raw.length).append("B)=").append(toHex(raw));
            // Best-effort decode, per the disassembly-read layout. Any failure here is reported
            // alongside the raw hex above, never in place of it.
            try {
                int type = reply.readInt();
                String name = reply.readString();
                int w = reply.readInt();
                int h = reply.readInt();
                IBinder producer = reply.readStrongBinder();
                out.append(" | decoded: type=").append(type)
                   .append(" name=").append(name == null ? "<null>" : "\"" + name + "\"")
                   .append(" w=").append(w).append(" h=").append(h)
                   .append(" producer=").append(producer == null ? "absent" : "present");
                if (type <= 0 && (name == null || name.isEmpty())) {
                    out.append(" [registry looks EMPTY — nothing currently registered]");
                }
            } catch (Throwable decodeFail) {
                out.append(" | decode FAILED (layout guess may be wrong): ")
                   .append(decodeFail.getClass().getSimpleName());
            }
            return out.toString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    // ─── Tracer: sample the registry across a normal projection cycle ─────

    private static final long TRACE_INTERVAL_MS = 2_000L;
    private static final long TRACE_HARD_CAP_MS = 90_000L;

    private static volatile Thread sTraceThread;
    private static final StringBuilder sTraceLog = new StringBuilder();
    private static final Object sTraceLock = new Object();

    /** Arms the sampler. Idempotent — a second call while one is already running is a no-op. */
    static void startTrace() {
        synchronized (sTraceLock) {
            if (sTraceThread != null && sTraceThread.isAlive()) return;
            sTraceLog.setLength(0);
            Thread t = new Thread(FissionHostSvcVerbs::runTrace, "fission-autocar-trace");
            t.setDaemon(true);
            sTraceThread = t;
            t.start();
        }
    }

    /** Stops the sampler (if still running) and returns everything recorded. */
    static String drainTrace() {
        Thread t = sTraceThread;
        if (t != null) t.interrupt();
        synchronized (sTraceLock) {
            String result = sTraceLog.length() == 0
                    ? "(no changes recorded)" : sTraceLog.toString();
            return result;
        }
    }

    /**
     * The {@link #TRACE_HARD_CAP_MS} check only runs between samples — {@code transact()} has no
     * per-call timeout, so a misbehaving or wrong service registered under this name could block
     * this thread past the cap, for the rest of the daemon's life. Accepted: this is a dedicated
     * background thread (not one of the daemon's own Binder-pool workers, so it cannot starve
     * client requests), the scenario requires a service actively misusing the "FissionHostSvc"
     * name, and {@code interrupt()} cannot unblock a thread parked in a native Binder call anyway.
     */
    private static void runTrace() {
        long startedAt = SystemClock.elapsedRealtime();
        IBinder b = resolve();
        if (b == null) {
            append("t=0s SERVICE NOT FOUND — aborting trace (not DL3, or SELinux blocks lookup)");
            return;
        }
        String last = null;
        while (SystemClock.elapsedRealtime() - startedAt < TRACE_HARD_CAP_MS) {
            if (Thread.currentThread().isInterrupted()) break;
            String sample;
            try {
                sample = sampleOnce(b);
            } catch (Throwable t) {
                sample = "PROBE ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            if (!sample.equals(last)) {
                long tSec = (SystemClock.elapsedRealtime() - startedAt) / 1000;
                append("t=" + tSec + "s " + sample);
                last = sample;
            }
            try { Thread.sleep(TRACE_INTERVAL_MS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
    }

    private static void append(String line) {
        synchronized (sTraceLock) {
            if (sTraceLog.length() > 0) sTraceLog.append('\n');
            sTraceLog.append(line);
        }
    }
}
