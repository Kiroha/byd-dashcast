package com.byd.dashcast.fission;

import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LayoutPreset {

    public String         id;
    public String         name;
    public List<SlotDef>  slots = new ArrayList<>();

    public LayoutPreset(String name) {
        this.id   = UUID.randomUUID().toString();
        this.name = name;
    }

    public static class SlotDef {
        public String label;
        public int    x, y, w, h;
        public int    displayId = -1;

        public SlotDef(String label, int x, int y, int w, int h) {
            this.label = label;
            this.x = x; this.y = y;
            this.w = w; this.h = h;
        }

        public Rect toRect() {
            return new Rect(x, y, x + w, y + h);
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("label", label)
                    .put("x", x).put("y", y)
                    .put("w", w).put("h", h);
        }

        public static SlotDef fromJson(JSONObject o) throws Exception {
            return new SlotDef(o.getString("label"),
                    o.getInt("x"), o.getInt("y"),
                    o.getInt("w"), o.getInt("h"));
        }
    }

    public String nextSlotLabel() {
        return "Zone " + (slots.size() + 1);
    }

    public JSONObject toJson() throws Exception {
        JSONArray arr = new JSONArray();
        for (SlotDef s : slots) arr.put(s.toJson());
        return new JSONObject()
                .put("id",    id)
                .put("name",  name)
                .put("slots", arr);
    }

    public static LayoutPreset fromJson(JSONObject o) throws Exception {
        LayoutPreset p  = new LayoutPreset(o.getString("name"));
        p.id            = o.getString("id");
        JSONArray arr   = o.getJSONArray("slots");
        for (int i = 0; i < arr.length(); i++) {
            p.slots.add(SlotDef.fromJson(arr.getJSONObject(i)));
        }
        return p;
    }
}
