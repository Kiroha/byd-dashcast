package com.byd.dashcast.infrastructure.task

import android.content.Context
import android.graphics.Rect

import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.util.AppLogger

import java.util.Locale

/**
 * Shell-based task resizer. Two strategies per ROM:
 *
 *  - **DL5 (API 32)** — `cmd activity task resize`: skips `am task resize`
 *    which returns exit=0 with zero visible effect on DL5's XDJA VirtualDisplay.
 *  - **DL2/3/4** — `am task resize` first; if it doesn't look successful,
 *    falls back to `cmd activity task resize`.
 *
 * Dispatches via [ShellGateway] (AdbLocalClient-backed async fire-and-forget).
 * The optional verification probe for DL5 is kept to preserve the existing field-logging
 * behaviour that has been vital for debugging cluster resize on DiLink 5.0 ROMs.
 */
class ShellTaskResizer(context: Context) : TaskResizer {

    private val mContext: Context = context.applicationContext

    @Throws(TaskResizer.ResizeException::class)
    override fun resize(taskId: Int, packageName: String, bounds: Rect) {
        val coords = "$taskId ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"
        val amCmd = "am task resize $coords 2>&1; echo \"exit=\$?\""
        val cmdAct = "cmd activity task resize $coords 2>&1; echo \"exit=\$?\""

        if (Platform.get().isDiLink5(mContext)) {
            dispatchDl5(taskId, packageName, bounds, cmdAct)
        } else {
            dispatchLegacy(taskId, packageName, bounds, amCmd, cmdAct)
        }
        // Shell resizers are fire-and-forget; we never throw ResizeException
        // because the result arrives asynchronously and logging covers failure.
    }

    private fun dispatchDl5(taskId: Int, packageName: String, bounds: Rect, cmdAct: String) {
        AppLogger.i(TAG, "DL5 cmd activity task resize taskId=$taskId pkg=$packageName bounds=$bounds")
        ShellGateway.execShellWithResult(mContext, cmdAct, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                AppLogger.i(TAG, "cmd activity task resize → " + (out?.trim() ?: ""))
                // DL5 verification probe: confirm windowing mode and bounds were applied.
                val verify = "dumpsys activity activities 2>/dev/null" +
                    " | awk '/Task=Task\\{[^}]*#" + taskId + "[ }]/," +
                    "/Task=Task\\{[^}]*#[0-9]+[ }]/'" +
                    " | grep -E 'mBounds|WindowingMode|displayId|resizeMode|#" + taskId + " '" +
                    " | head -25"
                ShellGateway.execShellWithResult(mContext, verify, object : AdbLocalClient.Callback {
                    override fun onSuccess(out: String?) {
                        AppLogger.i(TAG, "VERIFY taskId=$taskId pkg=$packageName →\n" + (out?.trim() ?: ""))
                    }

                    override fun onError(err: String?) {
                        AppLogger.w(TAG, "VERIFY error: $err")
                    }
                })
            }

            override fun onError(err: String?) {
                AppLogger.w(TAG, "DL5 cmd activity task resize error: $err (taskId=$taskId pkg=$packageName)")
            }
        })
    }

    private fun dispatchLegacy(
        taskId: Int, packageName: String, bounds: Rect,
        amCmd: String, cmdAct: String
    ) {
        ShellGateway.execShellWithResult(mContext, amCmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                val trimmed = out?.trim() ?: ""
                val looksOk = trimmed.contains("exit=0") &&
                    !trimmed.lowercase(Locale.ROOT).contains("unknown command") &&
                    !trimmed.lowercase(Locale.ROOT).contains("error") &&
                    !trimmed.lowercase(Locale.ROOT).contains("exception")
                AppLogger.i(TAG, "am task resize → \"$trimmed\" (looksOk=$looksOk)")
                if (looksOk) return
                AppLogger.i(TAG, "am task resize looksOk=false — falling back to cmd activity")
                ShellGateway.execShellWithResult(mContext, cmdAct, object : AdbLocalClient.Callback {
                    override fun onSuccess(out: String?) {
                        AppLogger.i(TAG, "cmd activity task resize → " + (out?.trim() ?: ""))
                    }

                    override fun onError(err: String?) {
                        AppLogger.w(TAG, "cmd activity task resize error: $err")
                    }
                })
            }

            override fun onError(err: String?) {
                AppLogger.w(TAG, "am task resize AdbLocal error: $err (taskId=$taskId pkg=$packageName)")
            }
        })
    }

    companion object {
        private const val TAG = "ShellTaskResizer"
    }
}
