package com.byd.dashcast.ui.main;

import android.content.Context;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.util.concurrent.GenerationGate;
import com.byd.dashcast.cluster.ClusterService;
import com.byd.dashcast.R;

/**
 * Manages split-screen state on the cluster display.
 *
 * <p>Owns: {@code mCurrentSplitSlot}, {@code mSecondDashboardPkg/App}.
 * Extracted from MainActivity to keep split bookkeeping out of the god-class.
 *
 * <p>MainActivity delegates {@link #showSplitMenu}, {@link #applySplitSlot},
 * and {@link #clearSplitState} to this controller. The second-app insertion logic
 * inside {@code onSendToDashboard} is handled inline by MainActivity (requires
 * updating {@code mLastLaunchTime} for the state-poll grace period).
 */
public final class SplitController {

    private static final String TAG = "SplitController";

    public interface Host {
        Context getContext();
        ClusterService getClusterServiceIfBound();
        String getCurrentDashboardPkg();
        String getCurrentDashboardApp();
        void setCurrentDashboardPkg(String pkg);
        void setCurrentDashboardApp(String app);
        /** Called after split state changes so the UI (label + split button) can be refreshed. */
        void onSplitStateChanged();
        void runOnUiThread(Runnable r);
    }

    private final Host mHost;

    private int    mCurrentSplitSlot   = 0;   // 0=full, 1=left, 2=right
    private String mSecondDashboardPkg = null;
    private String mSecondDashboardApp = null;
    private final GenerationGate mReplacementGate = new GenerationGate();

