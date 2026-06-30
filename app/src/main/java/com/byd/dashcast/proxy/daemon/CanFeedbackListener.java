package com.byd.dashcast.proxy.daemon;

import android.content.Context;
import android.hardware.IBYDAutoEvent;
import android.hardware.bydauto.setting.AbsBYDAutoSettingListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CanFeedbackListener — registers a BYD <em>setting</em> feedback listener INSIDE the daemon
 * (uid 2000, privileged context) to capture the PUSH feedback the SDK never exposes to a
 * synchronous {@code get()}.
 *
 * <p>Proven push-only (1.6.71): writing SET_HUD_MODE then reading it back via the daemon
 * returned 0 every time. The HUD/nav feedback is delivered through
 * {@code BYDAutoSettingDevice.registerListener(AbsBYDAutoSettingListener)} callbacks instead.
 * On-car structure discovery (1.6.72) confirmed {@code AbsBYDAutoSettingListener} has a no-arg
 * constructor and NO abstract methods, so subclassing is safe.
 *
 * <p>The compile stub ({@code app/libs/byd-auto-api-stubs.jar}) exposes
 * {@code onFeatureChanged(String,int)} and {@code onDataChanged(IBYDAutoEvent)} (it does NOT
 * carry the device's {@code onDataEventChanged(int,BYDAutoEventValue)} — that's overridden at
 * runtime only if needed). At runtime ART resolves {@code android.*} to the bootclasspath
 * class, so these override the real callbacks.
 *
 * <p>Everything is guarded: a failure here must NEVER crash the daemon, which also drives the
 * DL3/DL5 cluster projection. Registration is opt-in (only when the HUD diag calls it) and
 * idempotent.
 */
public final class CanFeedbackListener {

    private CanFeedbackListener() {}

    private static final int CAP = 1000;
    private static final List<String> sBuf = Collections.synchronizedList(new ArrayList<String>());
    private static volatile Object  sListener;     // our AbsBYDAutoSettingListener subclass (strong ref)
    private static volatile boolean sRegistered;
    private static volatile android.os.HandlerThread sThread;   // Looper thread for register + callbacks

    private static void record(String s) {
        synchronized (sBuf) {
            if (sBuf.size() < CAP) sBuf.add(s);
        }
    }

    /** Our concrete listener — records whatever the framework pushes. */
    private static final class SettingSink extends AbsBYDAutoSettingListener {
        @Override public void onFeatureChanged(String feature, int value) {
            record("feat " + feature + "=" + value);
        }
        @Override public void onDataChanged(IBYDAutoEvent event) {
            try {
                record("data type=" + event.getEventType()
                        + " dev=" + event.getDeviceType()
                        + " val=" + event.getValue());
            } catch (Throwable t) {
                record("data <err " + t.getClass().getSimpleName() + ">");
            }
        }
    }

    /**
     * Registers the setting listener once (idempotent). Uses reflection for getInstance /
     * registerListener (matching {@link CanWriteVerbs}) so we don't depend on the stub's exact
     * method signatures; only the listener subclass needs the compile stub.
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @return a short status string
     * @throws Throwable if registration fails (caller surfaces it; daemon stays alive)
     */
    public static synchronized String startSetting(final Context wrappedCtx) {
        if (sRegistered) return "already-registered";
        try {
            if (sThread == null) {
                sThread = new android.os.HandlerThread("can-feedback-listener");
                sThread.start();
            }
            // The BYD listener creates a Handler internally → it MUST be built/registered on a
            // thread that has a Looper (the daemon's binder thread has none → NPE). Do it on our
            // dedicated HandlerThread; callbacks are then delivered on that Looper too.
            final android.os.Handler h = new android.os.Handler(sThread.getLooper());
            final String[] result = { "no-result" };
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            h.post(new Runnable() {
                @Override public void run() {
                    try {
                        Class<?> cls = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice");
                        Object dev = cls.getMethod("getInstance", Context.class).invoke(null, wrappedCtx);
                        if (dev == null) { result[0] = "ERR getInstance() null"; return; }
                        SettingSink sink = new SettingSink();
                        cls.getMethod("registerListener", AbsBYDAutoSettingListener.class).invoke(dev, sink);
                        sListener = sink;
                        sRegistered = true;
                        result[0] = "registered (all setting features, looper thread)";
                    } catch (Throwable t) {
                        result[0] = "ERR " + describe(t);
                    } finally {
                        latch.countDown();
                    }
                }
            });
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) return "ERR register timeout";
            return result[0];
        } catch (Throwable t) {
            return "ERR(outer) " + describe(t);
        }
    }

    /** Unwraps InvocationTargetException and appends the top stack frames, so the cause is visible. */
    private static String describe(Throwable t) {
        Throwable r = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        StringBuilder sb = new StringBuilder(r.getClass().getName());
        if (r.getMessage() != null) sb.append(": ").append(r.getMessage());
        StackTraceElement[] st = r.getStackTrace();
        if (st != null && st.length > 0) sb.append(" @ ").append(st[0]);
        if (st != null && st.length > 1) sb.append(" <- ").append(st[1]);
        return sb.toString();
    }

    /** Returns and clears the captured push events (newline-separated), or "(no events)". */
    public static String drain() {
        synchronized (sBuf) {
            if (sBuf.isEmpty()) return "(no events)";
            StringBuilder sb = new StringBuilder();
            for (String s : sBuf) sb.append(s).append('\n');
            sBuf.clear();
            return sb.toString();
        }
    }
}
