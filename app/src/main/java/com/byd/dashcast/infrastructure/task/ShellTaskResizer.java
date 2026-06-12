package com.byd.dashcast.infrastructure.task;
import java.util.Locale;

import android.content.Context;
import android.graphics.Rect;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.platform.Platform;
import com.byd.dashcast.proxy.ShellGateway;

/**
 * Shell-based task resizer. Two strategies per ROM:
 *
 * <ul>
 *   <li><b>DL5 (API 32)</b> — {@code cmd activity task resize}: skips {@code am task resize}
 *       which returns exit=0 with zero visible effect on DL5's XDJA VirtualDisplay.
 *   <li><b>DL2/3/4</b> — {@code am task resize} first; if it doesn't look successful,
 *       falls back to {@code cmd activity task resize}.
 * </ul>
 *
 * Dispatches via {@link ShellGateway} (AdbLocalClient-backed async fire-and-forget).
 * The optional verification probe for DL5 is kept to preserve the existing field-logging
 * behaviour that has been vital for debugging cluster resize on DiLink 5.0 ROMs.
 */
public final class ShellTaskResizer implements TaskResizer {

    private static final String TAG = "ShellTaskResizer";

    private final Context mContext;

    public ShellTaskResizer(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public void resize(int taskId, String packageName, Rect bounds) throws ResizeException {
        String coords = taskId + " " + bounds.left + " " + bounds.top
                + " " + bounds.right + " " + bounds.bottom;
        String amCmd  = "am task resize " + coords + " 2>&1; echo \"exit=$?\"";
        String cmdAct = "cmd activity task resize " + coords + " 2>&1; echo \"exit=$?\"";

        if (Platform.get().isDiLink5(mContext)) {
            dispatchDl5(taskId, packageName, bounds, cmdAct);
        } else {
            dispatchLegacy(taskId, packageName, bounds, amCmd, cmdAct);
        }
        // Shell resizers are fire-and-forget; we never throw ResizeException
        // because the result arrives asynchronously and logging covers failure.
    }

    private void dispatchDl5(int taskId, String packageName, Rect bounds, String cmdAct) {
        AppLogger.i(TAG, "DL5 cmd activity task resize taskId=" + taskId
                + " pkg=" + packageName + " bounds=" + bounds);
        ShellGateway.execShellWithResult(mContext, cmdAct, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                AppLogger.i(TAG, "cmd activity task resize → " + (out == null ? "" : out.trim()));
                // DL5 verification probe: confirm windowing mode and bounds were applied.
                String verify = "dumpsys activity activities 2>/dev/null"
                        + " | awk '/Task=Task\\{[^}]*#" + taskId + "[ }]/,"
                        +       "/Task=Task\\{[^}]*#[0-9]+[ }]/'"
                        + " | grep -E 'mBounds|WindowingMode|displayId|resizeMode|#" + taskId + " '"
                        + " | head -25";
                ShellGateway.execShellWithResult(mContext, verify, new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String dump) {
                        AppLogger.i(TAG, "VERIFY taskId=" + taskId + " pkg=" + packageName
                                + " →\n" + (dump == null ? "" : dump.trim()));
                    }
                    @Override public void onError(String e) {
                        AppLogger.w(TAG, "VERIFY error: " + e);
                    }
                });
            }
            @Override public void onError(String err) {
                AppLogger.w(TAG, "DL5 cmd activity task resize error: " + err
                        + " (taskId=" + taskId + " pkg=" + packageName + ")");
            }
        });
    }

    private void dispatchLegacy(int taskId, String packageName, Rect bounds,
                                String amCmd, String cmdAct) {
        ShellGateway.execShellWithResult(mContext, amCmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                String trimmed = (out == null ? "" : out.trim());
                boolean looksOk = trimmed.contains("exit=0")
                        && !trimmed.toLowerCase(Locale.ROOT).contains("unknown command")
                        && !trimmed.toLowerCase(Locale.ROOT).contains("error")
                        && !trimmed.toLowerCase(Locale.ROOT).contains("exception");
                AppLogger.i(TAG, "am task resize → \"" + trimmed + "\" (looksOk=" + looksOk + ")");
                if (looksOk) return;
                AppLogger.i(TAG, "am task resize looksOk=false — falling back to cmd activity");
                ShellGateway.execShellWithResult(mContext, cmdAct, new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String out2) {
                        AppLogger.i(TAG, "cmd activity task resize → "
                                + (out2 == null ? "" : out2.trim()));
                    }
                    @Override public void onError(String err2) {
                        AppLogger.w(TAG, "cmd activity task resize error: " + err2);
                    }
                });
            }
            @Override public void onError(String err) {
                AppLogger.w(TAG, "am task resize AdbLocal error: " + err
                        + " (taskId=" + taskId + " pkg=" + packageName + ")");
            }
        });
    }
}
