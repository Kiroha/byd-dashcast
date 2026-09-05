package com.byd.dashcast.fission

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.byd.dashcast.MainActivity
import com.byd.dashcast.R
import com.byd.dashcast.proxy.DaemonConfig
import com.byd.dashcast.ui.diag.DiagActivity
import com.byd.dashcast.ui.diag.SysInfoActivity
import com.byd.dashcast.ui.log.LogActivity
import com.byd.dashcast.ui.nav.NavRailHotspot
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger
import com.google.android.material.button.MaterialButton

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class LayoutManagerActivity : Activity() {

    private var mCanvas: ClusterCanvasView? = null
    private var mRecycler: RecyclerView? = null
    private var mAdapter: LayoutPresetAdapter? = null
    private var mHsvChips: HorizontalScrollView? = null
    private var mChipsContainer: LinearLayout? = null
    private var mPanelContainer: LinearLayout? = null
    private var mIvToggle: ImageView? = null
    private var mTvCanvasTitle: TextView? = null
    private var mTvToolbarName: TextView? = null

    private var mPresets: MutableList<LayoutPreset> = ArrayList()
    private var mEditing: LayoutPreset? = null
    private var mActiveId: String? = null
    private var mPanelVisible = true

    private val mExec: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_layout_manager)

        mCanvas = findViewById(R.id.lm_canvas)
        mRecycler = findViewById(R.id.lm_recycler)
        mHsvChips = findViewById(R.id.lm_chips_scroll)
        mPanelContainer = findViewById(R.id.lm_panel_container)
        mIvToggle = findViewById(R.id.lm_toggle_icon)
        mTvCanvasTitle = findViewById(R.id.lm_canvas_title)
        mTvToolbarName = findViewById(R.id.lm_canvas_toolbar_name)

        mChipsContainer = mHsvChips?.getChildAt(0) as LinearLayout?

        // Load saved data
        val loaded = LayoutPrefs.loadResult(this)
        mPresets = ArrayList(loaded.presets)
        mActiveId = LayoutPrefs.getValidFavoriteId(this, mPresets)
        if (loaded.status == LayoutPrefs.LoadStatus.CORRUPT
                || loaded.status == LayoutPrefs.LoadStatus.STORAGE_ERROR) {
            Toast.makeText(this, R.string.lm_layout_load_failed, Toast.LENGTH_LONG).show()
        }

        // RecyclerView
        mRecycler?.layoutManager = LinearLayoutManager(this)
        val adapter = LayoutPresetAdapter(mPresets, mActiveId, object : LayoutPresetAdapter.Callbacks {
            override fun onSelect(p: LayoutPreset) { loadIntoCanvas(p) }
            override fun onEdit(p: LayoutPreset) { loadIntoCanvas(p); if (mPanelVisible) collapsePanel() }
            override fun onActivate(p: LayoutPreset) { activateLayout(p) }
            override fun onDeactivate() { deactivateLayout() }
            override fun onDelete(p: LayoutPreset) { deleteLayout(p) }
        })
        mAdapter = adapter
        mRecycler?.adapter = adapter

        // Canvas listeners
        mCanvas?.setOnZoneDrawnListener { x, y, w, h -> showZoneDialog(-1, x, y, w, h) }
        mCanvas?.setOnZoneTapListener { idx ->
            val editing = mEditing
            if (editing == null || idx < 0 || idx >= editing.slots.size) return@setOnZoneTapListener
            val s = editing.slots[idx]
            showZoneDialog(idx, s.x, s.y, s.w, s.h)
        }
        mCanvas?.setOnZoneLongPressListener { idx ->
            val editing = mEditing
            if (editing == null || idx < 0 || idx >= editing.slots.size) {
                return@setOnZoneLongPressListener
            }
            val label = editing.slots[idx].label
            AlertDialog.Builder(this)
                    .setTitle(getString(R.string.lm_delete_confirm_fmt, label))
                    .setPositiveButton(R.string.lm_action_delete) { _, _ ->
                        editing.slots.removeAt(idx)
                        mCanvas?.invalidate()
                        refreshChips()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
        }

        // Toggle panel
        findViewById<View?>(R.id.lm_toggle_btn)?.setOnClickListener { togglePanel() }

        // Toolbar buttons
        findViewById<View?>(R.id.lm_btn_new_layout)?.setOnClickListener { startNewLayout() }

        findViewById<View?>(R.id.lm_btn_clear)?.setOnClickListener {
            val editing = mEditing
            if (editing != null) {
                editing.slots.clear()
                mCanvas?.invalidate()
                refreshChips()
            }
        }

        findViewById<View?>(R.id.lm_btn_save)?.setOnClickListener { saveLayout() }

        findViewById<View?>(R.id.lm_btn_set_favorite)?.setOnClickListener {
            setCurrentLayoutAsFavorite()
        }

        // Initial state
        if (mPresets.isNotEmpty()) loadIntoCanvas(mPresets[0])
        else startNewLayout()

        // Nav rail wiring
        wireNavRail()
    }

    private fun wireNavRail() {
        findViewById<View?>(R.id.nav_apps_lm)?.setOnClickListener { nav(MainActivity::class.java) }
        findViewById<View?>(R.id.nav_settings_lm)?.setOnClickListener { nav(SettingsActivity::class.java) }
        findViewById<View?>(R.id.nav_diag_lm)?.setOnClickListener { nav(DiagActivity::class.java) }
        findViewById<View?>(R.id.nav_sysinfo_lm)?.setOnClickListener { nav(SysInfoActivity::class.java) }
        findViewById<View?>(R.id.nav_log_lm)?.setOnClickListener { nav(LogActivity::class.java) }
        findViewById<View?>(R.id.iv_nav_logo_lm)?.setOnClickListener { nav(MainActivity::class.java) }

        NavRailHotspot.apply(this, R.id.nav_hotspot_lm, true)
    }

    private fun nav(dest: Class<out Activity>) {
        startActivity(Intent(this, dest))
        finish()
    }

    // ── Canvas state ──────────────────────────────────────────────────────────

    private fun loadIntoCanvas(preset: LayoutPreset) {
        val editing = LayoutPreset(preset.name)
        editing.id = preset.id
        for (s in preset.slots) editing.slots.add(s.copy())
        mEditing = editing
        mCanvas?.setSlots(editing.slots)
        mCanvas?.invalidate()
        setCanvasTitle(preset.name)
        refreshChips()
        mAdapter?.setSelected(preset.id)
    }

    private fun startNewLayout() {
        val editing = LayoutPreset(getString(R.string.lm_new_layout_name))
        mEditing = editing
        mCanvas?.setSlots(editing.slots)
        mCanvas?.invalidate()
        setCanvasTitle(getString(R.string.lm_new_layout_name))
        refreshChips()
        mAdapter?.setSelected(null)
    }

    private fun setCanvasTitle(name: String) {
        mTvCanvasTitle?.text = name
        mTvToolbarName?.text = name
    }

    @SuppressLint("SetTextI18n") // technical geometry/IDs, locale-neutral
    private fun refreshChips() {
        val container = mChipsContainer ?: return
        container.removeAllViews()
        val editing = mEditing
        if (editing == null || editing.slots.isEmpty()) return

        for (i in editing.slots.indices) {
            val s = editing.slots[i]
            val idx = i

            val chip = TextView(this)
            val pkgSuffix = if (!s.packageName.isNullOrEmpty()) "  🔗 " + s.packageName else ""
            chip.text = s.label + "  " + s.w + "×" + s.h + " @ (" + s.x + "," + s.y + ")" + pkgSuffix
            chip.textSize = 12f
            chip.setTextColor("#2A5EA8".toColorInt())
            chip.setBackgroundResource(R.drawable.bg_log_filter)
            val ph = dpToPx(10)
            val pv = dpToPx(5)
            chip.setPadding(ph, pv, ph, pv)
            val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dpToPx(6)
            chip.layoutParams = lp
            // Tapping a chip used to delete the slot instantly; it now opens the
            // edit dialog (rename / geometry / app binding / delete).
            chip.setOnClickListener {
                val current = mEditing ?: return@setOnClickListener
                if (idx >= current.slots.size) return@setOnClickListener
                val sd = current.slots[idx]
                showZoneDialog(idx, sd.x, sd.y, sd.w, sd.h)
            }
            container.addView(chip)
        }
    }

    /**
     * Zone create/edit dialog. `editIdx == -1` creates a new slot from the drawn rect;
     * `editIdx >= 0` edits the existing slot in place (rename, geometry, app binding) and offers
     * deletion via the neutral button.
     */
    private fun showZoneDialog(editIdx: Int, x: Int, y: Int, w: Int, h: Int) {
        val editing = mEditing ?: return
        val editMode = editIdx >= 0
        val existing: LayoutPreset.SlotDef? = if (editMode) editing.slots[editIdx] else null

        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        val p = dpToPx(16)
        form.setPadding(p, p, p, 0)

        val etName = addField(form, getString(R.string.lm_zone_name_hint),
                if (editMode) existing!!.label else editing.nextSlotLabel())
        addDivider(form, getString(R.string.lm_zone_position_section))
        val etX = addField(form, getString(R.string.lm_zone_x), x.toString())
        val etY = addField(form, getString(R.string.lm_zone_y), y.toString())
        val etW = addField(form, getString(R.string.lm_zone_width), w.toString())
        val etH = addField(form, getString(R.string.lm_zone_height), h.toString())

        addDivider(form, getString(R.string.fission_slot_pick_pkg))
        val pickedPkg = arrayOf<String?>(if (editMode) existing!!.packageName else null)
        val tvBound = TextView(this)
        tvBound.text = if (!pickedPkg[0].isNullOrEmpty())
            getString(R.string.fission_slot_zone_bound_fmt, pickedPkg[0])
        else
            getString(R.string.fission_slot_pkg_none)
        tvBound.textSize = 13f
        tvBound.setTextColor("#74777F".toColorInt())
        val tvLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tvLp.topMargin = dpToPx(4)
        form.addView(tvBound, tvLp)

        val btnPickPkg = MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btnPickPkg.text = getString(R.string.fission_slot_pick_pkg)
        btnPickPkg.textSize = 13f
        btnPickPkg.insetTop = 0
        btnPickPkg.insetBottom = 0
        val btnLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(36))
        btnLp.topMargin = dpToPx(6)
        form.addView(btnPickPkg, btnLp)
        btnPickPkg.setOnClickListener { showPackagePickerForZone(tvBound, pickedPkg) }

        if (!editMode) etName.selectAll()
        // The form is taller than the dialog's max height on the 1920x720 head unit
        // (5 fields + binding section), and the soft keyboard shrinks it further —
        // without a ScrollView the "link an app" button is clipped out of reach.
        val scroller = ScrollView(this)
        scroller.isFillViewport = true
        scroller.addView(form, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val builder = AlertDialog.Builder(this)
                .setTitle(if (editMode)
                    getString(R.string.lm_zone_edit_title_fmt, existing!!.label)
                else
                    getString(R.string.lm_zone_new_title_fmt, w, h))
                .setView(scroller)
                .setPositiveButton(if (editMode) getString(R.string.lm_action_save)
                                   else getString(R.string.lm_action_add)) { _, _ ->
                    var label = etName.text.toString().trim()
                    if (label.isEmpty())
                        label = if (editMode) existing!!.label else editing.nextSlotLabel()
                    val fx = parseInt(etX, x)
                    val fy = parseInt(etY, y)
                    val fw = parseInt(etW, w)
                    val fh = parseInt(etH, h)
                    val slot = if (editMode) existing!!
                               else LayoutPreset.SlotDef(label, fx, fy, fw, fh)
                    if (editMode) {
                        slot.label = label
                        slot.x = fx; slot.y = fy; slot.w = fw; slot.h = fh
                    }
                    slot.packageName = if (!pickedPkg[0].isNullOrEmpty()) pickedPkg[0] else null
                    if (!editMode) editing.slots.add(slot)
                    mCanvas?.invalidate()
                    refreshChips()
                }
                .setNegativeButton(android.R.string.cancel, null)
        if (editMode) {
            builder.setNeutralButton(R.string.lm_action_delete) { _, _ ->
                if (editIdx < editing.slots.size) editing.slots.removeAt(editIdx)
                mCanvas?.invalidate()
                refreshChips()
            }
        }
        builder.show()
    }

    /** Opens an app-picker dialog and writes the selected package into `pickedPkg[0]`. */
    @Suppress("DEPRECATION")
    private fun showPackagePickerForZone(tvBound: TextView, pickedPkg: Array<String?>) {
        mExec.execute {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN)
            main.addCategory(Intent.CATEGORY_LAUNCHER)
            val infos: List<ResolveInfo> = try {
                pm.queryIntentActivities(main, 0)
            } catch (e: Exception) {
                ArrayList()
            }
            val pkgToLabel = LinkedHashMap<String, String>()
            val selfPkg = packageName
            for (ri in infos) {
                val activityInfo = ri?.activityInfo ?: continue
                val pkg = activityInfo.packageName
                if (pkg == null || pkg == selfPkg || pkgToLabel.containsKey(pkg)) continue
                val lbl = ri.loadLabel(pm)
                pkgToLabel[pkg] = lbl?.toString() ?: pkg
            }
            val sorted = ArrayList(pkgToLabel.entries)
            sorted.sortWith { a, b -> a.value.compareTo(b.value, ignoreCase = true) }
            val pkgs = Array(sorted.size) { sorted[it].key }
            val labels = Array(sorted.size) { sorted[it].value + "  —  " + sorted[it].key }
            runOnUiThread {
                // Same guard activateLayout already carries, and for the same reason: this runnable
                // is posted from a background query of the package manager, which on a head unit
                // with a few hundred packages takes long enough for the user to leave. Showing a
                // dialog on a destroyed Activity throws
                // WindowManager$BadTokenException("Unable to add window — token is not valid"),
                // an uncaught RuntimeException on the main thread, and a crash here blanks the
                // driver's cluster with the rest of the process.
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                        .setTitle(getString(R.string.fission_slot_pick_pkg))
                        .setItems(labels) { _, idx ->
                            pickedPkg[0] = pkgs[idx]
                            tvBound.text = getString(
                                    R.string.fission_slot_zone_bound_fmt, labels[idx])
                        }
                        .setNeutralButton(getString(R.string.fission_slot_pkg_none)) { _, _ ->
                            pickedPkg[0] = null
                            tvBound.text = getString(R.string.fission_slot_pkg_none)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
            }
        }
    }

    private fun addField(parent: LinearLayout, hint: String, value: String): EditText {
        val label = TextView(this)
        label.text = hint
        label.textSize = 12f
        label.setTextColor("#43474E".toColorInt())
        val llp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        llp.topMargin = dpToPx(8)
        parent.addView(label, llp)

        val et = EditText(this)
        et.hint = hint
        et.setText(value)
        et.inputType = InputType.TYPE_CLASS_TEXT
        parent.addView(et)
        return et
    }

    private fun addDivider(parent: LinearLayout, text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 11f
        tv.setTextColor("#74777F".toColorInt())
        tv.isAllCaps = true
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dpToPx(14)
        parent.addView(tv, lp)
    }

    private fun parseInt(et: EditText, fallback: Int): Int {
        return try { et.text.toString().trim().toInt() }
        catch (e: NumberFormatException) { fallback }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private fun saveLayout() {
        val editing = mEditing
        if (editing == null || editing.slots.isEmpty()) {
            Toast.makeText(this, R.string.lm_draw_zone_first, Toast.LENGTH_SHORT).show()
            return
        }
        val et = EditText(this)
        et.setHint(R.string.lm_layout_name_hint)
        et.setText(editing.name)
        et.selectAll()
        AlertDialog.Builder(this)
                .setTitle(R.string.lm_layout_save_title)
                .setView(et)
                .setPositiveButton(R.string.lm_action_save) { _, _ ->
                    var name = et.text.toString().trim()
                    if (name.isEmpty()) {
                        name = getString(R.string.lm_layout_default_name_fmt, mPresets.size + 1)
                    }
                    editing.name = name
                    // Store a SNAPSHOT, not the live object. mEditing is what the canvas drags,
                    // so putting it in mPresets made the saved layout a window onto the editor:
                    // every later move was already "saved", the next LayoutPrefs.save wrote it to
                    // disk, and Cancel cancelled nothing. The canvas keeps the live object.
                    val snapshot = editing.copy()
                    val updated = ArrayList(mPresets)
                    var replaced = false
                    for (i in updated.indices) {
                        if (updated[i].id == snapshot.id) {
                            updated[i] = snapshot; replaced = true; break
                        }
                    }
                    if (!replaced) updated.add(snapshot)
                    if (!LayoutPrefs.save(this, updated)) {
                        Toast.makeText(this, R.string.lm_layout_save_failed, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    mPresets = updated
                    mAdapter?.update(mPresets, mActiveId)
                    setCanvasTitle(editing.name)
                    Toast.makeText(this, R.string.lm_layout_saved_toast, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun deleteLayout(preset: LayoutPreset) {
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.lm_delete_confirm_fmt, preset.name))
                .setPositiveButton(R.string.lm_action_delete) { _, _ ->
                    val wasActive = preset.id == mActiveId
                    val wasFavorite = preset.id == LayoutPrefs.getValidFavoriteId(this, mPresets)
                    val updated = ArrayList(mPresets)
                    updated.remove(preset)
                    val saved = if (wasFavorite)
                        LayoutPrefs.saveState(this, updated, null)
                    else
                        LayoutPrefs.save(this, updated)
                    if (!saved) {
                        Toast.makeText(this, R.string.lm_layout_save_failed, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    mPresets = updated
                    if (wasActive) deactivateLayout(false)
                    mAdapter?.update(mPresets, mActiveId)
                    if (mEditing?.id == preset.id) startNewLayout()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /**
     * Activates a layout through [FissionOrchestrator.activateLayoutManually], i.e. the same
     * sequence the auto-start path runs: cluster projection first, then the SurfaceDaemon is
     * *started* (not merely probed — this button used to stop at "daemon not connected"), then
     * one slot per zone keyed by package. The orchestrator launches the bound apps itself, so the
     * former separate launch pass is gone.
     *
     * Gated on the Layout-mode setting. This screen is reachable from the nav rail on every
     * activity with no runtime gate (`NavRailLayouts` — "Always visible"), and the button used to
     * be a no-op toast whenever the daemon was not already up. Now it drives the OEM cluster into
     * projection mode and spawns the uid-2000 daemon, which must not happen to someone who has
     * Layout mode switched off and merely tapped a leftover preset.
     */
    private fun activateLayout(preset: LayoutPreset) {
        if (!DaemonConfig.isFissionModeEnabled(this)) {
            // Journal it, in English. Without this line a capture from a tester who was asked to
            // "activate a layout and send a report" is indistinguishable from one where they
            // never tried — same absence of ATTACH_SLOT lines, no explanation, one wasted round
            // trip. The setting defaults to OFF and this screen is reachable with no gate.
            AppLogger.w(TAG, "activateLayout refused: Layouts mode is disabled in Settings")
            Toast.makeText(this, getString(R.string.lm_layout_mode_disabled), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.lm_activating_fmt, preset.name),
                Toast.LENGTH_SHORT).show()
        // Activation takes seconds, and the user is free to leave this screen while it runs. What
        // must survive that is the record of what is now on the cluster; only the view work must
        // not. Splitting them is why the app context is captured here.
        val appCtx: Context = applicationContext
        FissionOrchestrator.activateLayoutManually(this, preset) { ok, error ->
            var selectionSaved = true
            var runtimeKept = error == null
            if (error == null) {
                // Unchanged contract: the activated layout only becomes the favourite when the
                // activation actually ran to completion. It is written before the lifecycle guard
                // because the layout really IS running — a screen that closed mid-activation must
                // not leave the favourite pointing at the previous one.
                val selectionStatus = LayoutPrefs.setFavoriteIdIfPresentResult(appCtx, preset.id)
                selectionSaved = selectionStatus == LayoutPrefs.FavoriteWriteStatus.SAVED
                if (!selectionSaved) {
                    AppLogger.e(TAG, "activated layout but failed to persist favourite selection")
                    if (selectionStatus == LayoutPrefs.FavoriteWriteStatus.MISSING) {
                        AppLogger.w(TAG, "activated layout was deleted before completion; stopping it")
                        FissionOrchestrator.stopAutoOrchestrator(null)
                        runtimeKept = false
                    }
                }
            } else {
                // Journalled before the guard for the same reason: losing the failure line because
                // the user navigated away costs a diagnostic round trip with a tester.
                //
                // In English, with only the toast translated. Precisely: the "activateLayout
                // failed:" prefix and every ERR_* code are English and greppable corpus-wide,
                // and an exception carries its class name — also English. What can still be
                // localised is an exception's own MESSAGE, because doStartSlot throws
                // fo_err_attach_fmt. So classify on the prefix and the class name, never on the
                // message tail.
                AppLogger.e(TAG, "activateLayout failed: $error")
            }
            if (isFinishing || isDestroyed) return@activateLayoutManually  // from here down it is all UI
            if (error == null && runtimeKept) {
                mActiveId = preset.id
                mAdapter?.update(mPresets, mActiveId)
            }
            val text = if (error != null)
                activationErrorText(error, preset.name)
            else if (!selectionSaved)
                getString(R.string.lm_layout_selection_save_failed)
            else if (ok)
                getString(R.string.lm_activated_ok_fmt, preset.name)
            else
                getString(R.string.lm_activated_partial_fmt, preset.name)
            Toast.makeText(this, text,
                    if (error != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Maps a [FissionOrchestrator] `ERR_*` code to translated user text.
     *
     * The codes stay English so the journal is greppable; anything unrecognised is a raw exception
     * message and goes through the existing `lm_error_fmt` wrapper, which has always carried
     * untranslated exception text.
     *
     * Never returns `null`. An earlier version showed nothing for `ERR_BUSY` / `ERR_ABANDONED`,
     * reasoning that the user had caused both. That is wrong during a diagnostic campaign, and
     * wrong generally: the "Activating…" toast is SHORT and long gone by the time either lands, so
     * the button reads as dead and the tester stops rather than retrying — producing no capture at
     * all.
     */
    private fun activationErrorText(error: String, presetName: String): String {
        if (FissionOrchestrator.ERR_CLUSTER_TIMEOUT == error) {
            return getString(R.string.toast_activate_timeout)
        }
        if (FissionOrchestrator.ERR_PROJECTION_CONFLICT == error) {
            return getString(R.string.fission_conflict_title)
        }
        if (FissionOrchestrator.ERR_NO_DAEMON == error) {
            return getString(R.string.fo_err_daemon)
        }
        if (FissionOrchestrator.ERR_BUSY == error) {
            // Truthful, not an error: the activation they asked for IS still running.
            return getString(R.string.lm_activating_fmt, presetName)
        }
        return getString(R.string.lm_error_fmt, error)
    }

    private fun deactivateLayout() {
        deactivateLayout(true)
    }

    private fun deactivateLayout(persistSelection: Boolean): Boolean {
        if (persistSelection && !LayoutPrefs.setFavoriteId(this, null)) {
            Toast.makeText(this, R.string.lm_layout_selection_save_failed, Toast.LENGTH_LONG).show()
            return false
        }
        mActiveId = null
        for (p in mPresets) for (s in p.slots) s.displayId = -1
        mAdapter?.update(mPresets, mActiveId)
        // The global purge is part of the old owner's serialized teardown; activation stays gated
        // until it completes, so it cannot delete slots attached by a newer layout.
        FissionOrchestrator.stopAutoOrchestratorAndPurge(this, null)
        Toast.makeText(this, R.string.lm_free_mode_toast, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun setCurrentLayoutAsFavorite() {
        val editing = mEditing
        if (editing == null) {
            Toast.makeText(this, R.string.lm_no_layout_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        var alreadySaved = false
        for (p in mPresets) {
            if (p.id == editing.id) { alreadySaved = true; break }
        }
        if (!alreadySaved) {
            Toast.makeText(this,
                    getString(R.string.lm_save_before_favorite),
                    Toast.LENGTH_SHORT).show()
            return
        }
        if (!LayoutPrefs.setFavoriteIdIfPresent(this, editing.id)) {
            Toast.makeText(this, R.string.lm_layout_selection_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this,
                getString(R.string.fission_layout_favorite_set_toast), Toast.LENGTH_SHORT).show()
    }

    // ── Panel toggle animation ─────────────────────────────────────────────────

    private fun togglePanel() {
        if (mPanelVisible) collapsePanel()
        else expandPanel()
    }

    private fun collapsePanel() {
        val panel = mPanelContainer
        if (!mPanelVisible || panel == null) return
        val startW = dpToPx(PANEL_WIDTH_DP)
        val anim = ValueAnimator.ofInt(startW, 0)
        anim.duration = ANIM_DURATION.toLong()
        anim.addUpdateListener { a ->
            val lp = panel.layoutParams
            lp.width = a.animatedValue as Int
            panel.layoutParams = lp
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                panel.visibility = View.GONE
            }
        })
        anim.start()
        mIvToggle?.setImageResource(R.drawable.ic_arrow_forward)
        mPanelVisible = false
    }

    private fun expandPanel() {
        val panel = mPanelContainer
        if (mPanelVisible || panel == null) return
        panel.visibility = View.VISIBLE
        val targetW = dpToPx(PANEL_WIDTH_DP)
        val anim = ValueAnimator.ofInt(0, targetW)
        anim.duration = ANIM_DURATION.toLong()
        anim.addUpdateListener { a ->
            val lp = panel.layoutParams
            lp.width = a.animatedValue as Int
            panel.layoutParams = lp
        }
        anim.start()
        mIvToggle?.setImageResource(R.drawable.ic_arrow_back)
        mPanelVisible = true
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        super.onDestroy()
        mExec.shutdown()
    }

    companion object {
        private const val TAG = "LayoutManager"
        private const val PANEL_WIDTH_DP = 300
        private const val ANIM_DURATION = 220
    }
}
