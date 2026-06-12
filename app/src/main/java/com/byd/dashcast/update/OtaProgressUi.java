package com.byd.dashcast.update;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.R;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Factory for the OTA update progress dialog shown inside MainActivity. */
public final class OtaProgressUi {

    private OtaProgressUi() {}

    /**
     * Creates a {@link UpdateChecker.ProgressListener} that shows an AlertDialog when an update
     * is found, with an inline download progress bar and changelog.
     *
     * @param activity         the host Activity (context + lifecycle checks)
     * @param notifyIfUpToDate if true, shows a toast when no update is found
     */
    public static UpdateChecker.ProgressListener makeListener(
            final android.app.Activity activity, final boolean notifyIfUpToDate) {

        final AlertDialog[] dlgHolder = {null};
        final ProgressBar[] pbHolder  = {null};
        final TextView[]    pctHolder = {null};

        return new UpdateChecker.ProgressListener() {

            @Override
            public void onUpdateFound(final String version, final String changelog,
                                      final String downloadUrl) {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);

                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(pad, pad, pad, pad / 2);

                TextView tvVersion = new TextView(activity);
                tvVersion.setText(activity.getString(R.string.ota_version_label, version));
                tvVersion.setTextSize(16);
                tvVersion.setPadding(pad, 0, pad, pad / 2);
                tvVersion.setTextColor(activity.getColor(R.color.text_accent));
                layout.addView(tvVersion);

                ScrollView sv = new ScrollView(activity);
                TextView tvChangelog = new TextView(activity);
                tvChangelog.setText(renderMarkdown(changelog));
                tvChangelog.setTextSize(13);
                tvChangelog.setPadding(pad, 0, pad, pad);
                tvChangelog.setTextColor(activity.getColor(R.color.text_primary));
                sv.addView(tvChangelog);
                layout.addView(sv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (activity.getResources().getDisplayMetrics().density * 250)));

                final LinearLayout progressLayout = new LinearLayout(activity);
                progressLayout.setOrientation(LinearLayout.VERTICAL);
                progressLayout.setPadding(pad, pad, pad, 0);
                progressLayout.setVisibility(View.GONE);

                ProgressBar pb = new ProgressBar(activity, null,
                        android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                progressLayout.addView(pb);
                pbHolder[0] = pb;

                TextView tvPct = new TextView(activity);
                tvPct.setText(activity.getString(R.string.ota_progress_percent, 0));
                tvPct.setGravity(android.view.Gravity.CENTER);
                tvPct.setTextSize(12);
                tvPct.setTextColor(0xFF888888);
                progressLayout.addView(tvPct);
                pctHolder[0] = tvPct;

                layout.addView(progressLayout);

                dlgHolder[0] = new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.ota_dialog_title))
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton(activity.getString(R.string.ota_btn_update_now), null)
                        .setNegativeButton(activity.getString(R.string.ota_btn_later),
                                (d, w) -> d.dismiss())
                        .create();

                final UpdateChecker.ProgressListener self = this;
                dlgHolder[0].setOnShowListener(dialog -> {
                    android.widget.Button pos = dlgHolder[0].getButton(AlertDialog.BUTTON_POSITIVE);
                    pos.setOnClickListener(v -> {
                        pos.setEnabled(false);
                        dlgHolder[0].getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        sv.setVisibility(View.GONE);
                        tvVersion.setText(activity.getString(R.string.ota_downloading));
                        progressLayout.setVisibility(View.VISIBLE);
                        UpdateChecker.startDownload(activity, downloadUrl, self);
                    });
                });
                dlgHolder[0].show();
            }

            @Override
            public void onDownloadProgress(int percent) {
                if (pbHolder[0] == null) return;
                if (percent < 0) {
                    pbHolder[0].setIndeterminate(true);
                    if (pctHolder[0] != null)
                        pctHolder[0].setText(activity.getString(R.string.ota_progress_unknown));
                } else {
                    pbHolder[0].setIndeterminate(false);
                    pbHolder[0].setProgress(percent);
                    if (pctHolder[0] != null)
                        pctHolder[0].setText(
                                activity.getString(R.string.ota_progress_percent, percent));
                }
            }

            @Override
            public void onInstalling() {
                if (dlgHolder[0] != null) { dlgHolder[0].dismiss(); dlgHolder[0] = null; }
            }

            @Override
            public void onUpToDate() {
                if (notifyIfUpToDate) {
                    android.widget.Toast.makeText(activity.getApplicationContext(),
                            activity.getString(R.string.ota_up_to_date),
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                if (dlgHolder[0] != null) { dlgHolder[0].dismiss(); dlgHolder[0] = null; }
                AppLogger.e("OTA", "error: " + message);
            }
        };
    }

    // ── Markdown renderer (changelog only) ───────────────────────────────────

    static CharSequence renderMarkdown(String raw) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = raw.split("\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            boolean bold = false;
            float relSize = 0f;
            if (line.startsWith("## ")) {
                line = line.substring(3); bold = true; relSize = 1.15f;
            } else if (line.startsWith("### ")) {
                line = line.substring(4); bold = true;
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                line = "• " + line.substring(2);
            }
            int lineStart = sb.length();
            appendWithInlineBold(sb, line);
            int lineEnd = sb.length();
            if (bold)
                sb.setSpan(new StyleSpan(Typeface.BOLD),
                        lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (relSize > 0f)
                sb.setSpan(new RelativeSizeSpan(relSize),
                        lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (li < lines.length - 1) sb.append('\n');
        }
        return sb;
    }

    private static void appendWithInlineBold(SpannableStringBuilder sb, String text) {
        int i = 0;
        while (i < text.length()) {
            int boldStart = text.indexOf("**", i);
            if (boldStart < 0) { sb.append(text.substring(i)); break; }
            sb.append(text.substring(i, boldStart));
            int boldEnd = text.indexOf("**", boldStart + 2);
            if (boldEnd < 0) { sb.append(text.substring(boldStart)); break; }
            int spanStart = sb.length();
            sb.append(text.substring(boldStart + 2, boldEnd));
            sb.setSpan(new StyleSpan(Typeface.BOLD),
                    spanStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = boldEnd + 2;
        }
    }
}
