package com.byd.dashcast.update

import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.R
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Factory for the OTA update progress dialog shown inside MainActivity. */
object OtaProgressUi {

    /**
     * Creates a [UpdateChecker.ProgressListener] that shows an AlertDialog when an update
     * is found, with an inline download progress bar and changelog.
     *
     * @param activity         the host Activity (context + lifecycle checks)
     * @param notifyIfUpToDate if true, shows a toast when no update is found
     */
    @JvmStatic
    fun makeListener(
        activity: android.app.Activity, notifyIfUpToDate: Boolean
    ): UpdateChecker.ProgressListener {

        var dlgHolder: AlertDialog? = null
        var pbHolder: ProgressBar? = null
        var pctHolder: TextView? = null

        return object : UpdateChecker.ProgressListener {

            /**
             * Closes the dialog without assuming its Activity is still alive.
             *
             * Every callback below this one arrives from UpdateChecker's download thread through
             * `ui.post(...)`, and an APK download over a car hotspot runs for minutes. `onUpdateFound`
             * guards on isFinishing/isDestroyed before it BUILDS the dialog; nothing guarded the
             * later callbacks that CLOSE it. `Dialog.dismiss()` on an Activity whose window is gone
             * throws IllegalArgumentException("View not attached to window manager") — an uncaught
             * RuntimeException on the main thread, i.e. a process crash, which on this app means the
             * cluster projection dies with it.
             *
             * The reference is cleared first, so a second callback cannot retry a dismiss that
             * already failed.
             */
            private fun closeDialog() {
                val d = dlgHolder ?: return
                dlgHolder = null
                if (activity.isFinishing || activity.isDestroyed) return
                try {
                    d.dismiss()
                } catch (t: Throwable) {
                    // Belt and braces: the window can go between the check and the call.
                    AppLogger.w("OTA", "dialog dismiss skipped: " + t.javaClass.simpleName)
                }
            }

            override fun onUpdateFound(asset: UpdateChecker.ReleaseAsset) {
                if (activity.isFinishing || activity.isDestroyed) return
                val version = asset.version
                val changelog = asset.changelog

                val pad = (activity.resources.displayMetrics.density * 20).toInt()

                val layout = LinearLayout(activity)
                layout.orientation = LinearLayout.VERTICAL
                layout.setPadding(pad, pad, pad, pad / 2)

                val tvVersion = TextView(activity)
                tvVersion.text = activity.getString(R.string.ota_version_label, version)
                tvVersion.textSize = 16f
                tvVersion.setPadding(pad, 0, pad, pad / 2)
                tvVersion.setTextColor(activity.getColor(R.color.text_accent))
                layout.addView(tvVersion)

                val sv = ScrollView(activity)
                val tvChangelog = TextView(activity)
                tvChangelog.text = renderMarkdown(changelog)
                tvChangelog.textSize = 13f
                tvChangelog.setPadding(pad, 0, pad, pad)
                tvChangelog.setTextColor(activity.getColor(R.color.text_primary))
                sv.addView(tvChangelog)
                layout.addView(
                    sv, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (activity.resources.displayMetrics.density * 250).toInt()
                    )
                )

                val progressLayout = LinearLayout(activity)
                progressLayout.orientation = LinearLayout.VERTICAL
                progressLayout.setPadding(pad, pad, pad, 0)
                progressLayout.visibility = View.GONE

                val pb = ProgressBar(
                    activity, null,
                    android.R.attr.progressBarStyleHorizontal
                )
                pb.max = 100
                progressLayout.addView(pb)
                pbHolder = pb

                val tvPct = TextView(activity)
                tvPct.text = activity.getString(R.string.ota_progress_percent, 0)
                tvPct.gravity = android.view.Gravity.CENTER
                tvPct.textSize = 12f
                tvPct.setTextColor(0xFF888888.toInt())
                progressLayout.addView(tvPct)
                pctHolder = tvPct

                layout.addView(progressLayout)

                dlgHolder = AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.ota_dialog_title))
                    .setView(layout)
                    .setCancelable(false)
                    .setPositiveButton(activity.getString(R.string.ota_btn_update_now), null)
                    .setNegativeButton(activity.getString(R.string.ota_btn_later)) { d, _ -> d.dismiss() }
                    .create()

                val self: UpdateChecker.ProgressListener = this
                dlgHolder!!.setOnShowListener {
                    val pos = dlgHolder!!.getButton(AlertDialog.BUTTON_POSITIVE)
                    pos.setOnClickListener {
                        pos.isEnabled = false
                        dlgHolder!!.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                        sv.visibility = View.GONE
                        tvVersion.text = activity.getString(R.string.ota_downloading)
                        progressLayout.visibility = View.VISIBLE
                        UpdateChecker.startDownload(activity, asset, self)
                    }
                }
                dlgHolder!!.show()
            }

            override fun onDownloadProgress(percent: Int) {
                val pb = pbHolder ?: return
                if (percent < 0) {
                    pb.isIndeterminate = true
                    pctHolder?.setText(activity.getString(R.string.ota_progress_unknown))
                } else {
                    pb.isIndeterminate = false
                    pb.progress = percent
                    pctHolder?.setText(
                        activity.getString(R.string.ota_progress_percent, percent)
                    )
                }
            }

            override fun onInstalling() {
                closeDialog()
            }

            override fun onUpToDate() {
                if (notifyIfUpToDate) {
                    android.widget.Toast.makeText(
                        activity.applicationContext,
                        activity.getString(R.string.ota_up_to_date),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onError(message: String) {
                closeDialog()
                AppLogger.e("OTA", "error: $message")
            }
        }
    }

    // ── Markdown renderer (changelog only) ───────────────────────────────────

    internal fun renderMarkdown(raw: String): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = "\n".toPattern().split(raw)
        for (li in lines.indices) {
            var line = lines[li]
            var bold = false
            var relSize = 0f
            if (line.startsWith("## ")) {
                line = line.substring(3); bold = true; relSize = 1.15f
            } else if (line.startsWith("### ")) {
                line = line.substring(4); bold = true
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                line = "• " + line.substring(2)
            }
            val lineStart = sb.length
            appendWithInlineBold(sb, line)
            val lineEnd = sb.length
            if (bold)
                sb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            if (relSize > 0f)
                sb.setSpan(
                    RelativeSizeSpan(relSize),
                    lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            if (li < lines.size - 1) sb.append('\n')
        }
        return sb
    }

    private fun appendWithInlineBold(sb: SpannableStringBuilder, text: String) {
        var i = 0
        while (i < text.length) {
            val boldStart = text.indexOf("**", i)
            if (boldStart < 0) { sb.append(text.substring(i)); break }
            sb.append(text.substring(i, boldStart))
            val boldEnd = text.indexOf("**", boldStart + 2)
            if (boldEnd < 0) { sb.append(text.substring(boldStart)); break }
            val spanStart = sb.length
            sb.append(text.substring(boldStart + 2, boldEnd))
            sb.setSpan(
                StyleSpan(Typeface.BOLD),
                spanStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            i = boldEnd + 2
        }
    }
}
