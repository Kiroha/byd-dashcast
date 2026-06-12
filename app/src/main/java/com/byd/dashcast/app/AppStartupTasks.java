package com.byd.dashcast.app;

import android.content.Context;

import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.proxy.ShellGateway;

/**
 * One-shot background tasks executed once at process start in MainActivity.onCreate().
 *
 * All I/O is off-loaded to a single daemon thread so onCreate() is not blocked.
 * The {@code killOrphanSniffer} flag must be evaluated on the main thread before calling
 * {@link #run} to preserve the "run once per process" invariant across Activity re-creates.
 */
public final class AppStartupTasks {

    private static final String TAG = "AppStartupTasks";

    private AppStartupTasks() {}

    /**
     * @param ctx               application context
     * @param killOrphanSniffer true only for the first MainActivity instance in this process
     */
    public static void run(Context ctx, boolean killOrphanSniffer) {
        Thread t = new Thread(() -> {
            try {
                AppLogger.pruneOldFiles(ctx, 3);
            } catch (Throwable e) {
                AppLogger.w(TAG, "pruneOldFiles failed: " + e.getMessage());
            }
            if (killOrphanSniffer) {
                try {
                    // Orphan-sniffer cleanup: the tag file lives on tmpfs and disappears at boot,
                    // so a present tag without a running-pref entry means orphan processes from a
                    // previous force-stop of DiagActivity.
                    String savedPath = ctx
                            .getSharedPreferences("byd_diag_prefs", Context.MODE_PRIVATE)
                            .getString("re_sniffer_file_path", null);
                    if (savedPath == null) {
                        ShellGateway.execShell(ctx,
                                "if [ -f /data/local/tmp/.re_sniffer_run ]; then"
                              + "  rm -f /data/local/tmp/.re_sniffer_run;"
                              + "  if [ -f /data/local/tmp/.re_sniffer_pids ]; then"
                              + "    while IFS= read -r pid; do"
                              + "      [ -n \"$pid\" ] && kill -9 \"$pid\" 2>/dev/null;"
                              + "    done < /data/local/tmp/.re_sniffer_pids;"
                              + "    rm -f /data/local/tmp/.re_sniffer_pids;"
                              + "  fi;"
                              + "  pkill -f BYD_RE_Sniffer_ 2>/dev/null; true;"
                              + "fi");
                    }
                } catch (Throwable e) {
                    AppLogger.w(TAG, "orphan-sniffer cleanup failed: " + e.getMessage());
                }
            }
        }, "storage-hygiene");
        t.setDaemon(true);
        t.start();
    }
}
