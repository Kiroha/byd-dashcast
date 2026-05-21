package com.byd.dashcast;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Adapter for the JOURNAL list. M3-styled rows with a level color bar + tinted bg. */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

    private final List<AppLogger.Entry> mEntries = new ArrayList<>();
    private final SimpleDateFormat mTimeFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final int mColorOk;
    private final int mColorWarn;
    private final int mColorErr;
    private final int mColorDebug;
    private final int mColorTag;
    private final int mColorMsg;
    private final int mColorTime;

    public LogAdapter(Context ctx) {
        mColorOk    = ctx.getColor(R.color.md_status_ok);
        mColorWarn  = ctx.getColor(R.color.md_status_warn);
        mColorErr   = ctx.getColor(R.color.md_status_err);
        mColorDebug = ctx.getColor(R.color.md_on_surface_variant);
        mColorTag   = ctx.getColor(R.color.md_primary);
        mColorMsg   = ctx.getColor(R.color.md_on_surface);
        mColorTime  = ctx.getColor(R.color.md_on_surface_variant);
    }

    public void setEntries(List<AppLogger.Entry> entries) {
        mEntries.clear();
        mEntries.addAll(entries);
        notifyDataSetChanged();
    }

    public int size() { return mEntries.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AppLogger.Entry e = mEntries.get(position);
        int color;
        int bgRes;
        String levelLabel;
        switch (e.level) {
            case ERROR: color = mColorErr;   bgRes = R.drawable.bg_log_row_error; levelLabel = "ERROR"; break;
            case WARN:  color = mColorWarn;  bgRes = R.drawable.bg_log_row_warn;  levelLabel = "WARN";  break;
            case DEBUG: color = mColorDebug; bgRes = R.drawable.bg_log_row_info;  levelLabel = "DEBUG"; break;
            case INFO:
            default:    color = mColorOk;    bgRes = R.drawable.bg_log_row_info;  levelLabel = "INFO";  break;
        }
        h.root.setBackgroundResource(bgRes);
        h.bar.setBackgroundColor(color);
        h.level.setText(levelLabel);
        h.level.setTextColor(color);
        h.time.setText(mTimeFmt.format(new Date(e.timestamp)));
        h.time.setTextColor(mColorTime);
        h.tag.setText(e.tag);
        h.tag.setTextColor(mColorTag);
        String msg = e.message;
        if (e.threadName != null && !"main".equals(e.threadName)) {
            msg = msg + "  {" + e.threadName + "}";
        }
        h.msg.setText(msg);
        h.msg.setTextColor(mColorMsg);
    }

    @Override
    public int getItemCount() { return mEntries.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final View      root;
        final View      bar;
        final TextView  level, time, tag, msg;
        VH(View v) {
            super(v);
            root  = v.findViewById(R.id.row_log_root);
            bar   = v.findViewById(R.id.row_log_bar);
            level = v.findViewById(R.id.row_log_level);
            time  = v.findViewById(R.id.row_log_time);
            tag   = v.findViewById(R.id.row_log_tag);
            msg   = v.findViewById(R.id.row_log_msg);
        }
    }
}
