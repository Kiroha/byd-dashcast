package com.byd.dashcast;

import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.byd.dashcast.model.AppInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    public interface OnSendToDashboardListener {
        void onSendToDashboard(AppInfo app);
        void onSendToMain(AppInfo app);
        void onKillApp(AppInfo app);
    }

    private List<AppInfo> mApps = new ArrayList<>();
    private final OnSendToDashboardListener mListener;
    // Package currently displayed on the cluster (green indicator)
    private String mCurrentPackage = null;
    // Package currently on the main screen ("→ Cluster" button visible)
    private String mMainPackage = null;
    // Cache packageName → index dans mApps pour notifyItemChanged() O(1)
    private final HashMap<String, Integer> mPackageIndexMap = new HashMap<>();

    public AppListAdapter(OnSendToDashboardListener listener) {
        mListener = listener;
    }

    public void setApps(List<AppInfo> apps) {
        mApps = apps;
        // Reconstruire l'index pour notifyPackageChanged() O(1)
        mPackageIndexMap.clear();
        for (int i = 0; i < apps.size(); i++) {
            mPackageIndexMap.put(apps.get(i).packageName, i);
        }
        notifyDataSetChanged();
    }

    /** Updates the indicator for the app currently on the cluster. */
    public void setCurrentPackage(String packageName) {
        String old = mCurrentPackage;
        mCurrentPackage = packageName;
        notifyPackageChanged(old);
        notifyPackageChanged(packageName);
    }

    /** Updates the indicator for the app currently on the main display. */
    public void setMainPackage(String packageName) {
        String old = mMainPackage;
        mMainPackage = packageName;
        notifyPackageChanged(old);
        notifyPackageChanged(packageName);
    }

    /**
     * Notifies only the item matching the given package — O(1) via HashMap.
     */
    private void notifyPackageChanged(String packageName) {
        if (packageName == null) return;
        Integer idx = mPackageIndexMap.get(packageName);
        if (idx != null) notifyItemChanged(idx);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new ViewHolder(v, mListener, this);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        final AppInfo app = mApps.get(position);
        holder.ivIcon.setImageDrawable(app.icon);
        holder.tvName.setText(app.appName);

        // Green indicator + buttons: managed based on state (cluster or main display)
        boolean isActive = app.packageName != null && app.packageName.equals(mCurrentPackage);
        boolean isOnMain = app.packageName != null && app.packageName.equals(mMainPackage);
        holder.viewActiveIndicator.setVisibility((isActive || isOnMain) ? View.VISIBLE : View.GONE);
        holder.btnToMain.setVisibility(isActive ? View.VISIBLE : View.GONE);
        holder.btnToCluster.setVisibility(isOnMain ? View.VISIBLE : View.GONE);
        holder.btnKill.setVisibility((isActive || isOnMain) ? View.VISIBLE : View.GONE);
    }

    @Override
    public int getItemCount() {
        return mApps.size();
    }

    // Helper method for the ViewHolder to get the AppInfo safely
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

        ViewHolder(View itemView, final OnSendToDashboardListener listener, final AppListAdapter adapter) {
            super(itemView);
            ivIcon              = (ImageView) itemView.findViewById(R.id.iv_app_icon);
            tvName              = (TextView)  itemView.findViewById(R.id.tv_app_name);
            viewActiveIndicator = itemView.findViewById(R.id.view_active_indicator);
            btnToMain           = (Button)    itemView.findViewById(R.id.btn_to_main);
            btnToCluster        = (Button)    itemView.findViewById(R.id.btn_to_cluster);
            btnKill             = (Button)    itemView.findViewById(R.id.btn_kill_app);

            // Tap on the entire row = send to cluster
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToDashboard(app);
                }
            });

            btnToMain.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToMain(app);
                }
            });

            btnToCluster.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToDashboard(app);
                }
            });

            btnKill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onKillApp(app);
                }
            });
        }
    }
}
