package com.byd.dashcast.fission;

import android.content.Context;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public class LayoutPrefs {

    private static final String PREFS_NAME = "dashcast_fission_layouts_v1";
    private static final String KEY_LIST   = "presets";

    public static List<LayoutPreset> load(Context ctx) {
        List<LayoutPreset> result = new ArrayList<>();
        try {
            String json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LIST, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(LayoutPreset.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return result;
    }

    public static void save(Context ctx, List<LayoutPreset> presets) {
        try {
            JSONArray arr = new JSONArray();
            for (LayoutPreset p : presets) arr.put(p.toJson());
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LIST, arr.toString()).apply();
        } catch (Exception ignored) {}
    }
}
