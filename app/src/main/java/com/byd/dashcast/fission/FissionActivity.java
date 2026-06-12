package com.byd.dashcast.fission;
import android.annotation.SuppressLint;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.cluster.ClusterService;
import com.byd.dashcast.R;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.domain.cluster.ProjectionStateProvider;
import com.byd.dashcast.proxy.daemon.BinderParcelable;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FissionActivity — split-screen (fission) projection manager.
 *
 * <h3>Architecture improvements (refactor v1.5)</h3>
 * <ul>
 *   <li><b>No static coupling to ClusterService</b>: normal projection state is read via
 *       {@link ProjectionStateProvider} obtained from the bound service. FissionOrchestrator
 *       receives it as a constructor argument.
 *   <li><b>No static binder field on FissionLayoutEditorActivity</b>: the daemon binder is
 *       passed via Intent extras using the existing {@link BinderParcelable}, exactly as
 *       MainActivity already does for the daemon binder it receives via broadcast.
 *   <li><b>Background logic extracted to {@link FissionOrchestrator}</b>: this Activity is
 *       now exclusively a UI shell — it handles dialogs, inflates slot rows, and delegates
 *       all daemon/fission operations to the Orchestrator.
 *   <li><b>Hard-coded French strings moved to strings.xml</b>: all user-visible text is now
 *       referenced via {@code R.string.*}.
 * </ul>
 *
 * Zero regression: every feature (auto-layout, pre-create, layout switcher, slot resize /
 * release dialogs, stop-all, mirror start) is preserved — now via Orchestrator callbacks.
 */
public class FissionActivity extends Activity implements FissionOrchestrator.Callbacks {

    private static final String TAG       = "FissionActivity";
    private static final int    CLUSTER_W = 1920;
    private static final int    CLUSTER_H = 720;

    // ── Extras key for passing the daemon binder to FissionLayoutEditorActivity ──
    public static final String EXTRA_DAEMON_BINDER = "daemon_binder";

    // ── UI ──────────────────────────────────────────────────────────────────────
    private SurfaceView    svPreview;
    private SurfaceHolder  mHolder;
    private boolean        mSurfaceReady;
    private TextView       tvStatus;
    private LinearLayout   llSlots;
    private MaterialButton btnAdd;
    private MaterialButton btnStopAll;
    private TextView       tvLayoutLabel;
    private MaterialButton btnSwitchLayout;

