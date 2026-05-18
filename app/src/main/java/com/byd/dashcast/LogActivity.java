package com.byd.dashcast;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LogActivity — real-time log viewer.
 *
 * • Scrollable display color-coded by level: DEBUG=grey, INFO=white, WARN=orange, ERROR=red
 * • Instant text filter (by tag or message)
 * • Auto-scroll vers le bas (toggle)
 * • Auto-refresh every 500 ms while the activity is visible
 * • Partage texte brut + effacement
 */
public class LogActivity extends AppCompatActivity {

    private static final String TAG = "LogActivity";
    private static final long REFRESH_MS = 500;

    // Couleurs par niveau
    private static final int COLOR_DEBUG    = Color.parseColor("#999999");
    private static final int COLOR_INFO     = Color.parseColor("#DDDDDD");
    private static final int COLOR_WARN     = Color.parseColor("#FFA040");
    private static final int COLOR_ERROR    = Color.parseColor("#FF4444");
    private static final int COLOR_TAG      = Color.parseColor("#88CCFF");
    private static final int COLOR_TIME     = Color.parseColor("#666666");

    private ScrollView  scrollView;
    private TextView    tvLog;
    private EditText    etFilter;
    private CheckBox    cbAutoScroll;
    private Button      btnShare;
    private Button      btnClear;

    private String mFilter = "";
    private boolean mRunning = false;
    private int    mLastEntryCount = -1;   // perf: skip rebuild si rien de nouveau
    private String mLastFilter    = null; // perf: invalider si filtre change

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (mRunning) {
                refreshLog();
                mHandler.postDelayed(this, REFRESH_MS);
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

        scrollView   = (ScrollView)  findViewById(R.id.log_scroll);
        tvLog        = (TextView)    findViewById(R.id.log_tv);
        etFilter     = (EditText)    findViewById(R.id.log_filter);
        cbAutoScroll = (CheckBox)    findViewById(R.id.log_autoscroll);
        btnShare     = (Button)      findViewById(R.id.log_btn_share);
        btnClear     = (Button)      findViewById(R.id.log_btn_clear);

        // Fond sombre pour le log
        tvLog.setBackgroundColor(getColor(R.color.bg_log));
        tvLog.setTextColor(COLOR_INFO);

        cbAutoScroll.setChecked(true);

        etFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                mFilter = s.toString();
                refreshLog();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLogger.share(LogActivity.this);
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLogger.clear();
                refreshLog();
            }
        });

        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");
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

    private final SimpleDateFormat mTimeFmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private void refreshLog() {
        // Skip buffer copy if neither the entry count nor the filter has changed.
        int currentCount = AppLogger.getEntriesCount();
        String filter = mFilter.toLowerCase(Locale.ROOT);
        if (currentCount == mLastEntryCount && filter.equals(mLastFilter)) return;
        // Buffer or filter changed: copy and rebuild.
        List<AppLogger.Entry> entries = AppLogger.getEntries();
        mLastEntryCount = entries.size();
        mLastFilter     = filter;

        // Build a color-coded SpannableString
        SpannableString span = buildSpannable(entries, filter);

        tvLog.setText(span, TextView.BufferType.SPANNABLE);

        if (cbAutoScroll.isChecked()) {
            scrollView.post(new Runnable() {
                @Override public void run() { scrollView.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    /** Static cache — avoids allocating a Level[] on every buildSpannable call. */
    private static final AppLogger.Level[] LEVEL_VALUES = AppLogger.Level.values();

    private SpannableString buildSpannable(List<AppLogger.Entry> entries, String filter) {
        StringBuilder sb = new StringBuilder();
        // Store only the positions of entries that pass the filter:
        //   {lineStart, lineEnd, timeStart, timeEnd, tagStart, tagEnd, level.ordinal()}
        // Avoids allocating 7×entries.size() elements when the filter retains only a
        // fraction of entries (e.g. 10/3000 → saves ~84 KB per rebuild).
        java.util.ArrayList<int[]> spanData = new java.util.ArrayList<>();

        for (AppLogger.Entry e : entries) {
            // Filtre
            if (!filter.isEmpty()) {
                boolean match = containsIgnoreCase(e.tag, filter)
                        || containsIgnoreCase(e.message, filter)
                        || containsIgnoreCase(e.level.name(), filter);
                if (!match) continue;
            }

            int lineStart = sb.length();

            // "[HH:mm:ss.SSS]"
            int timeStart = sb.length();
            sb.append("[").append(mTimeFmt.format(new Date(e.timestamp))).append("]");
            int timeEnd = sb.length();

            // "[LEVEL][TAG] "
            sb.append("[").append(e.level.name()).append("] ");
            int tagStart = sb.length();
            sb.append("[").append(e.tag).append("] ");
            int tagEnd = sb.length();

            sb.append(e.message);
            // Thread si pas main
            if (!"main".equals(e.threadName)) {
                sb.append("  {").append(e.threadName).append("}");
            }
            sb.append("\n");

            int lineEnd = sb.length();
            spanData.add(new int[]{lineStart, lineEnd, timeStart, timeEnd, tagStart, tagEnd,
                    e.level.ordinal()});
        }

        SpannableString span = new SpannableString(sb.toString());

        for (int[] d : spanData) {
            int msgColor = levelColor(LEVEL_VALUES[d[6]]);
            // Full line: level color
            span.setSpan(new ForegroundColorSpan(msgColor),
                    d[0], d[1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Timestamp en gris discret
            span.setSpan(new ForegroundColorSpan(COLOR_TIME),
                    d[2], d[3], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Tag en bleu clair
            span.setSpan(new ForegroundColorSpan(COLOR_TAG),
                    d[4], d[5], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return span;
    }

    /** Case-insensitive contains without allocating lowercase copies on every call. */
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

    private int levelColor(AppLogger.Level level) {
        switch (level) {
            case DEBUG: return COLOR_DEBUG;
            case INFO:  return COLOR_INFO;
            case WARN:  return COLOR_WARN;
            case ERROR: return COLOR_ERROR;
            default:    return COLOR_INFO;
        }
    }
}
