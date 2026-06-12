package com.byd.dashcast.fission;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.byd.dashcast.R;
import com.google.android.material.button.MaterialButton;
import java.util.List;

public class LayoutPresetAdapter extends RecyclerView.Adapter<LayoutPresetAdapter.VH> {

    public interface Callbacks {
        void onSelect(LayoutPreset p);
        void onEdit(LayoutPreset p);
        void onActivate(LayoutPreset p);
        void onDeactivate();
        void onDelete(LayoutPreset p);
    }

    private List<LayoutPreset> mItems;
    private String             mActiveId;
    private String             mSelectedId;
    private final Callbacks    mCb;

    public LayoutPresetAdapter(List<LayoutPreset> items, String activeId, Callbacks cb) {
        mItems    = items;
        mActiveId = activeId;
        mCb       = cb;
    }

    public void update(List<LayoutPreset> items, String activeId) {
        mItems    = items;
        mActiveId = activeId;
        notifyDataSetChanged();
    }

    public void setSelected(String id) {
        mSelectedId = id;
        notifyDataSetChanged();
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_layout_preset_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(VH h, int pos) {
        LayoutPreset p       = mItems.get(pos);
        boolean isActive     = mActiveId   != null && mActiveId.equals(p.id);
        boolean isSelected   = mSelectedId != null && mSelectedId.equals(p.id);

        h.tvName.setText(p.name);
        h.tvMeta.setText(h.tvMeta.getContext().getString(R.string.fission_zone_count, p.slots.size()));

        // Card background state
        if (isActive) {
            h.itemView.setBackgroundResource(R.drawable.bg_card_active_layout);
        } else if (isSelected) {
            h.itemView.setBackgroundResource(R.drawable.bg_card_selected);
        } else {
            h.itemView.setBackgroundResource(R.drawable.bg_card_default);
        }

        // Active badge
        h.tvActiveBadge.setVisibility(isActive ? View.VISIBLE : View.GONE);

        // Activate / Deactivate button
        if (isActive) {
            h.btnActivate.setText(R.string.layout_preset_deactivate);
            h.btnActivate.setOnClickListener(v -> mCb.onDeactivate());
        } else {
            h.btnActivate.setText(R.string.layout_preset_activate);
            h.btnActivate.setOnClickListener(v -> mCb.onActivate(p));
        }

        h.btnEdit.setOnClickListener(v   -> mCb.onEdit(p));
        h.btnDelete.setOnClickListener(v -> mCb.onDelete(p));
        h.itemView.setOnClickListener(v  -> mCb.onSelect(p));
    }

    @Override public int getItemCount() { return mItems.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView       tvName;
        final TextView       tvMeta;
        final TextView       tvActiveBadge;
        final MaterialButton btnActivate;
        final MaterialButton btnEdit;
        final MaterialButton btnDelete;

        VH(View v) {
            super(v);
            tvName       = v.findViewById(R.id.lm_card_name);
            tvMeta       = v.findViewById(R.id.lm_card_meta);
            tvActiveBadge = v.findViewById(R.id.lm_card_active_badge);
            btnActivate  = v.findViewById(R.id.lm_card_btn_activate);
            btnEdit      = v.findViewById(R.id.lm_card_btn_edit);
            btnDelete    = v.findViewById(R.id.lm_card_btn_delete);
        }
    }
}
