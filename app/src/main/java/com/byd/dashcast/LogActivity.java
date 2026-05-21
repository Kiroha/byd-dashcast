package com.byd.dashcast;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LogActivity — JOURNAL viewer (M3 redesign, mockup screen 6).
 *
 * • Nav rail (Apps / Réglages / Diag / Système / Journal active)
 * • Top bar: title + Pause/Clear/Share/Save icon buttons + clock
 * • Search bar (filter by tag / message / level)
 * • 4 chip filters: Tous / Info / Warn / Error (with counts)
 * • RecyclerView of M3 rows (colored bar + tinted bg per level)
 */
public class LogActivity extends AppCompatActivity {

    private static final long REFRESH_MS      = 500;   // delay when log changed
    private static final long REFRESH_IDLE_MS = 2000;  // delay when nothing new

    private RecyclerView    mRecycler;
    private LogAdapter      mAdapter;
    private EditText        mEtFilter;
    private MaterialButton  mBtnPause, mBtnClear, mBtnShare, mBtnSave;
    private MaterialButton  mChipAll, mChipInfo, mChipWarn, mChipError;
    private TextView        mEmptyView;

    private String                 mFilter      = "";
    private AppLogger.Level        mLevelFilter = null;   // null = all
    private boolean                mPaused      = false;
    private boolean                mRunning     = false;
    private int                    mLastEntryCount = -1;
    private String                 mLastFilterKey  = null;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override public void run() {
            if (mRunning && !mPaused) {
                boolean changed = refreshLog();
                mHandler.postDelayed(this, changed ? REFRESH_MS : REFRESH_IDLE_MS);
            }
        }
    };

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        wireLogNavRail();

        mRecycler   = findViewById(R.id.log_recycler);
        mEtFilter   = findViewById(R.id.log_filter);
        mBtnPause   = findViewById(R.id.log_btn_pause);
        mBtnClear   = findViewById(R.id.log_btn_clear);
        mBtnShare   = findViewById(R.id.log_btn_share);
        mBtnSave    = findViewById(R.id.log_btn_save);
        mChipAll    = findViewById(R.id.chip_all);
        mChipInfo   = findViewById(R.id.chip_info);
        mChipWarn   = findViewById(R.id.chip_warn);
        mChipError  = findViewById(R.id.chip_error);
        mEmptyView  = findViewById(R.id.log_empty_view);

        mAdapter = new LogAdapter(this);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        mEtFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                mFilter = s.toString();
                forceRefresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        mBtnPause.setOnClickListener(v -> togglePause());
        mBtnClear.setOnClickListener(v -> { AppLogger.clear(); forceRefresh(); });
        mBtnShare.setOnClickListener(v -> AppLogger.share(LogActivity.this));
        mBtnSave.setOnClickListener(v -> {
            File f = AppLogger.saveToFile(LogActivity.this);
            String msg = (f != null)
                    ? getString(R.string.log_saved_toast, f.getAbsolutePath())
                    : getString(R.string.log_save_failed);
            Toast.makeText(LogActivity.this, msg, Toast.LENGTH_LONG).show();
        });

        mChipAll.setOnClickListener(v   -> setLevelFilter(null));
        mChipInfo.setOnClickListener(v  -> setLevelFilter(AppLogger.Level.INFO));
        mChipWarn.setOnClickListener(v  -> setLevelFilter(AppLogger.Level.WARN));
        mChipError.setOnClickListener(v -> setLevelFilter(AppLogger.Level.ERROR));

        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");
    }

    private void wireLogNavRail() {
        View navApps     = findViewById(R.id.nav_apps_log);
        View navSettings = findViewById(R.id.nav_settings_log);
        View navDiag     = findViewById(R.id.nav_diag_log);
        View navSysinfo  = findViewById(R.id.nav_sysinfo_log);
        View navLogo     = findViewById(R.id.iv_nav_logo_log);
        if (navApps != null)     navApps.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
        if (navSettings != null) navSettings.setOnClickListener(v -> { startActivity(new Intent(this, SettingsActivity.class)); finish(); });
        if (navDiag != null)     navDiag.setOnClickListener(v -> { startActivity(new Intent(this, DiagActivity.class)); finish(); });
        if (navSysinfo != null)  navSysinfo.setOnClickListener(v -> { startActivity(new Intent(this, SysInfoActivity.class)); finish(); });
        if (navLogo != null)     navLogo.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRunning = true;
        mHandler.post(mRefreshRunnable);
        AppLogger.lifecycle(getClass().getSimpleName(), "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        mRunning = false;
        mHandler.removeCallbacks(mRefreshRunnable);
        AppLogger.lifecycle(getClass().getSimpleName(), "onPause");
    }

    // ────────────────────────────────────────────────────────────────────────────

    private void togglePause() {
        mPaused = !mPaused;
        if (mPaused) {
            mBtnPause.setIconResource(R.drawable.ic_play);
            mBtnPause.setContentDescription(getString(R.string.log_btn_resume_cd));
            mHandler.removeCallbacks(mRefreshRunnable);
        } else {
            mBtnPause.setIconResource(R.drawable.ic_pause);
            mBtnPause.setContentDescription(getString(R.string.log_btn_pause_cd));
            forceRefresh();
            mHandler.post(mRefreshRunnable);
        }
    }

    private void setLevelFilter(AppLogger.Level lvl) {
        mLevelFilter = lvl;
        applyChipState(mChipAll,   lvl == null);
        applyChipState(mChipInfo,  lvl == AppLogger.Level.INFO);
        applyChipState(mChipWarn,  lvl == AppLogger.Level.WARN);
        applyChipState(mChipError, lvl == AppLogger.Level.ERROR);
        forceRefresh();
    }

    private void applyChipState(MaterialButton chip, boolean selected) {
        if (chip == null) return;
        if (selected) {
            chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.md_secondary_container)));
            chip.setStrokeWidth(0);
            chip.setTextColor(getColor(R.color.md_on_secondary_container));
        } else {
            chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColor(android.R.color.transparent)));
            chip.setStrokeWidth(dp(1));
            chip.setTextColor(getColor(R.color.md_on_surface_variant));
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void forceRefresh() {
        mLastEntryCount = -1;
        mLastFilterKey  = null;
        refreshLog();
    }

    private boolean refreshLog() {
        int currentCount = AppLogger.getEntriesCount();
        String filterKey = mFilter.toLowerCase(Locale.ROOT)
                + "|" + (mLevelFilter == null ? "*" : mLevelFilter.name());
        if (currentCount == mLastEntryCount && filterKey.equals(mLastFilterKey)) return false;

        List<AppLogger.Entry> entries = AppLogger.getEntries();
        mLastEntryCount = entries.size();
        mLastFilterKey  = filterKey;

        // Counts per level (from full buffer, ignoring text filter)
        int cAll = entries.size(), cInfo = 0, cWarn = 0, cErr = 0;
        for (AppLogger.Entry e : entries) {
            switch (e.level) {
                case INFO:  cInfo++; break;
                case WARN:  cWarn++; break;
                case ERROR: cErr++;  break;
                default: break;
            }
        }
        mChipAll.setText(getString(R.string.log_chip_all, cAll));
        mChipInfo.setText(getString(R.string.log_chip_info, cInfo));
        mChipWarn.setText(getString(R.string.log_chip_warn, cWarn));
        mChipError.setText(getString(R.string.log_chip_error, cErr));

        // Apply filter (text + level)
        String needle = mFilter.toLowerCase(Locale.ROOT);
        List<AppLogger.Entry> filtered = new ArrayList<>(entries.size());
        for (AppLogger.Entry e : entries) {
            if (mLevelFilter != null && e.level != mLevelFilter) continue;
            if (!needle.isEmpty()) {
                boolean match = containsIgnoreCase(e.tag, needle)
                        || containsIgnoreCase(e.message, needle)
                        || containsIgnoreCase(e.level.name(), needle);
                if (!match) continue;
            }
            filtered.add(e);
        }

        mAdapter.setEntries(filtered);
        mEmptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        mRecycler.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);

        if (!filtered.isEmpty()) {
            mRecycler.scrollToPosition(filtered.size() - 1);
        }
        return true;
    }

    private static boolean containsIgnoreCase(String text, String needleLowercase) {
        if (text == null) return false;
        if (needleLowercase == null || needleLowercase.isEmpty()) return true;
        int n = needleLowercase.length();
        int limit = text.length() - n;
        for (int i = 0; i <= limit; i++) {
            if (text.regionMatches(true, i, needleLowercase, 0, n)) return true;
        }
        return false;
    }
}
