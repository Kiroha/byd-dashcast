package com.byd.dashcast.fission

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// @JvmField keeps direct field access (preset.slots, s.displayId = …,
// slot.packageName = …) working from the many Java call sites in the
// fission package during the Kotlin migration. @Throws preserves the
// `throws Exception` signature relied on by LayoutPrefs' try/catch.
class LayoutPreset(@JvmField var name: String) {

    @JvmField var id: String = UUID.randomUUID().toString()
    @JvmField var slots: MutableList<SlotDef> = ArrayList()

    class SlotDef(
        @JvmField var label: String,
        @JvmField var x: Int,
        @JvmField var y: Int,
        @JvmField var w: Int,
        @JvmField var h: Int
    ) {
        @JvmField var displayId: Int = -1

        /** Package name of the app bound to this zone, or null for a free zone. */
        @JvmField var packageName: String? = null

        fun copy(): SlotDef {
            val c = SlotDef(label, x, y, w, h)
            c.displayId = displayId
            c.packageName = packageName
            return c
        }

        fun toRect(): Rect = Rect(x, y, x + w, y + h)

        @Throws(Exception::class)
        fun toJson(): JSONObject {
            val o = JSONObject()
                .put("label", label)
                .put("x", x).put("y", y)
                .put("w", w).put("h", h)
            if (!packageName.isNullOrEmpty()) o.put("packageName", packageName)
            return o
        }

        companion object {
            @JvmStatic
            @Throws(Exception::class)
            fun fromJson(o: JSONObject): SlotDef {
                val s = SlotDef(
                    o.getString("label"),
                    o.getInt("x"), o.getInt("y"),
                    o.getInt("w"), o.getInt("h")
                )
                // Equivalent to the Java `optString("packageName", null)`: keep null
                // (the field default) when the key is absent or JSON-null, otherwise
                // take the stored value — without passing null to optString's @NonNull arg.
                if (o.has("packageName") && !o.isNull("packageName")) {
                    s.packageName = o.optString("packageName")
                }
                return s
            }
        }
    }

    fun nextSlotLabel(): String = "Zone " + (slots.size + 1)

    /**
     * A detached copy, slots included.
     *
     * The editor drags the live object, so anything that holds the same reference sees every
     * unsaved move. [SlotDef.copy] already existed for the same reason one level down; this is the
     * whole-preset version, so a saved layout is a snapshot rather than a window onto the canvas.
     */
    fun copy(): LayoutPreset {
        val c = LayoutPreset(name)
        c.id = id
        for (s in slots) c.slots.add(s.copy())
        return c
    }

    @Throws(Exception::class)
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (s in slots) arr.put(s.toJson())
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("slots", arr)
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun fromJson(o: JSONObject): LayoutPreset {
            val p = LayoutPreset(o.getString("name"))
            p.id = o.getString("id")
            val arr = o.getJSONArray("slots")
            for (i in 0 until arr.length()) {
                p.slots.add(SlotDef.fromJson(arr.getJSONObject(i)))
            }
            return p
        }
    }
}
