package com.byd.dashcast.fission;
import android.annotation.SuppressLint;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.ui.diag.DiagActivity;
import com.byd.dashcast.ui.hotspot.HotspotActivity;
import com.byd.dashcast.ui.log.LogActivity;
import com.byd.dashcast.MainActivity;
import com.byd.dashcast.ui.nav.NavRailHotspot;
import com.byd.dashcast.R;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.ui.diag.SysInfoActivity;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LayoutManagerActivity extends Activity {

    private static final String TAG           = "LayoutManager";
    private static final int    PANEL_WIDTH_DP = 300;
    private static final int    ANIM_DURATION  = 220;

    private ClusterCanvasView   mCanvas;
    private RecyclerView        mRecycler;
    private LayoutPresetAdapter mAdapter;
    private HorizontalScrollView mHsvChips;
    private LinearLayout        mChipsContainer;
    private LinearLayout        mPanelContainer;
    private ImageView           mIvToggle;
    private TextView            mTvCanvasTitle;
    private TextView            mTvToolbarName;

    private List<LayoutPreset>  mPresets  = new ArrayList<>();
    private LayoutPreset        mEditing;
    private String              mActiveId;
    private boolean             mPanelVisible = true;

    private final ExecutorService mExec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_layout_manager);

        mCanvas         = findViewById(R.id.lm_canvas);
        mRecycler       = findViewById(R.id.lm_recycler);
        mHsvChips       = findViewById(R.id.lm_chips_scroll);
        mPanelContainer = findViewById(R.id.lm_panel_container);
        mIvToggle       = findViewById(R.id.lm_toggle_icon);
        mTvCanvasTitle  = findViewById(R.id.lm_canvas_title);
        mTvToolbarName  = findViewById(R.id.lm_canvas_toolbar_name);

        mChipsContainer = mHsvChips != null
                ? (LinearLayout) mHsvChips.getChildAt(0) : null;

        // Load saved data
        mPresets  = LayoutPrefs.load(this);
        mActiveId = LayoutPrefs.getFavoriteId(this);

        // RecyclerView
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new LayoutPresetAdapter(mPresets, mActiveId, new LayoutPresetAdapter.Callbacks() {
            @Override public void onSelect(LayoutPreset p)   { loadIntoCanvas(p); }
            @Override public void onEdit(LayoutPreset p)     { loadIntoCanvas(p); if (mPanelVisible) collapsePanel(); }
            @Override public void onActivate(LayoutPreset p) { activateLayout(p); }
            @Override public void onDeactivate()             { deactivateLayout(); }
            @Override public void onDelete(LayoutPreset p)   { deleteLayout(p); }
        });
        mRecycler.setAdapter(mAdapter);

        // Canvas listeners
        mCanvas.setOnZoneDrawnListener((x, y, w, h) -> showAddZoneDialog(x, y, w, h));
        mCanvas.setOnZoneLongPressListener(idx -> {
            if (mEditing == null || idx < 0 || idx >= mEditing.slots.size()) return;
            String label = mEditing.slots.get(idx).label;
            new AlertDialog.Builder(this)
                    .setTitle("Supprimer \"" + label + "\" ?")
                    .setPositiveButton("Supprimer", (d, w2) -> {
                        mEditing.slots.remove(idx);
                        mCanvas.invalidate();
                        refreshChips();
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        });

        // Toggle panel
        View btnToggle = findViewById(R.id.lm_toggle_btn);
        if (btnToggle != null) btnToggle.setOnClickListener(v -> togglePanel());

        // Toolbar buttons
        View btnNew = findViewById(R.id.lm_btn_new_layout);
        if (btnNew != null) btnNew.setOnClickListener(v -> startNewLayout());

        View btnClear = findViewById(R.id.lm_btn_clear);
        if (btnClear != null) btnClear.setOnClickListener(v -> {
            if (mEditing != null) {
                mEditing.slots.clear();
                mCanvas.invalidate();
                refreshChips();
            }
        });

        View btnSave = findViewById(R.id.lm_btn_save);
        if (btnSave != null) btnSave.setOnClickListener(v -> saveLayout());

        View btnSetFavorite = findViewById(R.id.lm_btn_set_favorite);
        if (btnSetFavorite != null) btnSetFavorite.setOnClickListener(v -> setCurrentLayoutAsFavorite());

        // Initial state
        if (!mPresets.isEmpty()) loadIntoCanvas(mPresets.get(0));
        else startNewLayout();

        // Nav rail wiring
        wireNavRail();
    }

    private void wireNavRail() {
        View navApps     = findViewById(R.id.nav_apps_lm);
        View navSettings = findViewById(R.id.nav_settings_lm);
        View navDiag     = findViewById(R.id.nav_diag_lm);
        View navSysinfo  = findViewById(R.id.nav_sysinfo_lm);
        View navLog      = findViewById(R.id.nav_log_lm);
        View navLogo     = findViewById(R.id.iv_nav_logo_lm);

        if (navApps     != null) navApps.setOnClickListener(v -> nav(MainActivity.class));
        if (navSettings != null) navSettings.setOnClickListener(v -> nav(SettingsActivity.class));
        if (navDiag     != null) navDiag.setOnClickListener(v -> nav(DiagActivity.class));
        if (navSysinfo  != null) navSysinfo.setOnClickListener(v -> nav(SysInfoActivity.class));
        if (navLog      != null) navLog.setOnClickListener(v -> nav(LogActivity.class));
        if (navLogo     != null) navLogo.setOnClickListener(v -> nav(MainActivity.class));

        NavRailHotspot.apply(this, R.id.nav_hotspot_lm, true);
    }

    private void nav(Class<? extends Activity> dest) {
        startActivity(new Intent(this, dest));
        finish();
    }

    // ── Canvas state ──────────────────────────────────────────────────────────

    private void loadIntoCanvas(LayoutPreset preset) {
        mEditing = new LayoutPreset(preset.name);
        mEditing.id = preset.id;
        for (LayoutPreset.SlotDef s : preset.slots)
            mEditing.slots.add(s.copy());
        mCanvas.setSlots(mEditing.slots);
        mCanvas.invalidate();
        setCanvasTitle(preset.name);
        refreshChips();
        mAdapter.setSelected(preset.id);
    }

    private void startNewLayout() {
        mEditing = new LayoutPreset("Nouveau layout");
        mCanvas.setSlots(mEditing.slots);
        mCanvas.invalidate();
        setCanvasTitle("Nouveau layout");
        refreshChips();
        mAdapter.setSelected(null);
    }

    private void setCanvasTitle(String name) {
        if (mTvCanvasTitle  != null) mTvCanvasTitle.setText(name);
        if (mTvToolbarName  != null) mTvToolbarName.setText(name);
    }

    @SuppressLint("SetTextI18n") // technical geometry/IDs, locale-neutral
    private void refreshChips() {
        if (mChipsContainer == null) return;
        mChipsContainer.removeAllViews();
        if (mEditing == null || mEditing.slots.isEmpty()) return;

        for (int i = 0; i < mEditing.slots.size(); i++) {
            LayoutPreset.SlotDef s = mEditing.slots.get(i);
            final int idx = i;

            TextView chip = new TextView(this);
            String pkgSuffix = (s.packageName != null && !s.packageName.isEmpty())
                    ? "  🔗 " + s.packageName : "";
            chip.setText(s.label + "  " + s.w + "×" + s.h + " @ (" + s.x + "," + s.y + ")" + pkgSuffix + "  ✕");
            chip.setTextSize(12f);
            chip.setTextColor(Color.parseColor("#2A5EA8"));
            chip.setBackgroundResource(R.drawable.bg_log_filter);
            int ph = dpToPx(10), pv = dpToPx(5);
            chip.setPadding(ph, pv, ph, pv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dpToPx(6));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                mEditing.slots.remove(idx);
                mCanvas.invalidate();
                refreshChips();
            });
            mChipsContainer.addView(chip);
        }
    }

    private void showAddZoneDialog(int x, int y, int w, int h) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = dpToPx(16);
        form.setPadding(p, p, p, 0);

        EditText etName = addField(form, "Nom de la zone", mEditing.nextSlotLabel());
        addDivider(form, "Position & dimensions");
        EditText etX = addField(form, "X (px)", String.valueOf(x));
        EditText etY = addField(form, "Y (px)", String.valueOf(y));
        EditText etW = addField(form, "Largeur (px)", String.valueOf(w));
        EditText etH = addField(form, "Hauteur (px)", String.valueOf(h));

        addDivider(form, getString(R.string.fission_slot_pick_pkg));
        final String[] pickedPkg = {null};
        TextView tvBound = new TextView(this);
        tvBound.setText(getString(R.string.fission_slot_pkg_none));
        tvBound.setTextSize(13f);
        tvBound.setTextColor(Color.parseColor("#74777F"));
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dpToPx(4);
        form.addView(tvBound, tvLp);

        MaterialButton btnPickPkg = new MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnPickPkg.setText(getString(R.string.fission_slot_pick_pkg));
        btnPickPkg.setTextSize(13f);
        btnPickPkg.setInsetTop(0); btnPickPkg.setInsetBottom(0);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(36));
        btnLp.topMargin = dpToPx(6);
        form.addView(btnPickPkg, btnLp);
        btnPickPkg.setOnClickListener(v ->
                showPackagePickerForZone(tvBound, pickedPkg));

        etName.selectAll();
        // The form is taller than the dialog's max height on the 1920x720 head unit
        // (5 fields + binding section), and the soft keyboard shrinks it further —
        // without a ScrollView the "link an app" button is clipped out of reach.
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.setFillViewport(true);
        scroller.addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Nouvelle zone — " + w + "×" + h + " px")
                .setView(scroller)
                .setPositiveButton("Ajouter", (d, which) -> {
                    String label = etName.getText().toString().trim();
                    if (label.isEmpty()) label = mEditing.nextSlotLabel();
                    int fx = parseInt(etX, x), fy = parseInt(etY, y);
                    int fw = parseInt(etW, w), fh = parseInt(etH, h);
                    LayoutPreset.SlotDef slot = new LayoutPreset.SlotDef(label, fx, fy, fw, fh);
                    slot.packageName = (pickedPkg[0] != null && !pickedPkg[0].isEmpty())
                            ? pickedPkg[0] : null;
                    mEditing.slots.add(slot);
                    mCanvas.invalidate();
                    refreshChips();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Opens an app-picker dialog and writes the selected package into {@code pickedPkg[0]}. */
    private void showPackagePickerForZone(TextView tvBound, String[] pickedPkg) {
        mExec.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos;
            try { infos = pm.queryIntentActivities(main, 0); }
            catch (Exception e) { infos = new ArrayList<>(); }
            final Map<String, String> pkgToLabel = new LinkedHashMap<>();
            String selfPkg = getPackageName();
            for (ResolveInfo ri : infos) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(selfPkg) || pkgToLabel.containsKey(pkg)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                pkgToLabel.put(pkg, lbl != null ? lbl.toString() : pkg);
            }
            List<Map.Entry<String, String>> sorted = new ArrayList<>(pkgToLabel.entrySet());
            Collections.sort(sorted, (a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
            final String[] pkgs   = new String[sorted.size()];
            final String[] labels = new String[sorted.size()];
            for (int i = 0; i < sorted.size(); i++) {
                pkgs[i]   = sorted.get(i).getKey();
                labels[i] = sorted.get(i).getValue() + "  —  " + pkgs[i];
            }
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.fission_slot_pick_pkg))
                    .setItems(labels, (d2, idx) -> {
                        pickedPkg[0] = pkgs[idx];
                        tvBound.setText(getString(R.string.fission_slot_zone_bound_fmt, labels[idx]));
                    })
                    .setNeutralButton(getString(R.string.fission_slot_pkg_none), (d2, w2) -> {
                        pickedPkg[0] = null;
                        tvBound.setText(getString(R.string.fission_slot_pkg_none));
                    })
                    .setNegativeButton("Annuler", null)
                    .show());
        });
    }

    private EditText addField(LinearLayout parent, String hint, String value) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setTextSize(12f);
        label.setTextColor(Color.parseColor("#43474E"));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dpToPx(8);
        parent.addView(label, llp);

        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        parent.addView(et);
        return et;
    }

    private void addDivider(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11f);
        tv.setTextColor(Color.parseColor("#74777F"));
        tv.setAllCaps(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(14);
        parent.addView(tv, lp);
    }

    private int parseInt(EditText et, int fallback) {
        try { return Integer.parseInt(et.getText().toString().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private void saveLayout() {
        if (mEditing == null || mEditing.slots.isEmpty()) {
            Toast.makeText(this, "Dessinez au moins une zone", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText et = new EditText(this);
        et.setHint("Nom du layout");
        et.setText(mEditing.name);
        et.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Enregistrer le layout")
                .setView(et)
                .setPositiveButton("Enregistrer", (d, which) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) name = "Layout " + (mPresets.size() + 1);
                    mEditing.name = name;
                    boolean replaced = false;
                    for (int i = 0; i < mPresets.size(); i++) {
                        if (mPresets.get(i).id.equals(mEditing.id)) {
                            mPresets.set(i, mEditing); replaced = true; break;
                        }
                    }
                    if (!replaced) mPresets.add(mEditing);
                    LayoutPrefs.save(this, mPresets);
                    mAdapter.update(mPresets, mActiveId);
                    setCanvasTitle(mEditing.name);
                    Toast.makeText(this, "Layout enregistré ✓", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void deleteLayout(LayoutPreset preset) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer « " + preset.name + " » ?")
                .setPositiveButton("Supprimer", (d, w) -> {
                    if (preset.id.equals(mActiveId)) deactivateLayout();
                    mPresets.remove(preset);
                    LayoutPrefs.save(this, mPresets);
                    mAdapter.update(mPresets, mActiveId);
                    if (mEditing != null && mEditing.id.equals(preset.id)) startNewLayout();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void activateLayout(LayoutPreset preset) {
        IBinder binder = FissionClient.getBinderFromServiceManager();
        if (binder == null) {
            Toast.makeText(this,
                    "Daemon non connecté — lancez d'abord une projection Fission",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Activation de " + preset.name + "…", Toast.LENGTH_SHORT).show();
        mExec.execute(() -> {
            if (mActiveId != null && !mActiveId.equals(preset.id)) {
                try { FissionClient.deactivateLayout(binder); } catch (Exception ignored) {}
            }
            try {
                boolean ok = FissionClient.activateLayout(binder, preset);
                mActiveId = preset.id;
                LayoutPrefs.setFavoriteId(LayoutManagerActivity.this, mActiveId);
                runOnUiThread(() -> {
                    mAdapter.update(mPresets, mActiveId);
                    Toast.makeText(this,
                            ok ? preset.name + " activé ✓"
                               : preset.name + " activé (certains slots ont échoué)",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                AppLogger.e(TAG, "activateLayout failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Erreur: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void deactivateLayout() {
        IBinder binder = FissionClient.getBinderFromServiceManager();
        mActiveId = null;
        for (LayoutPreset p : mPresets) for (LayoutPreset.SlotDef s : p.slots) s.displayId = -1;
        LayoutPrefs.setFavoriteId(this, null);
        mAdapter.update(mPresets, mActiveId);
        if (binder != null) {
            mExec.execute(() -> {
                try { FissionClient.deactivateLayout(binder); } catch (Exception ignored) {}
            });
        }
        Toast.makeText(this, "Mode libre activé", Toast.LENGTH_SHORT).show();
    }

    private void setCurrentLayoutAsFavorite() {
        if (mEditing == null) {
            Toast.makeText(this, "Aucun layout chargé", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean alreadySaved = false;
        for (LayoutPreset p : mPresets) {
            if (p.id.equals(mEditing.id)) { alreadySaved = true; break; }
        }
        if (!alreadySaved) {
            Toast.makeText(this,
                    "Enregistrez d'abord le layout avant de le définir en favori",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        LayoutPrefs.setFavoriteId(this, mEditing.id);
        Toast.makeText(this,
                getString(R.string.fission_layout_favorite_set_toast), Toast.LENGTH_SHORT).show();
    }

    // ── Panel toggle animation ─────────────────────────────────────────────────

    private void togglePanel() {
        if (mPanelVisible) collapsePanel();
        else expandPanel();
    }

    private void collapsePanel() {
        if (!mPanelVisible || mPanelContainer == null) return;
        int startW = dpToPx(PANEL_WIDTH_DP);
        ValueAnimator anim = ValueAnimator.ofInt(startW, 0);
        anim.setDuration(ANIM_DURATION);
        anim.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = mPanelContainer.getLayoutParams();
            lp.width = (int) a.getAnimatedValue();
            mPanelContainer.setLayoutParams(lp);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                mPanelContainer.setVisibility(View.GONE);
            }
        });
        anim.start();
        if (mIvToggle != null) mIvToggle.setImageResource(R.drawable.ic_arrow_forward);
        mPanelVisible = false;
    }

    private void expandPanel() {
        if (mPanelVisible || mPanelContainer == null) return;
        mPanelContainer.setVisibility(View.VISIBLE);
        int targetW = dpToPx(PANEL_WIDTH_DP);
        ValueAnimator anim = ValueAnimator.ofInt(0, targetW);
        anim.setDuration(ANIM_DURATION);
        anim.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = mPanelContainer.getLayoutParams();
            lp.width = (int) a.getAnimatedValue();
            mPanelContainer.setLayoutParams(lp);
        });
        anim.start();
        if (mIvToggle != null) mIvToggle.setImageResource(R.drawable.ic_arrow_back);
        mPanelVisible = true;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExec.shutdown();
    }
}
