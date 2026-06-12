package com.byd.dashcast.fission;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.R;
import com.byd.dashcast.proxy.daemon.BinderParcelable;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FissionLayoutEditorActivity extends Activity {

    private static final String TAG = "FissionLayoutEditor";

    private IBinder mDaemonBinder;

    private ClusterCanvasView mCanvas;
    private LinearLayout      mLlLayouts;

    private List<LayoutPreset> mPresets  = new ArrayList<>();
    private LayoutPreset       mEditing;
    private String             mActiveId;

    private final ExecutorService mExec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_fission_layout_editor);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_fission_editor);
        toolbar.setNavigationOnClickListener(v -> finish());

        mCanvas    = findViewById(R.id.fission_canvas);
        mLlLayouts = findViewById(R.id.ll_fission_layouts);

        MaterialButton btnClear = findViewById(R.id.btn_fission_clear);
        MaterialButton btnSave  = findViewById(R.id.btn_fission_save);

        mEditing = new LayoutPreset("Nouveau layout");
        mCanvas.setSlots(mEditing.slots);

        mCanvas.setOnZoneDrawnListener((x, y, w, h) -> showAddZoneDialog(x, y, w, h));
        mCanvas.setOnZoneLongPressListener(idx -> {
            new AlertDialog.Builder(this)
                    .setTitle("Supprimer la zone ?")
                    .setPositiveButton("Supprimer", (d, w2) -> {
                        mEditing.slots.remove(idx);
                        mCanvas.invalidate();
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        });

        btnClear.setOnClickListener(v -> { mEditing.slots.clear(); mCanvas.invalidate(); });
        btnSave.setOnClickListener(v -> {
            if (mEditing.slots.isEmpty()) {
                Toast.makeText(this, "Dessinez au moins une zone", Toast.LENGTH_SHORT).show();
                return;
            }
            showSaveDialog();
        });

        // Receive daemon binder passed via Intent extra from FissionActivity.
        BinderParcelable bp = getIntent().getParcelableExtra(FissionActivity.EXTRA_DAEMON_BINDER);
        if (bp != null) mDaemonBinder = bp.binder;

        mPresets  = LayoutPrefs.load(this);
        mActiveId = LayoutPrefs.getFavoriteId(this);
        refreshLayoutList();
    }

    private void showAddZoneDialog(int x, int y, int w, int h) {
        EditText et = new EditText(this);
        et.setHint("Nom de la zone");
        et.setText(mEditing.nextSlotLabel());
        et.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Nouvelle zone — " + w + "×" + h + " px")
                .setView(et)
                .setPositiveButton("Ajouter", (d, which) -> {
                    String label = et.getText().toString().trim();
                    if (label.isEmpty()) label = mEditing.nextSlotLabel();
                    mEditing.slots.add(new LayoutPreset.SlotDef(label, x, y, w, h));
                    mCanvas.invalidate();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showSaveDialog() {
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
                    mEditing = new LayoutPreset("Nouveau layout");
                    mCanvas.setSlots(mEditing.slots);
                    refreshLayoutList();
                    Toast.makeText(this, "Layout enregistré", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void activateLayout(LayoutPreset preset) {
        IBinder binder = mDaemonBinder;
        if (binder == null) {
            Toast.makeText(this, "Daemon non connecté — lancez d'abord une projection Fission",
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
                LayoutPrefs.setFavoriteId(FissionLayoutEditorActivity.this, mActiveId);
                runOnUiThread(() -> {
                    refreshLayoutList();
                    Toast.makeText(this,
                            ok ? preset.name + " activé ✓"
                               : preset.name + " activé (certains slots ont échoué)",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                AppLogger.e(TAG, "activateLayout error", e);
                runOnUiThread(() -> Toast.makeText(this, "Erreur: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void deactivateLayout() {
        IBinder binder = mDaemonBinder;
        mActiveId = null;
        for (LayoutPreset p : mPresets) for (LayoutPreset.SlotDef s : p.slots) s.displayId = -1;
        LayoutPrefs.setFavoriteId(this, null);
        refreshLayoutList();
        if (binder != null) {
            mExec.execute(() -> {
                try { FissionClient.deactivateLayout(binder); } catch (Exception ignored) {}
            });
        }
        Toast.makeText(this, "Mode libre activé", Toast.LENGTH_SHORT).show();
    }

    private void refreshLayoutList() {
        if (mLlLayouts == null) return;
        mLlLayouts.removeAllViews();

        if (mPresets.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Aucun layout sauvegardé\nDessinez des zones ci-dessus et enregistrez.");
            tv.setPadding(16, 16, 16, 16);
            mLlLayouts.addView(tv);
            return;
        }

        for (LayoutPreset preset : mPresets) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_fission_layout_preset,
                    mLlLayouts, false);

            TextView       tvName     = row.findViewById(R.id.tv_fission_preset_name);
            TextView       tvSlots    = row.findViewById(R.id.tv_fission_preset_slots);
            MaterialButton btnActivate = row.findViewById(R.id.btn_fission_activate);
            MaterialButton btnEdit     = row.findViewById(R.id.btn_fission_edit);
            MaterialButton btnDelete   = row.findViewById(R.id.btn_fission_delete);
            LinearLayout   llActive    = row.findViewById(R.id.ll_fission_active_slots);

            boolean isActive = mActiveId != null && mActiveId.equals(preset.id);
            tvName.setText(preset.name + (isActive ? "  ●" : ""));
            tvSlots.setText(preset.slots.size() + " zone(s)");

            btnActivate.setText(isActive ? "Désactiver" : "Activer");
            btnActivate.setOnClickListener(v -> {
                if (isActive) deactivateLayout();
                else activateLayout(preset);
            });

            btnEdit.setOnClickListener(v -> {
                mEditing = new LayoutPreset(preset.name);
                mEditing.id = preset.id;
                for (LayoutPreset.SlotDef s : preset.slots)
                    mEditing.slots.add(new LayoutPreset.SlotDef(s.label, s.x, s.y, s.w, s.h));
                mCanvas.setSlots(mEditing.slots);
                mCanvas.invalidate();
                Toast.makeText(this, "Modifiez les zones puis enregistrez", Toast.LENGTH_SHORT).show();
            });

            btnDelete.setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle("Supprimer " + preset.name + " ?")
                            .setPositiveButton("Supprimer", (d, w) -> {
                                if (preset.id.equals(mActiveId)) deactivateLayout();
                                mPresets.remove(preset);
                                LayoutPrefs.save(this, mPresets);
                                refreshLayoutList();
                            })
                            .setNegativeButton("Annuler", null)
                            .show());

            if (isActive && llActive != null) {
                llActive.setVisibility(View.VISIBLE);
                for (LayoutPreset.SlotDef s : preset.slots) {
                    String label = s.label + "  " + s.w + "×" + s.h
                            + (s.displayId >= 0 ? "  [VD:" + s.displayId + "]" : "  [ERREUR]");
                    TextView tvSlot = new TextView(this);
                    tvSlot.setText(label);
                    tvSlot.setPadding(8, 4, 8, 4);
                    llActive.addView(tvSlot);
                }
            }

            mLlLayouts.addView(row);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExec.shutdown();
    }
}