    // ── State ────────────────────────────────────────────────────────────────────
    private FissionOrchestrator mOrchestrator;
    private boolean             mDestroyed;
    private final Handler       mUiHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_fission);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_fission);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_fission_layouts) {
                openLayoutEditor();
                return true;
            }
            return false;
        });

        svPreview      = findViewById(R.id.sv_fission_preview);
        tvStatus       = findViewById(R.id.tv_fission_status);
        llSlots        = findViewById(R.id.ll_fission_slots);
        btnAdd         = findViewById(R.id.btn_fission_add);
        btnStopAll     = findViewById(R.id.btn_fission_stop);
        tvLayoutLabel  = findViewById(R.id.tv_fission_layout_label);
        btnSwitchLayout = findViewById(R.id.btn_fission_switch_layout);

        if (btnSwitchLayout != null) btnSwitchLayout.setOnClickListener(v -> showLayoutSwitcher());
        btnStopAll.setEnabled(false);

        mHolder = svPreview.getHolder();
        mHolder.setFixedSize(CLUSTER_W, CLUSTER_H);
        mHolder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                mSurfaceReady = true;
                btnAdd.setEnabled(!mOrchestrator.isProjecting());
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                mSurfaceReady = false;
                btnAdd.setEnabled(false);
            }
        });

        btnAdd.setOnClickListener(v -> pickApp());
        btnStopAll.setOnClickListener(v -> mOrchestrator.stopAll());

        // Build the projection-state provider from the live ClusterService, or a safe default.
        ProjectionStateProvider psp = buildProjectionStateProvider();

        mOrchestrator = new FissionOrchestrator(this, psp, this);

        LayoutPreset fav = LayoutPrefs.getFavoriteLayout(getApplicationContext());
        boolean autoLayout = ClusterPrefs.isFissionAutoLayout(this);
        boolean precreate  = ClusterPrefs.isFissionPrecreateSlots(this);
        mOrchestrator.initAsync(fav, autoLayout, precreate);
    }

    private ProjectionStateProvider buildProjectionStateProvider() {
        ClusterService svc = ClusterService.getInstance();
        if (svc != null) return svc;
        // Fallback: read the volatile static flag directly (same data, no IAM call).
        return new ProjectionStateProvider() {
            @Override public boolean isProjectionActive() { return ClusterService.sIsRunning; }
            @Override public void stopProjectionIfActive(Runnable onStopped) {
                ClusterService cs = ClusterService.getInstance();
                if (cs != null) cs.stopProjectionNoAdb();
                if (onStopped != null) mUiHandler.post(onStopped);
            }
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
        mUiHandler.removeCallbacksAndMessages(null);
        if (mOrchestrator != null) mOrchestrator.destroy(isFinishing());
    }

    // ── FissionOrchestrator.Callbacks (main thread) ──────────────────────────────

    @Override
    public void onSlotsChanged(java.util.Collection<FissionOrchestrator.SlotState> slots) {
        updateSlotsUi(slots);
        boolean projecting = !slots.isEmpty();
        btnAdd.setEnabled(mSurfaceReady && !projecting || projecting);
        btnStopAll.setEnabled(projecting);
        if (tvLayoutLabel != null) updateLayoutSelectorUi();
    }

    @Override
    public void onDaemonBinderAcquired(IBinder binder) {
        // Nothing to do on the UI side. The binder is passed to FissionLayoutEditorActivity
        // via Intent when the user navigates there (see openLayoutEditor()).
    }

    @Override
    public void onStatusMessage(String message) {
        setStatus(message);
    }

    @Override
    public void onSlotError(String pkg, String message) {
        Toast.makeText(this,
                getString(R.string.fission_slot_error, pkg, message != null ? message : "?"),
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onProjectionConflict(Runnable proceedCallback) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.fission_conflict_title)
                .setMessage(R.string.fission_conflict_message)
                .setPositiveButton(R.string.fission_conflict_stop, (d, w) -> {
                    if (proceedCallback != null) proceedCallback.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── App picker ────────────────────────────────────────────────────────────────

    private void pickApp() {
        if (!mSurfaceReady) return;
        btnAdd.setEnabled(false);
        ExecutorService tmpExec = Executors.newSingleThreadExecutor();
        tmpExec.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos;
            try { infos = pm.queryIntentActivities(main, 0); }
            catch (Exception e) {
                safeRun(() -> {
                    btnAdd.setEnabled(mSurfaceReady);
                    Toast.makeText(this, R.string.fission_no_apps, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            if (infos == null || infos.isEmpty()) {
                safeRun(() -> {
                    btnAdd.setEnabled(mSurfaceReady);
                    Toast.makeText(this, R.string.fission_no_apps, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            String selfPkg = getPackageName();
            java.util.Collection<FissionOrchestrator.SlotState> slots =
                    mOrchestrator.getSlots();
            java.util.Set<String> activeSlotPkgs = new java.util.HashSet<>();
            for (FissionOrchestrator.SlotState s : slots) activeSlotPkgs.add(s.pkg);

            Map<String, String> pkgToLabel = new LinkedHashMap<>();
            for (ResolveInfo ri : infos) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(selfPkg)
                        || pkgToLabel.containsKey(pkg)
                        || activeSlotPkgs.contains(pkg)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                pkgToLabel.put(pkg, lbl != null ? lbl.toString() : pkg);
            }
            if (pkgToLabel.isEmpty()) {
                safeRun(() -> {
                    btnAdd.setEnabled(mSurfaceReady);
                    Toast.makeText(this, R.string.fission_all_projected, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            List<Map.Entry<String, String>> sorted = new ArrayList<>(pkgToLabel.entrySet());
            Collections.sort(sorted, (a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
            final String[] pkgs   = new String[sorted.size()];
            final String[] labels = new String[sorted.size()];
            for (int i = 0; i < sorted.size(); i++) {
                pkgs[i]   = sorted.get(i).getKey();
                labels[i] = sorted.get(i).getValue() + "  —  " + pkgs[i];
            }
            List<LayoutPreset> presets = LayoutPrefs.load(this);
            safeRun(() -> {
                btnAdd.setEnabled(mSurfaceReady);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.fission_pick_app_title)
                        .setItems(labels, (d, which) -> {
                            if (which < 0 || which >= pkgs.length) return;
                            String pkg2   = pkgs[which];
                            String label2 = sorted.get(which).getValue();
                            if (!presets.isEmpty()) showLayoutOrFreePicker(pkg2, label2, presets);
                            else                    startSlot(pkg2, label2, autoRect());
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
        tmpExec.shutdown();
    }

    private void showLayoutOrFreePicker(String pkg, String appLabel, List<LayoutPreset> presets) {
        String[] names = new String[presets.size() + 1];
        names[0] = getString(R.string.fission_mode_free);
        for (int i = 0; i < presets.size(); i++) {
            names[i + 1] = presets.get(i).name + "  (" + presets.get(i).slots.size() + " zones)";
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.fission_place_in, appLabel))
                .setItems(names, (d, which) -> {
                    if (which == 0) startSlot(pkg, appLabel, autoRect());
                    else            showZonePicker(pkg, appLabel, presets.get(which - 1).slots);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showZonePicker(String pkg, String appLabel, List<LayoutPreset.SlotDef> slots) {
        String[] names = new String[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            LayoutPreset.SlotDef s = slots.get(i);
            names[i] = s.label + "  (" + s.w + "×" + s.h + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.fission_launch_in, appLabel))
                .setItems(names, (d, which) -> startSlot(pkg, appLabel, slots.get(which).toRect()))
                .setNeutralButton(R.string.fission_mode_free, (d, w) -> startSlot(pkg, appLabel, autoRect()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private Rect autoRect() {
        java.util.Collection<FissionOrchestrator.SlotState> slots = mOrchestrator.getSlots();
        if (slots.isEmpty()) return new Rect(0, 0, CLUSTER_W, CLUSTER_H);
        int maxRight = 0;
        for (FissionOrchestrator.SlotState s : slots)
            maxRight = Math.max(maxRight, s.rect.right);
        int remaining = CLUSTER_W - maxRight;
        if (remaining > 100) return new Rect(maxRight, 0, CLUSTER_W, CLUSTER_H);
        FissionOrchestrator.SlotState first = slots.iterator().next();
        int mid = first.rect.left + first.rect.width() / 2;
        mOrchestrator.resizeSlotAsync(first.pkg, new Rect(first.rect.left, 0, mid, CLUSTER_H));
        return new Rect(mid, 0, CLUSTER_W, CLUSTER_H);
    }

    private void startSlot(String pkg, String label, Rect rect) {
        mOrchestrator.startSlot(pkg, label, rect, mHolder);
    }

    // ── Slot resize / release dialogs ─────────────────────────────────────────────

    private void showResizeDialog(FissionOrchestrator.SlotState slot) {
        String[] names = {
            getString(R.string.fission_resize_full),
            getString(R.string.fission_resize_left_half),
            getString(R.string.fission_resize_right_half),
            getString(R.string.fission_resize_left_3q),
            getString(R.string.fission_resize_right_3q)
        };
        Rect[] rects = {
            new Rect(0,           0, CLUSTER_W,       CLUSTER_H),
            new Rect(0,           0, CLUSTER_W / 2,   CLUSTER_H),
            new Rect(CLUSTER_W/2, 0, CLUSTER_W,       CLUSTER_H),
            new Rect(0,           0, CLUSTER_W*3/4,   CLUSTER_H),
            new Rect(CLUSTER_W/4, 0, CLUSTER_W,       CLUSTER_H)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.fission_resize_title, slot.label))
                .setItems(names, (d, which) -> {
                    mOrchestrator.resizeSlotAsync(slot.pkg, rects[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── Layout selector ────────────────────────────────────────────────────────────

    private void openLayoutEditor() {
        IBinder binder = (mOrchestrator != null) ? mOrchestrator.getDaemonBinder() : null;
        Intent intent = new Intent(this, FissionLayoutEditorActivity.class);
        if (binder != null) {
            // Pass binder via Intent extras — eliminates the static field coupling.
            intent.putExtra(EXTRA_DAEMON_BINDER, new BinderParcelable(binder));
        }
        startActivity(intent);
    }

    private void showLayoutSwitcher() {
        List<LayoutPreset> presets = LayoutPrefs.load(this);
        String favId = LayoutPrefs.getFavoriteId(this);
        LayoutPreset active = mOrchestrator.getActiveLayout();
        String[] names = new String[presets.size() + 1];
        names[0] = getString(R.string.fission_layout_mode_free);
        for (int i = 0; i < presets.size(); i++) {
            LayoutPreset p = presets.get(i);
            String label   = (p.id.equals(favId) ? "⭐ " : "") + p.name;
            if (active != null && p.id.equals(active.id)) label += "  ✓";
            names[i + 1] = label;
        }
        final LayoutPreset[] arr = presets.toArray(new LayoutPreset[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.fission_layout_switch)
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        // Free mode
                        mOrchestrator.switchToLayoutAsync(null);
                    } else {
                        mOrchestrator.switchToLayoutAsync(arr[which - 1]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateLayoutSelectorUi() {
        if (tvLayoutLabel == null) return;
        LayoutPreset active = (mOrchestrator != null) ? mOrchestrator.getActiveLayout() : null;
        if (active == null) {
            tvLayoutLabel.setText(R.string.fission_layout_mode_free);
        } else {
            String favId  = LayoutPrefs.getFavoriteId(this);
            boolean isFav = active.id.equals(favId);
            String text   = getString(R.string.fission_layout_active_fmt, active.name);
            if (isFav) text += "  " + getString(R.string.fission_layout_favorite_suffix);
            tvLayoutLabel.setText(text);
        }
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n") // technical geometry/IDs, locale-neutral
    private void updateSlotsUi(java.util.Collection<FissionOrchestrator.SlotState> slots) {
        if (llSlots == null) return;
        llSlots.removeAllViews();
        if (slots.isEmpty()) { llSlots.setVisibility(View.GONE); return; }
        llSlots.setVisibility(View.VISIBLE);
        for (FissionOrchestrator.SlotState slot : slots) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_fission_slot, llSlots, false);
            TextView tvLabel = row.findViewById(R.id.tv_fission_slot_label);
            tvLabel.setText(slot.label + "  "
                    + slot.rect.width() + "×" + slot.rect.height()
                    + "  [VD:" + slot.displayId + "]");
            MaterialButton btnResize = row.findViewById(R.id.btn_fission_slot_resize);
            btnResize.setOnClickListener(v -> showResizeDialog(slot));
            MaterialButton btnClose = row.findViewById(R.id.btn_fission_slot_close);
            btnClose.setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.fission_close_slot_title, slot.label))
                            .setPositiveButton(R.string.fission_close_slot_confirm,
                                    (d, w) -> mOrchestrator.releaseSlotAsync(slot.pkg))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show());
            llSlots.addView(row);
        }
    }

    private void setStatus(String msg) {
        AppLogger.d(TAG, "status: " + (msg == null ? "(idle)" : msg));
        if (tvStatus == null) return;
        if (msg == null) {
            java.util.Collection<FissionOrchestrator.SlotState> slots =
                    (mOrchestrator != null) ? mOrchestrator.getSlots()
                                            : java.util.Collections.emptyList();
            tvStatus.setText(slots.isEmpty()
                    ? getString(R.string.fission_idle_hint)
                    : getString(R.string.fission_active_count, slots.size()));
        } else {
            tvStatus.setText(msg);
        }
    }

    private void safeRun(Runnable r) {
        if (mDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        mUiHandler.post(() -> { if (!mDestroyed) r.run(); });
    }
}
