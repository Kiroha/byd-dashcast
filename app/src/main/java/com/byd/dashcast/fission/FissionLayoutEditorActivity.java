package com.byd.dashcast.fission;
import android.annotation.SuppressLint;

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

        mEditing = new LayoutPreset(getString(R.string.lm_new_layout_name));
        mCanvas.setSlots(mEditing.slots);

        mCanvas.setOnZoneDrawnListener((x, y, w, h) -> showAddZoneDialog(x, y, w, h));
        mCanvas.setOnZoneLongPressListener(idx -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.lm_zone_delete_title)
                    .setPositiveButton(R.string.lm_action_delete, (d, w2) -> {
                        mEditing.slots.remove(idx);
                        mCanvas.invalidate();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        btnClear.setOnClickListener(v -> { mEditing.slots.clear(); mCanvas.invalidate(); });
        btnSave.setOnClickListener(v -> {
            if (mEditing.slots.isEmpty()) {
                Toast.makeText(this, R.string.lm_draw_zone_first, Toast.LENGTH_SHORT).show();
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
        et.setHint(R.string.lm_zone_name_hint);
        et.setText(mEditing.nextSlotLabel());
        et.selectAll();
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.lm_zone_new_title_fmt, w, h))
                .setView(et)
                .setPositiveButton(R.string.lm_action_add, (d, which) -> {
                    String label = et.getText().toString().trim();
                    if (label.isEmpty()) label = mEditing.nextSlotLabel();
                    mEditing.slots.add(new LayoutPreset.SlotDef(label, x, y, w, h));
                    mCanvas.invalidate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSaveDialog() {
        EditText et = new EditText(this);
        et.setHint(R.string.lm_layout_name_hint);
        et.setText(mEditing.name);
        et.selectAll();
        new AlertDialog.Builder(this)
                .setTitle(R.string.lm_layout_save_title)
                .setView(et)
                .setPositiveButton(R.string.lm_action_save, (d, which) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) name = getString(R.string.lm_layout_default_name_fmt, mPresets.size() + 1);
                    mEditing.name = name;
                    boolean replaced = false;
                    for (int i = 0; i < mPresets.size(); i++) {
                        if (mPresets.get(i).id.equals(mEditing.id)) {
                            mPresets.set(i, mEditing); replaced = true; break;
                        }
                    }
                    if (!replaced) mPresets.add(mEditing);
                    LayoutPrefs.save(this, mPresets);
                    mEditing = new LayoutPreset(getString(R.string.lm_new_layout_name));
                    mCanvas.setSlots(mEditing.slots);
                    refreshLayoutList();
                    Toast.makeText(this, R.string.lm_layout_saved_toast, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void activateLayout(LayoutPreset preset) {
        IBinder binder = mDaemonBinder;
        if (binder == null) {
            Toast.makeText(this, getString(R.string.lm_daemon_not_connected),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.lm_activating_fmt, preset.name), Toast.LENGTH_SHORT).show();
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
                            ok ? getString(R.string.lm_activated_ok_fmt, preset.name)
                               : getString(R.string.lm_activated_partial_fmt, preset.name),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                AppLogger.e(TAG, "activateLayout error", e);
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.lm_error_fmt, e.getMessage()),
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
        Toast.makeText(this, R.string.lm_free_mode_toast, Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("SetTextI18n") // technical geometry/IDs, locale-neutral
    private void refreshLayoutList() {
        if (mLlLayouts == null) return;
        mLlLayouts.removeAllViews();

        if (mPresets.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.lm_no_saved_layouts);
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
            tvSlots.setText(getString(R.string.fission_zone_count, preset.slots.size()));

            btnActivate.setText(isActive ? R.string.layout_preset_deactivate : R.string.layout_preset_activate);
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
                Toast.makeText(this, R.string.lm_edit_zones_hint, Toast.LENGTH_SHORT).show();
            });

            btnDelete.setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.lm_delete_confirm_fmt, preset.name))
                            .setPositiveButton(R.string.lm_action_delete, (d, w) -> {
                                if (preset.id.equals(mActiveId)) deactivateLayout();
                                mPresets.remove(preset);
                                LayoutPrefs.save(this, mPresets);
                                refreshLayoutList();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
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
