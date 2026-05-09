package com.byd.dashcast;

import androidx.recyclerview.widget.RecyclerView;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import com.byd.dashcast.model.AppInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    public interface OnSendToDashboardListener {
        void onSendToDashboard(AppInfo app);
        void onSendToMain(AppInfo app);
        void onKillApp(AppInfo app);
        void onToggleFavorite(AppInfo app);
        void onSetAutoLaunch(AppInfo app, boolean enable);
    }

    private List<AppInfo> mApps = new ArrayList<>();
    private final OnSendToDashboardListener mListener;
    private String mCurrentPackage = null;
    private String mMainPackage = null;
    private final HashMap<String, Integer> mPackageIndexMap = new HashMap<>();
    
    private boolean mIsGridMode = false;

    public AppListAdapter(OnSendToDashboardListener listener) {
        mListener = listener;
    }

    public void setGridMode(boolean isGridMode) {
        if (mIsGridMode != isGridMode) {
            mIsGridMode = isGridMode;
            notifyDataSetChanged();
        }
    }

    public boolean isGridMode() {
        return mIsGridMode;
    }

    public void setApps(List<AppInfo> apps) {
        mApps = apps;
        mPackageIndexMap.clear();
        for (int i = 0; i < apps.size(); i++) {
            mPackageIndexMap.put(apps.get(i).packageName, i);
        }
        notifyDataSetChanged();
    }

    public void setCurrentPackage(String packageName) {
        String old = mCurrentPackage;
        mCurrentPackage = packageName;
        notifyPackageChanged(old);
        notifyPackageChanged(packageName);
    }

    public void setMainPackage(String packageName) {
        String old = mMainPackage;
        mMainPackage = packageName;
        notifyPackageChanged(old);
        notifyPackageChanged(packageName);
    }

    private void notifyPackageChanged(String packageName) {
        if (packageName == null) return;
        Integer idx = mPackageIndexMap.get(packageName);
        if (idx != null) notifyItemChanged(idx);
    }

    @Override
    public int getItemViewType(int position) {
        return mIsGridMode ? 1 : 0;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        int layoutId = viewType == 1 ? R.layout.item_app_grid : R.layout.item_app;
        View v = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new ViewHolder(v, mListener, this);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        final AppInfo app = mApps.get(position);
        holder.ivIcon.setImageDrawable(app.icon);
        
        // Indicate pinned state with a star prefix or bold text in List mode
        if (app.isFavorite && !mIsGridMode) {
            holder.tvName.setText("⭐ " + app.appName);
            holder.tvName.setTextColor(Color.parseColor("#FFC107"));
        } else if (app.isFavorite && mIsGridMode) {
            holder.tvName.setText("⭐ " + app.appName);
            holder.tvName.setTextColor(Color.parseColor("#FFC107"));
        } else {
            holder.tvName.setText(app.appName);
            holder.tvName.setTextColor(Color.parseColor("#333333")); // Default dark text
        }

        boolean isActive = app.packageName != null && app.packageName.equals(mCurrentPackage);
        boolean isOnMain = app.packageName != null && app.packageName.equals(mMainPackage);
        
        if (holder.viewActiveIndicator != null) {
            holder.viewActiveIndicator.setVisibility((isActive || isOnMain) ? View.VISIBLE : View.GONE);
        }
        if (holder.btnToMain != null) {
            holder.btnToMain.setVisibility(isActive ? View.VISIBLE : View.GONE);
        }
        if (holder.btnToCluster != null) {
            holder.btnToCluster.setVisibility(isOnMain ? View.VISIBLE : View.GONE);
        }
        if (holder.btnKill != null) {
            holder.btnKill.setVisibility((isActive || isOnMain) ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return mApps.size();
    }

    public List<AppInfo> getApps() { return mApps; }

    AppInfo getAppAt(int position) {
        if (position >= 0 && position < mApps.size()) {
            return mApps.get(position);
        }
        return null;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView  tvName;
        final View      viewActiveIndicator;
        final Button    btnToMain;
        final Button    btnToCluster;
        final Button    btnKill;
        final CheckBox  cbAutoLaunch;

        ViewHolder(View itemView, final OnSendToDashboardListener listener, final AppListAdapter adapter) {
            super(itemView);
            ivIcon              = (ImageView) itemView.findViewById(R.id.iv_app_icon);
            tvName              = (TextView)  itemView.findViewById(R.id.tv_app_name);
            viewActiveIndicator = itemView.findViewById(R.id.view_active_indicator);
            btnToMain           = (Button)    itemView.findViewById(R.id.btn_to_main);
            btnToCluster        = (Button)    itemView.findViewById(R.id.btn_to_cluster);
            btnKill             = (Button)    itemView.findViewById(R.id.btn_kill_app);
            cbAutoLaunch        = (CheckBox)  itemView.findViewById(R.id.cb_auto_launch);

            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToDashboard(app);
                }
            });

            // Long click to trigger favorite toggle
            itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) {
                        listener.onToggleFavorite(app);
                        return true;
                    }
                    return false;
                }
            });

            if (btnToMain != null) btnToMain.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToMain(app);
                }
            });

            if (btnToCluster != null) btnToCluster.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToDashboard(app);
                }
            });

            if (btnKill != null) btnKill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onKillApp(app);
                }
            });
        }
    }
}
