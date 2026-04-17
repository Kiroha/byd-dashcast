package com.byd.myapp;

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
 * LogActivity — visualiseur de journal en temps réel.
 *
 * • Affichage scrollable coloré par niveau : DEBUG=gris, INFO=blanc, WARN=orange, ERROR=rouge
 * • Filtre texte instantané (tag ou message)
 * • Auto-scroll vers le bas (toggle)
 * • Refresh automatique toutes les 500 ms pendant que l'activité est visible
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
    private Button      btn;
    private TextView    tvStatus;

    private String mFilter = "";
    private boolean mRunning = false;

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
        btn     = (Button)      findViewById(R.id.log_btn_export);
        tvStatus = (TextView)   findViewById(R.id.log_tv_export_status);

        // Fond sombre pour le log
        tvLog.setBackgroundColor(Color.parseColor("#1A1A1A"));
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
                // 1. Sauvegarde le fichier .log + ouvre le share chooser
                AppLogger.share(LogActivity.this);
                // 2. Double automatique dans remote log analytics
                tvStatus.setText("Double  en cours…");
                tvStatus.setTextColor(android.graphics.Color.parseColor("#FFA040"));
                btn.setEnabled(false);
                LogExporter.export(new LogExporter.ExportCallback() {
                    @Override
                    public void onSuccess(final int count, final int httpStatus) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvStatus.setText("✅ Fichier partagé + "
                                        + count + " entrées  (HTTP " + httpStatus + ")");
                                tvStatus.setTextColor(
                                        android.graphics.Color.parseColor("#44DD44"));
                                btn.setEnabled(true);
                            }
                        });
                    }
                    @Override
                    public void onError(final String message) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvStatus.setText("⚠ Fichier partagé — : ❌ " + message);
                                tvStatus.setTextColor(
                                        android.graphics.Color.parseColor("#FFA040"));
                                btn.setEnabled(true);
                            }
                        });
                    }
                });
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLogger.clear();
                refreshLog();
            }
        });

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btn.setEnabled(false);
                tvStatus.setText("Envoi en cours…");
                tvStatus.setTextColor(android.graphics.Color.parseColor("#FFA040"));
                LogExporter.export(new LogExporter.ExportCallback() {
                    @Override
                    public void onSuccess(final int count, final int httpStatus) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvStatus.setText("✅ " + count
                                    + " entrées envoyées (HTTP " + httpStatus + ")");
                                tvStatus.setTextColor(
                                    android.graphics.Color.parseColor("#44DD44"));
                                btn.setEnabled(true);
                            }
                        });
                    }
                    @Override
                    public void onError(final String message) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvStatus.setText("❌ " + message);
                                tvStatus.setTextColor(
                                    android.graphics.Color.parseColor("#FF4444"));
                                btn.setEnabled(true);
                            }
                        });
                    }
                });
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
        List<AppLogger.Entry> entries = AppLogger.getEntries();
        String filter = mFilter.toLowerCase(Locale.getDefault());

        // Construire un SpannableString coloré
        SpannableString span = buildSpannable(entries, filter);

        tvLog.setText(span, TextView.BufferType.SPANNABLE);

        if (cbAutoScroll.isChecked()) {
            scrollView.post(new Runnable() {
                @Override public void run() { scrollView.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    private SpannableString buildSpannable(List<AppLogger.Entry> entries, String filter) {
        StringBuilder sb = new StringBuilder();
        // On mémorise les positions de chaque ligne pour colorier
        int[] lineStarts  = new int[entries.size()];
        int[] lineEnds    = new int[entries.size()];
        AppLogger.Level[]  levels     = new AppLogger.Level[entries.size()];
        int[] timeStarts   = new int[entries.size()];
        int[] timeEnds     = new int[entries.size()];
        int[] tagStarts    = new int[entries.size()];
        int[] tagEnds      = new int[entries.size()];
        int count = 0;

        for (AppLogger.Entry e : entries) {
            // Filtre
            if (!filter.isEmpty()) {
                boolean match = e.tag.toLowerCase(Locale.getDefault()).contains(filter)
                        || e.message.toLowerCase(Locale.getDefault()).contains(filter)
                        || e.level.name().toLowerCase(Locale.getDefault()).contains(filter);
                if (!match) continue;
            }

            lineStarts[count] = sb.length();

            // "[HH:mm:ss.SSS]"
            timeStarts[count] = sb.length();
            sb.append("[").append(mTimeFmt.format(new Date(e.timestamp))).append("]");
            timeEnds[count] = sb.length();

            // "[LEVEL][TAG] "
            sb.append("[").append(e.level.name()).append("] ");
            tagStarts[count] = sb.length();
            sb.append("[").append(e.tag).append("] ");
            tagEnds[count] = sb.length();

            sb.append(e.message);
            // Thread si pas main
            if (!"main".equals(e.threadName)) {
                sb.append("  {").append(e.threadName).append("}");
            }
            sb.append("\n");

            lineEnds[count] = sb.length();
            levels[count] = e.level;
            count++;
        }

        SpannableString span = new SpannableString(sb.toString());

        for (int i = 0; i < count; i++) {
            int msgColor = levelColor(levels[i]);
            // Ligne entière : couleur du niveau
            span.setSpan(new ForegroundColorSpan(msgColor),
                    lineStarts[i], lineEnds[i], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Timestamp en gris discret
            span.setSpan(new ForegroundColorSpan(COLOR_TIME),
                    timeStarts[i], timeEnds[i], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Tag en bleu clair
            span.setSpan(new ForegroundColorSpan(COLOR_TAG),
                    tagStarts[i], tagEnds[i], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return span;
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
