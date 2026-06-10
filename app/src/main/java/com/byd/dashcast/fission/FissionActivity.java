package com.byd.dashcast.fission;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Bundle;
import android.os.Handler;
import androidx.appcompat.app.AlertDialog;
import com.byd.dashcast.AdbLocalClient;
import com.byd.dashcast.proxy.ShellGateway;
import com.byd.dashcast.AppLogger;
import com.byd.dashcast.ClusterService;
import com.byd.dashcast.R;
import com.byd.dashcast.proxy.ProxyClient;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FissionActivity extends Activity {

    private static final String TAG      = "FissionActivity";
    private static final int    CLUSTER_W = 1920;
    private static final int    CLUSTER_H = 720;

    private static class SlotState {
        final String pkg;
        final String label;
        final int    displayId;
        Rect         rect;
        SlotState(String pkg, String label, int displayId, Rect rect) {
            this.pkg = pkg; this.label = label;
            this.displayId = displayId; this.rect = rect;
        }
    }

    private IBinder       mDaemonBinder;
    private boolean       mProjecting;
    private boolean       mMirrorReady;
    private boolean       mDestroyed;
    private int           mFirstDisplayId = -1;
    private LayoutPreset  mActiveLayout;

    private final ConcurrentHashMap<String, SlotState> mSlots = new ConcurrentHashMap<>();

    private SurfaceView    svPreview;
    private SurfaceHolder  mHolder;
    private boolean        mSurfaceReady;
    private TextView       tvStatus;
    private LinearLayout   llSlots;
    private MaterialButton btnAdd, btnStopAll;
    private TextView       tvLayoutLabel;
    private MaterialButton btnSwitchLayout;

    private final Handler         mUiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExec      = Executors.newSingleThreadExecutor();
    // FA-3: application context for background lambdas — avoids holding Activity ref after destroy.
    private Context mAppCtx;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        mAppCtx = getApplicationContext();
        setContentView(R.layout.activity_fission);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_fission);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_fission_layouts) {
                FissionLayoutEditorActivity.sDaemonBinder = mDaemonBinder;
                startActivity(new Intent(this, FissionLayoutEditorActivity.class));
                return true;
            }
            return false;
        });

        svPreview = findViewById(R.id.sv_fission_preview);
        tvStatus  = findViewById(R.id.tv_fission_status);
        llSlots   = findViewById(R.id.ll_fission_slots);
        btnAdd         = findViewById(R.id.btn_fission_add);
        btnStopAll     = findViewById(R.id.btn_fission_stop);
        tvLayoutLabel  = findViewById(R.id.tv_fission_layout_label);
        btnSwitchLayout = findViewById(R.id.btn_fission_switch_layout);
        if (btnSwitchLayout != null) btnSwitchLayout.setOnClickListener(v -> showLayoutSwitcher());

        btnStopAll.setEnabled(false);

        mHolder = svPreview.getHolder();
        mHolder.setFixedSize(CLUSTER_W, CLUSTER_H);
        mHolder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h)          { mSurfaceReady = true;  btnAdd.setEnabled(!mProjecting); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h)        { mSurfaceReady = false; btnAdd.setEnabled(false); }
        });

        btnAdd.setOnClickListener(v -> pickApp());
        btnStopAll.setOnClickListener(v -> stopAll());

        // Probe daemon + apply auto-layout / pre-create settings if configured
        mExec.execute(() -> {
            tryGetBinder();
            LayoutPreset fav = LayoutPrefs.getFavoriteLayout(mAppCtx);
            if (fav != null) {
                mActiveLayout = fav;
                safeRun(this::updateLayoutSelectorUi);
                if (ClusterPrefs.isFissionAutoLayout(mAppCtx)) {
                    safeRun(() -> setStatus(getString(R.string.fission_auto_activating)));
                    activateFavoriteLayout();
                } else if (ClusterPrefs.isFissionPrecreateSlots(mAppCtx)) {
                    precreateSlots(fav);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Keep the layout editor's shared binder in sync
        FissionLayoutEditorActivity.sDaemonBinder = mDaemonBinder;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
        mUiHandler.removeCallbacksAndMessages(null);
        // Release active slots when user navigates away without pressing Stop.
        // isFinishing() guards against config-change recreation.
        if (isFinishing() && !mSlots.isEmpty()) {
            final IBinder binder = mDaemonBinder;
            final List<String> pkgs = new ArrayList<>(mSlots.keySet());
            mExec.execute(() -> {
                for (String pkg : pkgs) {
                    if (binder != null) {
                        try { FissionClient.releaseSlot(binder, pkg); } catch (Throwable ignored) {}
                    }
                    ShellGateway.execShell(mAppCtx, "am force-stop " + pkg);
                }
                if (binder != null) {
                    try { FissionClient.stopMirror(binder); } catch (Throwable ignored) {}
                }
            });
        }
        mExec.shutdown();
    }

    // ── Binder acquisition ────────────────────────────────────────────────────

    private void tryGetBinder() {
        IBinder b = FissionClient.getBinderFromServiceManager();
        if (b != null) {
            mDaemonBinder = b;
            FissionLayoutEditorActivity.sDaemonBinder = b;
            AppLogger.d(TAG, "Daemon binder found in ServiceManager");
        }
    }

    /**
     * Ensures daemon is running and binder is acquired.
     * Blocks the calling thread up to ~8s. Call from background thread only.
     * Returns true on success.
     */
    private boolean ensureDaemon() {
        // Guard first — must run before any daemon access, including the fast path.
        // ClusterService owns the daemon when normal projection is active; letting
        // Fission reuse that binder would corrupt the mirror state and send apps
        // back to display 0.
        if (ClusterService.sIsRunning) {
            safeRun(() -> Toast.makeText(this,
                    "Arrêtez d'abord la projection normale avant d'utiliser le mode Fission.",
                    Toast.LENGTH_LONG).show());
            return false;
        }

        // Fast path — daemon already running (Fission started it earlier this session)
        IBinder b = FissionClient.getBinderFromServiceManager();
        if (b != null) { mDaemonBinder = b; FissionLayoutEditorActivity.sDaemonBinder = b; return true; }

        // Daemon not running — start it via AdbLocalClient (detached shell)
        safeRun(() -> setStatus("Démarrage du daemon…"));
        AdbLocalClient.startMirrorDaemon(this);

        // Poll up to 8s
        for (int i = 0; i < 16; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            b = FissionClient.getBinderFromServiceManager();
            if (b != null) {
                mDaemonBinder = b;
                FissionLayoutEditorActivity.sDaemonBinder = b;
                AppLogger.d(TAG, "Daemon binder acquired after " + ((i + 1) * 500) + "ms");
                return true;
            }
        }
        AppLogger.e(TAG, "Daemon binder NOT found after 8s");
        return false;
    }

    // ── App picker ────────────────────────────────────────────────────────────

    private void pickApp() {
        if (!mSurfaceReady) return;
        // Disable button while loading to prevent double-tap.
        btnAdd.setEnabled(false);
        // PM query + loadLabel can block >100 ms on a crowded head unit — run off main thread.
        mExec.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos;
            try {
                infos = pm.queryIntentActivities(main, 0);
            } catch (Exception e) {
                safeRun(() -> {
                    btnAdd.setEnabled(mSurfaceReady);
                    Toast.makeText(this, "Aucune application disponible", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            if (infos == null || infos.isEmpty()) {
                safeRun(() -> {
                    btnAdd.setEnabled(mSurfaceReady);
                    Toast.makeText(this, "Aucune application disponible", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            String selfPkg = getPackageName();
            Map<String, String> pkgToLabel = new LinkedHashMap<>();
            for (ResolveInfo ri : infos) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(selfPkg) || pkgToLabel.containsKey(pkg)
                        || mSlots.containsKey(pkg)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                pkgToLabel.put(pkg, lbl != null ? lbl.toString() : pkg);
            }
            if (pkgToLabel.isEmpty()) {
                safeRun(() -> {
                    btnAdd.setEnabled(mSurfaceReady);
                    Toast.makeText(this, "Toutes les apps sont déjà projetées", Toast.LENGTH_SHORT).show();
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
                        .setTitle("Choisir une application")
                        .setItems(labels, (d, which) -> {
                            if (which < 0 || which >= pkgs.length) return;
                            String pkg2   = pkgs[which];
                            String label2 = sorted.get(which).getValue();
                            if (!presets.isEmpty()) showLayoutOrFreePicker(pkg2, label2, presets);
                            else                    startSlot(pkg2, label2, autoRect());
                        })
                        .setNegativeButton("Annuler", null)
                        .show();
            });
        });
    }

    private void showLayoutOrFreePicker(String pkg, String appLabel, List<LayoutPreset> presets) {
        String[] names = new String[presets.size() + 1];
        names[0] = "Mode libre (automatique)";
        for (int i = 0; i < presets.size(); i++) {
            names[i + 1] = presets.get(i).name + "  (" + presets.get(i).slots.size() + " zones)";
        }
        new AlertDialog.Builder(this)
                .setTitle("Placer " + appLabel + " dans…")
                .setItems(names, (d, which) -> {
                    if (which == 0) startSlot(pkg, appLabel, autoRect());
                    else            showZonePicker(pkg, appLabel, presets.get(which - 1).slots);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showZonePicker(String pkg, String appLabel, List<LayoutPreset.SlotDef> slots) {
        String[] names = new String[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            LayoutPreset.SlotDef s = slots.get(i);
            names[i] = s.label + "  (" + s.w + "×" + s.h + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Lancer " + appLabel + " dans…")
                .setItems(names, (d, which) -> startSlot(pkg, appLabel, slots.get(which).toRect()))
                .setNeutralButton("Mode libre", (d, w) -> startSlot(pkg, appLabel, autoRect()))
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Returns the largest available horizontal segment, or full cluster if empty. */
    private Rect autoRect() {
        if (mSlots.isEmpty()) return new Rect(0, 0, CLUSTER_W, CLUSTER_H);
        // Find the rightmost occupied position
        int maxRight = 0;
        for (SlotState s : mSlots.values()) maxRight = Math.max(maxRight, s.rect.right);
        int remaining = CLUSTER_W - maxRight;
        if (remaining > 100) return new Rect(maxRight, 0, CLUSTER_W, CLUSTER_H);
        // No free space — split the first slot 50/50
        SlotState first = mSlots.values().iterator().next();
        int mid = first.rect.left + first.rect.width() / 2;
        sendResizeSlot(first.pkg, new Rect(first.rect.left, 0, mid, CLUSTER_H));
        first.rect = new Rect(first.rect.left, 0, mid, CLUSTER_H);
        return new Rect(mid, 0, CLUSTER_W, CLUSTER_H);
    }

    // ── Start slot ────────────────────────────────────────────────────────────

    private void startSlot(String pkg, String label, Rect rect) {
        if (!mSurfaceReady) return;

        // Guard: normal projection may have (re)started automatically, e.g. via ADAS warm
        // path, without the user noticing. Ask explicitly rather than silently blocking.
        if (ClusterService.sIsRunning) {
            new AlertDialog.Builder(this)
                    .setTitle("Projection en cours")
                    .setMessage("La projection normale est active sur le cluster. "
                            + "Voulez-vous l'arrêter pour utiliser le mode Fission ?")
                    .setPositiveButton("Arrêter et continuer", (d, w) -> {
                        // Clear the flag immediately so ensureDaemon() won't re-block.
                        ClusterService.sIsRunning = false;
                        ClusterService cs = ClusterService.getInstance();
                        if (cs != null) cs.stopProjectionNoAdb();
                        mUiHandler.postDelayed(() -> startSlot(pkg, label, rect), 400);
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }

        btnAdd.setEnabled(false);
        btnStopAll.setEnabled(false);
        setStatus("Démarrage de " + label + "…");
        mExec.execute(() -> {
            try {
                doStartSlot(pkg, label, rect);
            } catch (Exception e) {
                AppLogger.e(TAG, "startSlot error pkg=" + pkg, e);
                mSlots.remove(pkg);
                if (mDaemonBinder != null) {
                    try { FissionClient.releaseSlot(mDaemonBinder, pkg); } catch (Exception ignored) {}
                }
                safeRun(() -> {
                    updateSlotsUi();
                    setStatus("Erreur: " + e.getMessage());
                    btnAdd.setEnabled(mSurfaceReady);
                    btnStopAll.setEnabled(!mSlots.isEmpty());
                });
            }
        });
    }

    private void doStartSlot(String pkg, String label, Rect rect) throws Exception {
        boolean isFirst = mSlots.isEmpty();

        // Step 1 — Ensure daemon is running
        if (!ensureDaemon()) throw new RuntimeException("Daemon non disponible");

        // Step 2 — ATTACH_SLOT or REUSE if VD already alive in daemon
        int existingId = -1;
        try { existingId = FissionClient.querySlot(mDaemonBinder, pkg); } catch (Exception ignored) {}
        final int displayId;
        if (existingId > 0) {
            // VD still alive — just resize, skip creating a new one
            safeRun(() -> setStatus("Réutilisation du slot pour " + label + "…"));
            try {
                FissionClient.resizeSlot(mDaemonBinder, pkg,
                        rect.left, rect.top, rect.width(), rect.height());
            } catch (Exception ignored) {}
            displayId = existingId;
            AppLogger.i(TAG, "FISSION REUSE_SLOT pkg=" + pkg + " displayId=" + displayId);
            safeRun(() -> Toast.makeText(this,
                    getString(R.string.fission_slot_reused_toast), Toast.LENGTH_SHORT).show());
        } else {
            safeRun(() -> setStatus("Création du slot pour " + label + "…"));
            int newId = FissionClient.attachSlot(mDaemonBinder, pkg,
                    rect.left, rect.top, rect.width(), rect.height());
            if (newId < 0) throw new RuntimeException("ATTACH_SLOT failed pour " + pkg);
            displayId = newId;
            AppLogger.i(TAG, "FISSION ATTACH_SLOT pkg=" + pkg
                    + " displayId=" + displayId
                    + " rect=" + rect.left + "," + rect.top + "+" + rect.width() + "x" + rect.height());
        }

        // Step 3 — LAUNCH_AND_FORCE via Proxy Daemon (Phase5b watchdog: am start + moveTaskToDisplay)
        // The Proxy Daemon must be running (enabled in Settings > Beta). If not connected we
        // throw so the slot is not added — launching without the watchdog would leave the app
        // on display 0 and is not reliable enough to silently accept.
        safeRun(() -> setStatus("Lancement de " + label + " (displayId=" + displayId + ")…"));
        if (!ProxyClient.isConnected()) {
            AppLogger.d(TAG, "ProxyClient not connected — attempting connect…");
            boolean connected = ProxyClient.connect(this);
            if (!connected) {
                throw new RuntimeException(
                        "Proxy Daemon non connecté. Activez « Proxy Daemon ADB » dans Paramètres > Beta.");
            }
        }
        AppLogger.i(TAG, "FISSION LAUNCH_AND_FORCE pkg=" + pkg + " → displayId=" + displayId);
        String launchResult = ProxyClient.launchAndForce(pkg, null, displayId,
                rect.width(), rect.height());
        // Always log the full daemon result at INFO so it's visible in sniffer
        AppLogger.i(TAG, "FISSION launchAndForce result:\n" + launchResult);
        if (launchResult != null && !launchResult.startsWith("OK"))
            AppLogger.w(TAG, "FISSION launchAndForce non-OK: " + launchResult);

        // Step 4 — MIRROR_START on first slot (shows the cluster in the SurfaceView)
        if (isFirst) {
            safeRun(() -> setStatus("Démarrage du miroir…"));
            mFirstDisplayId = displayId;
            int svW = svPreview.getWidth(), svH = svPreview.getHeight();
            if (svW <= 0 || svH <= 0) { svW = CLUSTER_W; svH = CLUSTER_H; }
            mMirrorReady = FissionClient.startMirror(mDaemonBinder,
                    displayId, rect.width(), rect.height(),
                    displayId, svW, svH, mHolder.getSurface());
            AppLogger.i(TAG, "FISSION MIRROR_START displayId=" + displayId + " ok=" + mMirrorReady);
        }

        mSlots.put(pkg, new SlotState(pkg, label, displayId, new Rect(rect)));
        mProjecting = true;

        safeRun(() -> {
            updateSlotsUi();
            btnAdd.setEnabled(mSurfaceReady);
            btnStopAll.setEnabled(true);
            setStatus(null);
        });
    }

    // ── Stop ─────────────────────────────────────────────────────────────────

    private void stopAll() {
        if (!mProjecting) return;
        setStatus("Arrêt…");
        btnAdd.setEnabled(false);
        btnStopAll.setEnabled(false);
        mExec.execute(() -> {
            mProjecting  = false;
            mMirrorReady = false;
            // Force-stop each projected app before releasing the display so it
            // doesn't linger on the main screen after the user presses Stop.
            for (String pkg : mSlots.keySet()) {
                ShellGateway.execShell(mAppCtx, "am force-stop " + pkg);
            }
            mSlots.clear();
            if (mDaemonBinder != null) {
                FissionClient.stopMirror(mDaemonBinder);
            }
            mDaemonBinder     = null;
            mFirstDisplayId   = -1;
            FissionLayoutEditorActivity.sDaemonBinder = null;
            safeRun(() -> {
                updateSlotsUi();
                setStatus(null);
                btnAdd.setEnabled(mSurfaceReady);
                btnStopAll.setEnabled(false);
            });
        });
    }

    private void releaseSlot(String pkg) {
        mExec.execute(() -> {
            if (mDaemonBinder != null) {
                try { FissionClient.releaseSlot(mDaemonBinder, pkg); }
                catch (Exception e) { AppLogger.e(TAG, "releaseSlot error", e); }
            }
            // Kill the app after releasing its display slot so it doesn't linger
            // on the main screen and leave stale task state for the next launch.
            ShellGateway.execShell(mAppCtx, "am force-stop " + pkg);
            mSlots.remove(pkg);
            mProjecting = !mSlots.isEmpty();
            safeRun(() -> {
                updateSlotsUi();
                btnAdd.setEnabled(mSurfaceReady);
                btnStopAll.setEnabled(!mSlots.isEmpty());
            });
        });
    }

    private void sendResizeSlot(String pkg, Rect r) {
        mExec.execute(() -> {
            if (mDaemonBinder == null) return;
            try { FissionClient.resizeSlot(mDaemonBinder, pkg, r.left, r.top, r.width(), r.height()); }
            catch (Exception e) { AppLogger.e(TAG, "resizeSlot error", e); }
        });
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void updateSlotsUi() {
        if (llSlots == null) return;
        llSlots.removeAllViews();
        if (mSlots.isEmpty()) { llSlots.setVisibility(View.GONE); return; }

        llSlots.setVisibility(View.VISIBLE);
        for (SlotState slot : mSlots.values()) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_fission_slot, llSlots, false);

            TextView tvLabel = row.findViewById(R.id.tv_fission_slot_label);
            tvLabel.setText(slot.label
                    + "  " + slot.rect.width() + "×" + slot.rect.height()
                    + "  [VD:" + slot.displayId + "]");

            MaterialButton btnResize = row.findViewById(R.id.btn_fission_slot_resize);
            btnResize.setOnClickListener(v -> showResizeDialog(slot));

            MaterialButton btnClose = row.findViewById(R.id.btn_fission_slot_close);
            btnClose.setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle("Fermer " + slot.label + " ?")
                            .setPositiveButton("Fermer", (d, w) -> releaseSlot(slot.pkg))
                            .setNegativeButton("Annuler", null)
                            .show());

            llSlots.addView(row);
        }
    }

    private void showResizeDialog(SlotState slot) {
        String[] names = {
            "Plein écran", "Gauche 1/2", "Droite 1/2", "Gauche 3/4", "Droite 3/4"
        };
        Rect[] rects = {
            new Rect(0,           0, CLUSTER_W,       CLUSTER_H),
            new Rect(0,           0, CLUSTER_W / 2,   CLUSTER_H),
            new Rect(CLUSTER_W/2, 0, CLUSTER_W,       CLUSTER_H),
            new Rect(0,           0, CLUSTER_W*3/4,   CLUSTER_H),
            new Rect(CLUSTER_W/4, 0, CLUSTER_W,       CLUSTER_H)
        };
        new AlertDialog.Builder(this)
                .setTitle("Redimensionner " + slot.label)
                .setItems(names, (d, which) -> {
                    slot.rect = new Rect(rects[which]);
                    sendResizeSlot(slot.pkg, rects[which]);
                    updateSlotsUi();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void setStatus(String msg) {
        AppLogger.d(TAG, "status: " + (msg == null ? "(idle)" : msg));
        if (tvStatus == null) return;
        if (msg == null) {
            tvStatus.setText(mSlots.isEmpty() ? "Appuyez sur + pour projeter une application"
                                              : mSlots.size() + " app(s) en cours");
        } else {
            tvStatus.setText(msg);
        }
    }

    private void safeRun(Runnable r) {
        if (mDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        mUiHandler.post(() -> { if (!mDestroyed) r.run(); });
    }

    // ── Layout selector UI ────────────────────────────────────────────────────

    private void updateLayoutSelectorUi() {
        if (tvLayoutLabel == null) return;
        if (mActiveLayout == null) {
            tvLayoutLabel.setText(getString(R.string.fission_layout_mode_free));
        } else {
            String favId  = LayoutPrefs.getFavoriteId(this);
            boolean isFav = mActiveLayout.id.equals(favId);
            String text   = getString(R.string.fission_layout_active_fmt, mActiveLayout.name);
            if (isFav) text += "  " + getString(R.string.fission_layout_favorite_suffix);
            tvLayoutLabel.setText(text);
        }
    }

    private void showLayoutSwitcher() {
        List<LayoutPreset> presets = LayoutPrefs.load(this);
        String favId = LayoutPrefs.getFavoriteId(this);
        String[] names = new String[presets.size() + 1];
        names[0] = getString(R.string.fission_layout_mode_free);
        for (int i = 0; i < presets.size(); i++) {
            LayoutPreset p  = presets.get(i);
            String label    = (p.id.equals(favId) ? "⭐ " : "") + p.name;
            if (mActiveLayout != null && p.id.equals(mActiveLayout.id)) label += "  ✓";
            names[i + 1] = label;
        }
        final LayoutPreset[] arr = presets.toArray(new LayoutPreset[0]);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.fission_layout_switch))
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        mActiveLayout = null;
                        updateLayoutSelectorUi();
                    } else {
                        switchToLayout(arr[which - 1]);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ── Layout switching ──────────────────────────────────────────────────────

    private void switchToLayout(LayoutPreset newLayout) {
        btnAdd.setEnabled(false);
        btnStopAll.setEnabled(false);
        mActiveLayout = newLayout;
        safeRun(this::updateLayoutSelectorUi);
        mExec.execute(() -> {
            try {
                doSwitchToLayout(newLayout);
            } catch (Exception e) {
                AppLogger.e(TAG, "switchToLayout error", e);
                safeRun(() -> Toast.makeText(this,
                        "Erreur layout: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                safeRun(() -> {
                    updateSlotsUi();
                    btnAdd.setEnabled(mSurfaceReady);
                    btnStopAll.setEnabled(!mSlots.isEmpty());
                });
            }
        });
    }

    /** Must be called from mExec background thread. */
    private void doSwitchToLayout(LayoutPreset newLayout) throws Exception {
        // Collect packages bound in new layout
        Set<String> newPkgs = new HashSet<>();
        for (LayoutPreset.SlotDef s : newLayout.slots) {
            if (s.packageName != null && !s.packageName.isEmpty()) newPkgs.add(s.packageName);
        }
        // Release slots not present in new layout
        List<String> toRelease = new ArrayList<>(mSlots.keySet());
        for (String pkg : toRelease) {
            if (!newPkgs.contains(pkg)) {
                if (mDaemonBinder != null) {
                    try { FissionClient.releaseSlot(mDaemonBinder, pkg); } catch (Exception ignored) {}
                }
                ShellGateway.execShell(mAppCtx, "am force-stop " + pkg);
                mSlots.remove(pkg);
            }
        }
        mProjecting = !mSlots.isEmpty();
        // Start / reuse bound slots
        for (LayoutPreset.SlotDef s : newLayout.slots) {
            if (s.packageName == null || s.packageName.isEmpty()) continue;
            if (mSlots.containsKey(s.packageName)) continue;
            String appLabel = getAppLabel(s.packageName);
            doStartSlot(s.packageName, appLabel, s.toRect());
        }
    }

    // ── Auto-activate & pre-create ────────────────────────────────────────────

    /** Triggered on open when KEY_FISSION_AUTO_LAYOUT is true. Must run on mExec. */
    private void activateFavoriteLayout() {
        LayoutPreset fav = LayoutPrefs.getFavoriteLayout(mAppCtx);
        if (fav == null) { safeRun(() -> setStatus(null)); return; }
        mActiveLayout = fav;
        safeRun(this::updateLayoutSelectorUi);
        try {
            doSwitchToLayout(fav);
        } catch (Exception e) {
            AppLogger.e(TAG, "activateFavoriteLayout failed", e);
            safeRun(() -> setStatus("Erreur auto-layout: " + e.getMessage()));
        }
    }

    /** Triggered on open when KEY_FISSION_PRECREATE_SLOTS is true. Must run on mExec. */
    private void precreateSlots(LayoutPreset layout) {
        safeRun(() -> setStatus(getString(R.string.fission_precreating_slots)));
        if (!ensureDaemon()) { safeRun(() -> setStatus(null)); return; }
        for (LayoutPreset.SlotDef s : layout.slots) {
            String key = (s.packageName != null && !s.packageName.isEmpty())
                    ? s.packageName : s.label;
            try {
                int id = FissionClient.attachSlot(mDaemonBinder, key, s.x, s.y, s.w, s.h);
                if (id > 0) AppLogger.i(TAG, "FISSION PRECREATE slot=" + key + " displayId=" + id);
            } catch (Exception e) {
                AppLogger.w(TAG, "precreateSlots failed for " + key + ": " + e.getMessage());
            }
        }
        safeRun(() -> setStatus(null));
    }

    private String getAppLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }
}
