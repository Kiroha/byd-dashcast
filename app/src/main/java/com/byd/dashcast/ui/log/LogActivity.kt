package com.byd.dashcast.ui.log

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.byd.dashcast.MainActivity
import com.byd.dashcast.R
import com.byd.dashcast.ui.diag.DiagActivity
import com.byd.dashcast.ui.diag.SysInfoActivity
import com.byd.dashcast.ui.nav.NavRailHotspot
import com.byd.dashcast.ui.nav.NavRailLayouts
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.LocaleHelper
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * LogActivity — JOURNAL viewer (M3 redesign, mockup screen 6).
 *
 * • Nav rail (Apps / Réglages / Diag / Système / Journal active)
 * • Top bar: title + Pause/Clear/Share/Save icon buttons + clock
 * • Search bar (filter by tag / message / level)
 * • 4 chip filters: Tous / Info / Warn / Error (with counts)
 * • RecyclerView of M3 rows (colored bar + tinted bg per level)
 */
class LogActivity : AppCompatActivity() {

    private lateinit var mRecycler: RecyclerView
    private lateinit var mAdapter: LogAdapter
    private lateinit var mEtFilter: EditText
    private lateinit var mBtnPause: MaterialButton
    private lateinit var mBtnClear: MaterialButton
    private lateinit var mBtnShare: MaterialButton
    private lateinit var mBtnSave: MaterialButton
    private lateinit var mChipAll: MaterialButton
    private lateinit var mChipInfo: MaterialButton
    private lateinit var mChipWarn: MaterialButton
    private lateinit var mChipError: MaterialButton
    private lateinit var mEmptyView: TextView

    private var mFilter = ""
    private var mLevelFilter: AppLogger.Level? = null // null = all
    private var mPaused = false
    private var mRunning = false

    // Change detection uses AppLogger's mutation stamp, NOT the entry count:
    // once the circular buffer saturates, size() stays pinned at MAX_ENTRIES
    // and a count-based check would freeze the viewer forever.
    private var mLastChangeStamp = -1L
    private var mLastFilterKey: String? = null
    private var mLastGeneration = -1L
    private var mFirstSourceSequence = -1L
    private var mLastSourceSequence = -1L

    private val mHandler = Handler(Looper.getMainLooper())
    private val mRefreshRunnable = object : Runnable {
        override fun run() {
            if (mRunning && !mPaused) {
                val changed = refreshLog()
                mHandler.postDelayed(this, if (changed) REFRESH_MS else REFRESH_IDLE_MS)
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        wireLogNavRail()

        mRecycler = findViewById(R.id.log_recycler)
        mEtFilter = findViewById(R.id.log_filter)
        mBtnPause = findViewById(R.id.log_btn_pause)
        mBtnClear = findViewById(R.id.log_btn_clear)
        mBtnShare = findViewById(R.id.log_btn_share)
        mBtnSave = findViewById(R.id.log_btn_save)
        mChipAll = findViewById(R.id.chip_all)
        mChipInfo = findViewById(R.id.chip_info)
        mChipWarn = findViewById(R.id.chip_warn)
        mChipError = findViewById(R.id.chip_error)
        mEmptyView = findViewById(R.id.log_empty_view)

        mAdapter = LogAdapter(this)
        mRecycler.layoutManager = LinearLayoutManager(this)
        mRecycler.adapter = mAdapter

        mEtFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                mFilter = s.toString()
                forceRefresh()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        mBtnPause.setOnClickListener { togglePause() }
        mBtnClear.setOnClickListener { AppLogger.clear(); forceRefresh() }
        mBtnShare.setOnClickListener { AppLogger.share(this) }
        mBtnSave.setOnClickListener {
            val f = AppLogger.saveToFile(this)
            val msg = if (f != null)
                getString(R.string.log_saved_toast, f.absolutePath)
            else
                getString(R.string.log_save_failed)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        mChipAll.setOnClickListener { setLevelFilter(null) }
        mChipInfo.setOnClickListener { setLevelFilter(AppLogger.Level.INFO) }
        mChipWarn.setOnClickListener { setLevelFilter(AppLogger.Level.WARN) }
        mChipError.setOnClickListener { setLevelFilter(AppLogger.Level.ERROR) }

        AppLogger.lifecycle(javaClass.simpleName, "onCreate")
    }

    private fun wireLogNavRail() {
        val navApps: View? = findViewById(R.id.nav_apps_log)
        val navSettings: View? = findViewById(R.id.nav_settings_log)
        val navDiag: View? = findViewById(R.id.nav_diag_log)
        val navSysinfo: View? = findViewById(R.id.nav_sysinfo_log)
        val navLogo: View? = findViewById(R.id.iv_nav_logo_log)
        navApps?.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
        navSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)); finish() }
        navDiag?.setOnClickListener { startActivity(Intent(this, DiagActivity::class.java)); finish() }
        navSysinfo?.setOnClickListener { startActivity(Intent(this, SysInfoActivity::class.java)); finish() }
        navLogo?.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
        // v1.2.44 — Hotspot navrail entry (DL3 + use_own_sim runtime-gated)
        NavRailHotspot.apply(this, R.id.nav_hotspot_log, true)
        // v1.4.9-beta — Layouts
        NavRailLayouts.apply(this, R.id.nav_layouts_log, true)
    }

