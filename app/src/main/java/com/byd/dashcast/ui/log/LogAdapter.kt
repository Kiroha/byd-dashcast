package com.byd.dashcast.ui.log

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.byd.dashcast.R
import com.byd.dashcast.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Adapter for the JOURNAL list. M3-styled rows with a level color bar + tinted bg. */
class LogAdapter(ctx: Context) : RecyclerView.Adapter<LogAdapter.VH>() {

    private val mEntries = ArrayList<AppLogger.Entry>()
    private val mTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val mDateBuf = Date()

    private val mColorOk = ctx.getColor(R.color.md_status_ok)
    private val mColorWarn = ctx.getColor(R.color.md_status_warn)
    private val mColorErr = ctx.getColor(R.color.md_status_err)
    private val mColorDebug = ctx.getColor(R.color.md_on_surface_variant)
    private val mColorTag = ctx.getColor(R.color.md_primary)
    private val mColorMsg = ctx.getColor(R.color.md_on_surface)
    private val mColorTime = ctx.getColor(R.color.md_on_surface_variant)

    @SuppressLint("NotifyDataSetChanged")
    fun setEntries(entries: List<AppLogger.Entry>) {
        mEntries.clear()
        mEntries.addAll(entries)
        notifyDataSetChanged()
    }

    fun appendEntries(entries: List<AppLogger.Entry>) {
        if (entries.isEmpty()) return
        val start = mEntries.size
        mEntries.addAll(entries)
        notifyItemRangeInserted(start, entries.size)
    }

    fun size(): Int = mEntries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_log, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = mEntries[position]
        val color: Int
        val bgRes: Int
        val levelLabel: String
        when (e.level) {
            AppLogger.Level.ERROR -> { color = mColorErr; bgRes = R.drawable.bg_log_row_error; levelLabel = "ERROR" }
            AppLogger.Level.WARN -> { color = mColorWarn; bgRes = R.drawable.bg_log_row_warn; levelLabel = "WARN" }
            AppLogger.Level.DEBUG -> { color = mColorDebug; bgRes = R.drawable.bg_log_row_info; levelLabel = "DEBUG" }
            AppLogger.Level.INFO -> { color = mColorOk; bgRes = R.drawable.bg_log_row_info; levelLabel = "INFO" }
        }
        if (h.lastBgRes != bgRes) {
            h.root.setBackgroundResource(bgRes)
            h.lastBgRes = bgRes
        }
        h.bar.setBackgroundColor(color)
        h.level.text = levelLabel
        h.level.setTextColor(color)
        mDateBuf.time = e.timestamp
        h.time.text = mTimeFmt.format(mDateBuf)
        h.time.setTextColor(mColorTime)
        h.tag.text = e.tag
        h.tag.setTextColor(mColorTag)
        var msg = e.message
        if (e.threadName != "main") {
            msg = "$msg  {${e.threadName}}"
        }
        h.msg.text = msg
        h.msg.setTextColor(mColorMsg)
    }

    override fun getItemCount(): Int = mEntries.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.row_log_root)
        val bar: View = v.findViewById(R.id.row_log_bar)
        val level: TextView = v.findViewById(R.id.row_log_level)
        val time: TextView = v.findViewById(R.id.row_log_time)
        val tag: TextView = v.findViewById(R.id.row_log_tag)
        val msg: TextView = v.findViewById(R.id.row_log_msg)
        var lastBgRes = -1
    }
}