    public SplitController(Host host) {
        mHost = host;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public int    getCurrentSplitSlot()   { return mCurrentSplitSlot; }
    public String getSecondDashboardPkg() { return mSecondDashboardPkg; }
    public String getSecondDashboardApp() { return mSecondDashboardApp; }
    public boolean isInSplitMode()        { return mCurrentSplitSlot != 0; }

    // ── Setters (for use by MainActivity when the host updates external state) ─

    public void setSecondDashboardPkg(String pkg) { mSecondDashboardPkg = pkg; }
    public void setSecondDashboardApp(String app) { mSecondDashboardApp = app; }

    /** Starts a user replacement intent and invalidates every older stop/launch completion. */
    public int beginSecondDashboardReplacement() {
        mReplacementGate.invalidate();
        return mReplacementGate.capture();
    }

    public boolean isCurrentSecondDashboardReplacement(int generation) {
        return mReplacementGate.isCurrent(generation);
    }

    /** Clears a verified-stopped occupant only for the latest replacement intent. */
    public boolean clearSecondDashboardIfMatches(String expectedPkg, int generation) {
        if (!mReplacementGate.isCurrent(generation)
                || expectedPkg == null || !expectedPkg.equals(mSecondDashboardPkg)) return false;
        mSecondDashboardApp = null;
        mSecondDashboardPkg = null;
        mHost.onSplitStateChanged();
        return true;
    }

    /** Atomically commits full-screen state after the secondary occupant was verified stopped. */
    public boolean commitFullScreenIfMatches(String expectedPkg, int generation) {
        if (!mReplacementGate.isCurrent(generation)
                || expectedPkg == null || !expectedPkg.equals(mSecondDashboardPkg)) return false;
        mSecondDashboardApp = null;
        mSecondDashboardPkg = null;
        mCurrentSplitSlot = 0;
        mHost.onSplitStateChanged();
        return true;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Shows a popup to choose full-screen / left-50% / right-50% for the current cluster app. */
    public void showSplitMenu(View anchor) {
        ClusterService svc = mHost.getClusterServiceIfBound();
        String curPkg = mHost.getCurrentDashboardPkg();
        if (svc == null || curPkg == null) {
            AppLogger.w(TAG, "showSplitMenu ignored — svc=" + svc + " pkg=" + curPkg);
            Toast.makeText(mHost.getContext(),
                    mHost.getContext().getString(R.string.toast_no_app_cluster),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        AppLogger.d(TAG, "showSplitMenu — app=" + curPkg
                + " slot=" + mCurrentSplitSlot + " second=" + mSecondDashboardPkg);
        int[] dims = getClusterDimensions(svc);
        final int W = dims[0], H = dims[1];
        PopupMenu popup = new PopupMenu(mHost.getContext(), anchor);
        popup.getMenu().add(0, 1, 0, mHost.getContext().getString(R.string.split_full_screen));
        popup.getMenu().add(0, 2, 0, mHost.getContext().getString(R.string.split_left));
        popup.getMenu().add(0, 3, 0, mHost.getContext().getString(R.string.split_right));
        popup.setOnMenuItemClickListener((MenuItem item) -> {
            switch (item.getItemId()) {
                case 1: applySplitSlot(0, 0, 0, W, H);     break;
                case 2: applySplitSlot(1, 0, 0, W / 2, H); break;
                case 3: applySplitSlot(2, W / 2, 0, W, H); break;
            }
            return true;
        });
        popup.show();
    }

    /**
     * Resizes the main cluster app to the given slot bounds.
     * slot 0 = full screen, 1 = left 50%, 2 = right 50%.
     */
    public void applySplitSlot(final int slot, final int l, final int t, final int r, final int b) {
        ClusterService svc = mHost.getClusterServiceIfBound();
        if (svc == null) {
            AppLogger.w(TAG, "applySplitSlot: service not bound — ignored");
            return;
        }
        final String splitPkg = mHost.getCurrentDashboardPkg();
        final String splitApp = mHost.getCurrentDashboardApp();
        AppLogger.i(TAG, "applySplitSlot slot=" + slot
                + " bounds=[" + l + "," + t + "," + r + "," + b + "]"
                + " pkg=" + splitPkg + " second=" + mSecondDashboardPkg);

        final int generation = beginSecondDashboardReplacement();
        if (slot == 0 && mSecondDashboardPkg != null) {
            final String secondPkg = mSecondDashboardPkg;
            AppLogger.i(TAG, "split → full screen: force-stop second=" + secondPkg);
            AdbLocalClient.forceStopApp(mHost.getContext(), secondPkg,
                    new AdbLocalClient.Callback() {
                @Override public void onSuccess(String report) {
                    mHost.runOnUiThread(() -> {
                        if (!commitFullScreenIfMatches(secondPkg, generation)) return;
                        relaunchPrimaryInSlot(svc, splitPkg, splitApp, l, t, r, b,
                                slot, generation);
                    });
                }

                @Override public void onError(String error) {
                    mHost.runOnUiThread(() -> {
                        if (!isCurrentSecondDashboardReplacement(generation)) return;
                        AppLogger.e(TAG, "split → full screen: secondary stop failed: " + error);
                        Toast.makeText(mHost.getContext(),
                                mHost.getContext().getString(R.string.toast_kill_failed, error),
                                Toast.LENGTH_LONG).show();
                    });
                }
            });
            return;
        }
        mCurrentSplitSlot = slot;
        relaunchPrimaryInSlot(svc, splitPkg, splitApp, l, t, r, b, slot, generation);
    }

    private void relaunchPrimaryInSlot(ClusterService svc, String splitPkg, String splitApp,
                                       int l, int t, int r, int b, int slot, int generation) {
        AdbLocalClient.forceStopApp(mHost.getContext(), splitPkg, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String ignored) {
                if (isCurrentSecondDashboardReplacement(generation)) {
                    launchInSlot(svc, splitPkg, splitApp, l, t, r, b, slot, generation);
                }
            }
            @Override public void onError(String error) {
                // force-stop failed: attempt relaunch anyway
                if (isCurrentSecondDashboardReplacement(generation)) {
                    launchInSlot(svc, splitPkg, splitApp, l, t, r, b, slot, generation);
                }
            }
        });
    }

    private void launchInSlot(ClusterService svc, String splitPkg, String splitApp,
                               int l, int t, int r, int b, int slot, int generation) {
        svc.launchOnDashboardWithBounds(splitPkg, l, t, r, b,
                launched -> mHost.runOnUiThread(() -> {
                    if (!isCurrentSecondDashboardReplacement(generation)) return;
                    if (launched) {
                        mHost.setCurrentDashboardPkg(splitPkg);
                        mHost.setCurrentDashboardApp(splitApp);
                        AppLogger.i(TAG, "split slot " + slot + " OK ["
                                + l + "," + t + "," + r + "," + b + "]");
                        mHost.onSplitStateChanged();
                    } else {
                        AppLogger.e(TAG, "split relaunch FAILED slot=" + slot);
                        Toast.makeText(mHost.getContext(),
                                mHost.getContext().getString(
                                        R.string.toast_app_launch_failed, splitApp),
                                Toast.LENGTH_SHORT).show();
                        mCurrentSplitSlot = 0;
                        mHost.onSplitStateChanged();
                    }
                }));
    }

    /** Resets split state (slot + second app). Call when the main app changes or cluster stops. */
    public void clearSplitState() {
        mReplacementGate.invalidate();
        if (mCurrentSplitSlot != 0 || mSecondDashboardPkg != null) {
            AppLogger.d(TAG, "clearSplitState — slot=" + mCurrentSplitSlot
                    + " second=" + mSecondDashboardPkg);
        }
        mSecondDashboardApp = null;
        mSecondDashboardPkg = null;
        mCurrentSplitSlot   = 0;
        // Use direct call when already on main thread so callers see the updated
        // isInSplitMode() immediately; post only when invoked from a bg thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mHost.onSplitStateChanged();
        } else {
            mHost.runOnUiThread(mHost::onSplitStateChanged);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    public int[] getClusterDimensions() {
        ClusterService svc = mHost.getClusterServiceIfBound();
        if (svc == null) return new int[]{1920, 720};
        return getClusterDimensions(svc);
    }

    private int[] getClusterDimensions(ClusterService svc) {
        int w = svc.getMirrorManager().getClusterWidth();
        int h = svc.getMirrorManager().getClusterHeight();
        if (w > 0 && h > 0) return new int[]{w, h};
        AppLogger.w(TAG, "getClusterDimensions → fallback 1920×720");
        return new int[]{1920, 720};
    }
}
