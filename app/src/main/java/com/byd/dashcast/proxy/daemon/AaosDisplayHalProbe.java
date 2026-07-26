package com.byd.dashcast.proxy.daemon;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * AaosDisplayHalProbe — the definitive test of whether app windows can be drawn to the AAOS
 * instrument-cluster panel via the automotive display proxy HAL
 * ({@code android.frameworks.automotive.display@1.0/1.1::IAutomotiveDisplayProxyService}).
 *
 * <p>On DX_BYD_AUTO the cluster panel is NOT a scan-out of logical display 1 — it is rendered by
 * the AAOS cluster pipeline, and the ONLY way to draw arbitrary content to it is the HAL's
 * {@code IGraphicBufferProducer}. This probe checks, step by step, whether we can reach it:
 * <ol>
 *   <li>is the HIDL Java interface even present?</li>
 *   <li>{@code getService()} — can we obtain the HAL binder? (SELinux / hwservicemanager gate)</li>
 *   <li>{@code getHGraphicBufferProducer(displayId)} — can we get a buffer producer for the panel?</li>
 * </ol>
 * A valid producer ⇒ projection is technically feasible (drawing is then "just" Surface work).
 * Any failure (absent / null / denied) ⇒ definitively closed.
 *
 * <p>Pure reflection (no compile-time deps) so it runs both in-app and in the daemon (uid 2000)
 * for comparison. READ-ONLY: it never calls {@code showWindow} / posts buffers, so it cannot
 * disrupt the OEM cluster nav. Safe no-op on non-AAOS (the HIDL class is simply ABSENT).
 */
public final class AaosDisplayHalProbe {
    private AaosDisplayHalProbe() {}

    public static String probe() {
        StringBuilder sb = new StringBuilder();
        sb.append("uid=").append(android.os.Process.myUid())
          .append(" pid=").append(android.os.Process.myPid()).append('\n');
        for (String ver : new String[]{"V1_1", "V1_0"}) {
            String cn = "android.frameworks.automotive.display." + ver + ".IAutomotiveDisplayProxyService";
            sb.append("== ").append(cn).append(" ==\n");
            Class<?> c;
            try {
                c = Class.forName(cn);
            } catch (Throwable t) {
                sb.append("  class: ABSENT (").append(t.getClass().getSimpleName()).append(")\n");
                continue;
            }
            sb.append("  class: present\n");
            Object svc;
            try {
                svc = getService(c);
            } catch (Throwable t) {
                sb.append("  getService: FAILED ").append(root(t)).append('\n');
                continue;
            }
            if (svc == null) {
                sb.append("  getService: null (HAL not registered, or SELinux-denied)\n");
                continue;
            }
            sb.append("  getService: OK → ").append(svc.getClass().getName()).append('\n');
            Method gp = null;
            try { gp = c.getMethod("getHGraphicBufferProducer", long.class); } catch (Throwable ignore) {}
            if (gp == null) {
                sb.append("  getHGraphicBufferProducer(long): method ABSENT\n");
            } else {
                for (long did : new long[]{1L, 0L, 2L}) {
                    try {
                        Object prod = gp.invoke(svc, did);
                        sb.append("  getHGraphicBufferProducer(").append(did).append("): ")
                          .append(prod == null
                                  ? "null"
                                  : ("OK → " + prod.getClass().getName()
                                     + "  ⇒ a buffer producer to the panel EXISTS (projection feasible)"))
                          .append('\n');
                    } catch (Throwable t) {
                        sb.append("  getHGraphicBufferProducer(").append(did).append("): FAILED ")
                          .append(root(t)).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Tries the common HIDL {@code getService} overloads; lets the real invocation error propagate. */
    private static Object getService(Class<?> c) throws Throwable {
        Class<?>[][] sigs = {
                {}, {boolean.class}, {String.class}, {String.class, boolean.class}
        };
        for (Class<?>[] params : sigs) {
            Method m;
            try {
                m = c.getMethod("getService", params);
            } catch (NoSuchMethodException nsme) {
                continue;
            }
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = (params[i] == String.class) ? "default" : Boolean.TRUE;
            }
            return m.invoke(null, args);
        }
        throw new NoSuchMethodException("no getService overload on " + c.getName());
    }

    private static String root(Throwable t) {
        Throwable r = (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
        return r.getClass().getName() + ": " + r.getMessage();
    }
}
