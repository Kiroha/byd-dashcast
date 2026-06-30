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
    public static synchronized String startSetting(Context wrappedCtx) throws Throwable {
        if (sRegistered) return "already-registered";
        Class<?> cls = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice");
        Object dev = cls.getMethod("getInstance", Context.class).invoke(null, wrappedCtx);
        if (dev == null) throw new IllegalStateException("BYDAutoSettingDevice.getInstance() returned null");
        SettingSink sink = new SettingSink();
        cls.getMethod("registerListener", AbsBYDAutoSettingListener.class).invoke(dev, sink);
        sListener = sink;
        sRegistered = true;
        return "registered (all setting features)";
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
