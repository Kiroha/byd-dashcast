package com.byd.dashcast.proxy.daemon;

import android.content.Context;
import android.hardware.bydauto.setting.AbsBYDAutoSettingListener;

import java.util.ArrayDeque;

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
    /**
     * The event log, newest-wins.
     *
     * <p>It used to be an {@code ArrayList} filled by {@code if (size < CAP) add(line)} — which is
     * the opposite of a ring: once full it kept the OLDEST thousand events and silently ignored
     * everything after. INC-20260826-194829 is what that produced: exactly 1000 lines covering
     * t=0.1s to t=35.9s of a six-minute session, with no banner saying the recording had stopped.
     * The report's own {@code [last-known]} snapshot, which is not capped, named six feature ids
     * that appear nowhere in those 1000 lines — proof the pushes had kept coming.
     *
     * <p>A recording that goes deaf is survivable. One that goes deaf while looking complete is
     * not, so {@link #drain} now says how many events it dropped.
     */
    private static final ArrayDeque<String> sBuf = new ArrayDeque<>(CAP + 1);
    /** Events discarded to keep {@link #sBuf} at {@link #CAP}. Reset by {@link #drain}/{@link #clear}. */
    private static int sDropped;
    private static volatile Object  sListener;     // our AbsBYDAutoSettingListener subclass (strong ref)
    private static volatile Object  sInstrListener;// our AbsBYDAutoInstrumentListener subclass (strong ref)
    private static volatile boolean sRegistered;
    /** True while a registration runnable is posted but not yet completed. Prevents a retry
     *  during the 5s await-timeout window from posting a second runnable that would register
     *  a duplicate device listener and leak the first sink. Cleared on success AND failure. */
    private static volatile boolean sRegisterInFlight;
    private static volatile android.os.HandlerThread sThread;   // Looper thread for register + callbacks
    /** Last value pushed per feature id — persistent (survives drain). onDataEventChanged fires on
     *  CHANGE only, and the OEM nav sets the HUD mode once at nav-start; keeping the last value lets
     *  us report the CURRENT HUD mode even if it was pushed before the read window. */
    private static final java.util.Map<Integer, Integer> sLastValue =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Session start (elapsedRealtime ms) — every log line is timestamped relative to this, so
     *  user-tapped ground-truth markers ({@link #mark}) can be correlated with CAN events. */
    private static volatile long sT0 = android.os.SystemClock.elapsedRealtime();

    private static void record(String s) {
        String line = String.format(java.util.Locale.US, "[t=%6.1f] %s",
                (android.os.SystemClock.elapsedRealtime() - sT0) / 1000.0, s);
        synchronized (sBuf) {
            sBuf.addLast(line);
            while (sBuf.size() > CAP) { sBuf.pollFirst(); sDropped++; }
        }
    }

    /** Records a user-tapped ground-truth marker (the maneuver shown on the HUD right now). */
    public static void mark(String label) {
        record("TAP " + label);
    }

    /** Clears the event log + last-known maps and resets the timestamp clock (fresh recording). */
    public static void clear() {
        synchronized (sBuf) { sBuf.clear(); sDropped = 0; }
        sLastValue.clear();
        sLastBuf.clear();
        sT0 = android.os.SystemClock.elapsedRealtime();
    }

    /** Last buffer (hex) seen per feature id — nav guidance icon/distance/road is likely a buffer. */
    private static final java.util.Map<Integer, String> sLastBuf =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Handles a push (int value + optional byte buffer). Logs ONLY on a CHANGE (dedups the noisy
     * 0x99000198/0x12D heartbeats). The rich nav guidance (turn direction, distance, next road) is
     * often carried in the byte BUFFER, not intValue (which is 0/1 for many features) — so log the
     * buffer hex too when present + changed.
     */
    private static void onEvent(int featureId, android.hardware.bydauto.BYDAutoEventValue value) {
        int v = (value != null) ? value.intValue : Integer.MIN_VALUE;
        byte[] buf = null;
        try { buf = (value != null) ? value.bufferDataValue : null; } catch (Throwable ignore) {}
        boolean intChanged;
        { Integer prev = sLastValue.put(featureId, v); intChanged = (prev == null || prev.intValue() != v); }
        if (buf != null && buf.length > 0) {
            String hex = toHex(buf);
            String pb = sLastBuf.put(featureId, hex);
            if (!hex.equals(pb) || intChanged) {
                record(String.format(java.util.Locale.US, "evt 0x%08X=%d buf=%s", featureId, v, hex));
                return;
            }
            return;
        }
        if (intChanged) record(String.format(java.util.Locale.US, "evt 0x%08X=%d", featureId, v));
    }

    private static String toHex(byte[] b) {
        int n = Math.min(b.length, 48);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format(java.util.Locale.US, "%02x", b[i] & 0xff));
        if (b.length > n) sb.append("..").append(b.length);
        return sb.toString();
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
            onEvent(featureId, value);
        }
    }

    /**
     * Same, but for the INSTRUMENT device — nav GUIDANCE (turn arrows, distances, next-road) is
     * pushed here, not on the setting device. Registered so a capture WHILE DRIVING a route with
     * turns reveals which instrument feature ids fire for each maneuver → the protocol to feed nav
     * to the HUD ourselves. onDataEventChanged declared without @Override (runtime override).
     */
    private static final class InstrumentSink
            extends android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener {
        public void onDataEventChanged(int featureId, android.hardware.bydauto.BYDAutoEventValue value) {
            onEvent(featureId, value);
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
        // A previous attempt's runnable may still be in flight after its caller's 5s await
        // timed out — don't post a second one (it would register a duplicate device listener).
        if (sRegisterInFlight) return "register-in-flight";
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
            sRegisterInFlight = true;
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
                        // Prefer registerListener(listener) = ALL setting features: the filtered
                        // variant (our 5 HUD/nav ids) captured our own SET_HUD_MODE writes + the switch
                        // toggle, but NOTHING when a tester changes the HUD display mode in the car's
                        // HUD settings — because that user setting almost certainly uses a DIFFERENT
                        // feature id we weren't subscribed to. Subscribe to everything (EventSink's
                        // onDataEventChanged gives featureId+value) so we discover which id fires.
                        String how;
                        try {
                            cls.getMethod("registerListener", AbsBYDAutoSettingListener.class).invoke(dev, sink);
                            how = "all features";
                        } catch (NoSuchMethodException nsme) {
                            cls.getMethod("registerListener", AbsBYDAutoSettingListener.class, int[].class)
                               .invoke(dev, sink, SUBSCRIBE_IDS);
                            how = "filtered ids";
                        }
                        sListener = sink;
                        sRegistered = true;
                        String instr = registerInstrument(wrappedCtx);
                        result[0] = "registered (" + how + ", " + sinkKind + ", looper thread) + instrument[" + instr + "]";
                    } catch (Throwable t) {
                        result[0] = "ERR " + describe(t);
                    } finally {
                        // Clear on BOTH success and failure: a genuinely-failed register can be
                        // retried, but a still-pending one blocks a duplicate post above. On
                        // success sRegistered=true is already set, so retries short-circuit there.
                        sRegisterInFlight = false;
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

    /**
     * Registers the INSTRUMENT-device listener (all features) so nav guidance events are captured
     * too. Runs on the shared Looper thread's caller (already on it). Fully guarded — a failure here
     * must not disable the setting listener. Returns a short status string.
     */
    private static String registerInstrument(Context wrappedCtx) {
        if (sInstrListener != null) return "already";
        try {
            Class<?> icls = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Object idev = icls.getMethod("getInstance", Context.class).invoke(null, wrappedCtx);
            if (idev == null) return "getInstance null";
            InstrumentSink isink;
            try {
                isink = new InstrumentSink();
            } catch (Throwable le) {
                return "sink load failed: " + le.getClass().getSimpleName();
            }
            Class<?> lcls = android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener.class;
            try {
                icls.getMethod("registerListener", lcls).invoke(idev, isink);
            } catch (NoSuchMethodException nsme) {
                icls.getMethod("registerListener", lcls, int[].class).invoke(idev, isink, SUBSCRIBE_IDS);
            }
            sInstrListener = isink;
            return "all features";
        } catch (Throwable t) {
            return "ERR " + describe(t);
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
            // First line, not a footnote: a reader who does not know the window closed will date
            // the whole session from the last timestamp they see.
            if (sDropped > 0) {
                sb.append("[capped — ").append(sDropped)
                  .append(" event(s) dropped, the ").append(sBuf.size())
                  .append(" most recent kept]\n");
            }
            for (String s : sBuf) sb.append(s).append('\n');
            sBuf.clear();
            sDropped = 0;
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
