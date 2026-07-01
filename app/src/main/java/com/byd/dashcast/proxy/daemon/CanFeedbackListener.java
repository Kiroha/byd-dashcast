package com.byd.dashcast.proxy.daemon;

import android.content.Context;
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
    /** HUD/nav setting feature ids to subscribe to via registerListener(listener, int[]). */
    private static final int[] SUBSCRIBE_IDS = {
        CanWriteVerbs.SET_HUD_MODE, CanWriteVerbs.SET_HUD_MODE_FEEDBACK,
        CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.SET_HUD_SWITCH_STATUS_FEEDBACK,
        CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS
    };
    private static final List<String> sBuf = Collections.synchronizedList(new ArrayList<String>());
    private static volatile Object  sListener;     // our AbsBYDAutoSettingListener subclass (strong ref)
    private static volatile boolean sRegistered;
    private static volatile android.os.HandlerThread sThread;   // Looper thread for register + callbacks
    /** Last value pushed per feature id — persistent (survives drain). onDataEventChanged fires on
     *  CHANGE only, and the OEM nav sets the HUD mode once at nav-start; keeping the last value lets
     *  us report the CURRENT HUD mode even if it was pushed before the read window. */
    private static final java.util.Map<Integer, Integer> sLastValue =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void record(String s) {
        synchronized (sBuf) {
            if (sBuf.size() < CAP) sBuf.add(s);
        }
    }

    /** Records an int-feature push: updates the persistent last-value map + the event log. */
    private static void recordValue(int featureId, int value) {
        sLastValue.put(featureId, value);
        record(String.format(java.util.Locale.US, "evt 0x%08X=%d", featureId, value));
    }

    /**
     * Our concrete listener — records whatever the framework pushes via the generic
     * {@code onFeatureChanged(String,int)} callback. We do NOT override
     * {@code onDataChanged(IBYDAutoEvent)} — it is {@code final} on the real device
     * (the internal dispatcher), and overriding it throws a {@link LinkageError} at class
     * load (proven 1.6.80). The overridable surface is the {@code onXxxChanged} family.
     */
    private static final class SettingSink extends AbsBYDAutoSettingListener {
        @Override public void onFeatureChanged(String feature, int value) {
            record("feat " + feature + "=" + value);
        }
    }

    /**
     * Adds {@code onDataEventChanged(int, BYDAutoEventValue)} — the most likely per-feature
     * delivery callback (1.6.83's onFeatureChanged captured nothing even when subscribed to the
     * ids). It is NOT in the stub, so it is declared WITHOUT {@code @Override} — at runtime it
     * overrides the real method by name+descriptor. It MIGHT be {@code final} on-device (like
     * onDataChanged); if so this class fails to load with a {@link LinkageError}, which
     * {@link #startSetting} catches to fall back to {@link SettingSink} — so capture is never
     * disabled entirely.
     */
    private static final class EventSink extends AbsBYDAutoSettingListener {
        @Override public void onFeatureChanged(String feature, int value) {
            record("feat " + feature + "=" + value);
        }
        public void onDataEventChanged(int featureId, android.hardware.bydauto.BYDAutoEventValue value) {
            recordValue(featureId, (value != null) ? value.intValue : Integer.MIN_VALUE);
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
                        // Try the EventSink (adds onDataEventChanged) — its class load throws a
                        // LinkageError if onDataEventChanged is final on-device; fall back to the
                        // onFeatureChanged-only sink so capture is never disabled entirely.
                        AbsBYDAutoSettingListener sink;
                        String sinkKind;
                        try {
                            sink = new EventSink();
                            sinkKind = "EventSink";
                        } catch (Throwable le) {
                            sink = new SettingSink();
                            sinkKind = "SettingSink(EventSink load failed: " + le.getClass().getSimpleName() + ")";
                        }
                        // Prefer registerListener(listener, int[]): many BYD builds deliver NO
                        // callbacks unless you subscribe to specific feature IDs (1.6.81 registered
                        // via the no-arg variant but captured nothing). Subscribe to the HUD/nav
                        // feedback ids; fall back to the all-features variant if that overload is absent.
                        String how;
                        try {
                            cls.getMethod("registerListener", AbsBYDAutoSettingListener.class, int[].class)
                               .invoke(dev, sink, SUBSCRIBE_IDS);
                            how = "filtered ids";
                        } catch (NoSuchMethodException nsme) {
                            cls.getMethod("registerListener", AbsBYDAutoSettingListener.class).invoke(dev, sink);
                            how = "all features";
                        }
                        sListener = sink;
                        sRegistered = true;
                        result[0] = "registered (" + how + ", " + sinkKind + ", looper thread)";
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

    /**
     * Returns the new events since the last drain (cleared) PLUS the persistent per-feature
     * last-known snapshot (NOT cleared) — so a stable value pushed before this window (e.g. the
     * OEM nav's HUD mode set at nav-start) is still reported.
     */
    public static String drain() {
        StringBuilder sb = new StringBuilder();
        synchronized (sBuf) {
            for (String s : sBuf) sb.append(s).append('\n');
            sBuf.clear();
        }
        if (!sLastValue.isEmpty()) {
            sb.append("[last-known]");
            for (java.util.Map.Entry<Integer, Integer> e : sLastValue.entrySet()) {
                sb.append(String.format(java.util.Locale.US, " 0x%08X=%d", e.getKey(), e.getValue()));
            }
            sb.append('\n');
        }
        return sb.length() == 0 ? "(no events)" : sb.toString();
    }
}
