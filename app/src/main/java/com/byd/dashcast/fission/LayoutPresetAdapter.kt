package com.byd.dashcast.fission

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.byd.dashcast.R
import com.google.android.material.button.MaterialButton

/**
 * Kotlin port of the former LayoutPresetAdapter.java — behaviour identical.
 *
 * Interop notes for the Java call site (LayoutManagerActivity.java):
 *  - the single 3-arg constructor `(List, String, Callbacks)` is preserved verbatim; the primary
 *    constructor declares no default values, so exactly that one JVM signature is emitted.
 *  - `activeId` / `id` stay nullable: the activity passes `null` (no favourite, deactivation),
 *    and the Java compared them with an explicit null guard.
 *  - [VH] is public where the Java had it package-private: Kotlin refuses to expose a less-visible
 *    type through the public `RecyclerView.Adapter<VH>` supertype and the public overrides. Same
 *    shape as the already-migrated LogAdapter.VH; nothing outside this file references it.
 */
class LayoutPresetAdapter(
    items: List<LayoutPreset>,
    activeId: String?,
    cb: Callbacks
) : RecyclerView.Adapter<LayoutPresetAdapter.VH>() {

    interface Callbacks {
        fun onSelect(p: LayoutPreset)
        fun onEdit(p: LayoutPreset)
        fun onActivate(p: LayoutPreset)
        fun onDeactivate()
        fun onDelete(p: LayoutPreset)
    }

    private var mItems: List<LayoutPreset> = items
    private var mActiveId: String? = activeId
    private var mSelectedId: String? = null
    private val mCb: Callbacks = cb

    @SuppressLint("NotifyDataSetChanged")
    fun update(items: List<LayoutPreset>, activeId: String?) {
        mItems = items
        mActiveId = activeId
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setSelected(id: String?) {
        mSelectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_layout_preset_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = mItems[pos]
        val isActive = mActiveId != null && mActiveId == p.id
        val isSelected = mSelectedId != null && mSelectedId == p.id

        h.tvName.text = p.name
        h.tvMeta.text = h.tvMeta.context.getString(R.string.fission_zone_count, p.slots.size)

        // Card background state
        if (isActive) {
            h.itemView.setBackgroundResource(R.drawable.bg_card_active_layout)
        } else if (isSelected) {
            h.itemView.setBackgroundResource(R.drawable.bg_card_selected)
        } else {
            h.itemView.setBackgroundResource(R.drawable.bg_card_default)
        }

        // Active badge
        h.tvActiveBadge.visibility = if (isActive) View.VISIBLE else View.GONE

        // Activate / Deactivate button
        if (isActive) {
            h.btnActivate.setText(R.string.layout_preset_deactivate)
            h.btnActivate.setOnClickListener { mCb.onDeactivate() }
        } else {
            h.btnActivate.setText(R.string.layout_preset_activate)
            h.btnActivate.setOnClickListener { mCb.onActivate(p) }
        }

        h.btnEdit.setOnClickListener { mCb.onEdit(p) }
        h.btnDelete.setOnClickListener { mCb.onDelete(p) }
        h.itemView.setOnClickListener { mCb.onSelect(p) }
    }

    override fun getItemCount(): Int = mItems.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.lm_card_name)
        val tvMeta: TextView = v.findViewById(R.id.lm_card_meta)
        val tvActiveBadge: TextView = v.findViewById(R.id.lm_card_active_badge)
        val btnActivate: MaterialButton = v.findViewById(R.id.lm_card_btn_activate)
        val btnEdit: MaterialButton = v.findViewById(R.id.lm_card_btn_edit)
        val btnDelete: MaterialButton = v.findViewById(R.id.lm_card_btn_delete)
    }
}
