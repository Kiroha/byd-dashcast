package com.byd.myapp;

import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.byd.myapp.model.AppInfo;

import java.util.ArrayList;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    public interface OnSendToDashboardListener {
        void onSendToDashboard(AppInfo app);
        void onSendToMain(AppInfo app);
    }

    private List<AppInfo> mApps = new ArrayList<>();
    private final OnSendToDashboardListener mListener;
    private boolean mDashboardAvailable = false;
    private boolean mMainSendEnabled    = true;

    public AppListAdapter(OnSendToDashboardListener listener) {
        mListener = listener;
    }

    public void setApps(List<AppInfo> apps) {
        mApps = apps;
        notifyDataSetChanged();
    }

    public void setDashboardAvailable(boolean available) {
        mDashboardAvailable = available;
        notifyDataSetChanged();
    }

    public void setMainSendEnabled(boolean enabled) {
        mMainSendEnabled = enabled;
        notifyDataSetChanged();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        final AppInfo app = mApps.get(position);
        holder.ivIcon.setImageDrawable(app.icon);
        holder.tvName.setText(app.appName);
        holder.btnSend.setEnabled(mDashboardAvailable);
        holder.btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mListener.onSendToDashboard(app);
            }
        });
        holder.btnSendToMain.setEnabled(mMainSendEnabled);
        holder.btnSendToMain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mListener.onSendToMain(app);
            }
        });
    }

    @Override
    public int getItemCount() {
        return mApps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView  tvName;
        final Button    btnSend;
        final Button    btnSendToMain;

        ViewHolder(View itemView) {
            super(itemView);
            ivIcon       = (ImageView) itemView.findViewById(R.id.iv_app_icon);
            tvName       = (TextView)  itemView.findViewById(R.id.tv_app_name);
            btnSend      = (Button)    itemView.findViewById(R.id.btn_send_to_dash);
            btnSendToMain = (Button)   itemView.findViewById(R.id.btn_send_to_main);
        }
    }
}