    override fun onResume() {
        super.onResume()
        mRunning = true
        mHandler.post(mRefreshRunnable)
        AppLogger.lifecycle(javaClass.simpleName, "onResume")
    }

    override fun onPause() {
        super.onPause()
        mRunning = false
        mHandler.removeCallbacks(mRefreshRunnable)
        AppLogger.lifecycle(javaClass.simpleName, "onPause")
    }

    // ────────────────────────────────────────────────────────────────────────────

    private fun togglePause() {
        mPaused = !mPaused
        if (mPaused) {
            mBtnPause.setIconResource(R.drawable.ic_play)
            mBtnPause.contentDescription = getString(R.string.log_btn_resume_cd)
            mHandler.removeCallbacks(mRefreshRunnable)
        } else {
            mBtnPause.setIconResource(R.drawable.ic_pause)
            mBtnPause.contentDescription = getString(R.string.log_btn_pause_cd)
            forceRefresh()
            mHandler.post(mRefreshRunnable)
        }
    }

    private fun setLevelFilter(lvl: AppLogger.Level?) {
        mLevelFilter = lvl
        applyChipState(mChipAll, lvl == null)
        applyChipState(mChipInfo, lvl == AppLogger.Level.INFO)
        applyChipState(mChipWarn, lvl == AppLogger.Level.WARN)
        applyChipState(mChipError, lvl == AppLogger.Level.ERROR)
        forceRefresh()
    }

    private fun applyChipState(chip: MaterialButton, selected: Boolean) {
        if (selected) {
            chip.backgroundTintList = ColorStateList.valueOf(getColor(R.color.md_secondary_container))
            chip.strokeWidth = 0
            chip.setTextColor(getColor(R.color.md_on_secondary_container))
        } else {
            chip.backgroundTintList = ColorStateList.valueOf(getColor(android.R.color.transparent))
            chip.strokeWidth = dp(1)
            chip.setTextColor(getColor(R.color.md_on_surface_variant))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun forceRefresh() {
        mLastChangeStamp = -1L
        mLastFilterKey = null
        refreshLog()
    }

    private fun refreshLog(): Boolean {
        val currentStamp = AppLogger.getChangeStamp()
        val filterKey = mFilter.lowercase(Locale.ROOT) + "|" + (mLevelFilter?.name ?: "*")
        if (currentStamp == mLastChangeStamp && filterKey == mLastFilterKey) return false

        val initialLoad = mLastChangeStamp < 0L
        val filterChanged = filterKey != mLastFilterKey
        val update = if (filterChanged) {
            AppLogger.getEntryUpdate(-1L, -1L, -1L)
        } else {
            AppLogger.getEntryUpdate(mLastGeneration, mFirstSourceSequence, mLastSourceSequence)
        }
        mLastChangeStamp = currentStamp
        mLastFilterKey = filterKey
        mLastGeneration = update.generation
        mFirstSourceSequence = update.firstSequence
        mLastSourceSequence = update.lastSequence

        val cnts = update.countByLevel
        val cAll = update.totalCount
        val cInfo = cnts[AppLogger.Level.INFO.ordinal]
        val cWarn = cnts[AppLogger.Level.WARN.ordinal]
        val cErr = cnts[AppLogger.Level.ERROR.ordinal]
        mChipAll.text = getString(R.string.log_chip_all, cAll)
        mChipInfo.text = getString(R.string.log_chip_info, cInfo)
        mChipWarn.text = getString(R.string.log_chip_warn, cWarn)
        mChipError.text = getString(R.string.log_chip_error, cErr)

        // Apply filter (text + level)
        val needle = mFilter.lowercase(Locale.ROOT)
        val filtered = ArrayList<AppLogger.Entry>(update.entries.size)
        for (e in update.entries) {
            if (mLevelFilter != null && e.level != mLevelFilter) continue
            if (needle.isNotEmpty()) {
                val match = containsIgnoreCase(e.tag, needle) ||
                    containsIgnoreCase(e.message, needle) ||
                    containsIgnoreCase(e.level.name, needle)
                if (!match) continue
            }
            filtered.add(e)
        }

        val prevSize = mAdapter.size()
        val canAppend = update.appendOnly && !filterChanged
        if (canAppend) mAdapter.appendEntries(filtered) else mAdapter.setEntries(filtered)
        val currentSize = mAdapter.size()
        mEmptyView.visibility = if (currentSize == 0) View.VISIBLE else View.GONE
        mRecycler.visibility = if (currentSize == 0) View.GONE else View.VISIBLE

        // Auto-scroll on initial load or when matching rows were genuinely appended.
        if (currentSize > 0 && (initialLoad || (canAppend && currentSize > prevSize))) {
            mRecycler.scrollToPosition(currentSize - 1)
        }
        return true
    }

    companion object {
        private const val REFRESH_MS = 500L // delay when log changed
        private const val REFRESH_IDLE_MS = 2000L // delay when nothing new

        private fun containsIgnoreCase(text: String?, needleLowercase: String?): Boolean {
            if (text == null) return false
            if (needleLowercase == null || needleLowercase.isEmpty()) return true
            val n = needleLowercase.length
            val limit = text.length - n
            for (i in 0..limit) {
                if (text.regionMatches(i, needleLowercase, 0, n, ignoreCase = true)) return true
            }
            return false
        }
    }
}
