package com.byd.dashcast.fission;

import android.content.Context;
import org.json.JSONArray;
import com.byd.dashcast.util.AppLogger;
import java.util.ArrayList;
import java.util.List;

public class LayoutPrefs {

    private static final String PREFS_NAME       = "dashcast_fission_layouts_v1";
    private static final String KEY_LIST         = "presets";
    private static final String KEY_FAVORITE_ID  = "favorite_layout_id";

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

    public static String getFavoriteId(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FAVORITE_ID, null);
    }

    public static void setFavoriteId(Context ctx, String id) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_FAVORITE_ID, id).apply();
    }

    /** Returns the favorite layout, or null if none set or the id no longer exists. */
    public static LayoutPreset getFavoriteLayout(Context ctx) {
        String id = getFavoriteId(ctx);
        if (id == null) return null;
        for (LayoutPreset p : load(ctx)) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    /**
     * Resolves a launchable automatic layout. Existing users who saved exactly one layout with
     * bound apps but never tapped "Favourite" are repaired automatically; multiple candidates
     * remain explicit to avoid launching an arbitrary layout.
     */
    public static LayoutPreset getAutoStartLayout(Context ctx) {
        String favoriteId = getFavoriteId(ctx);
        LayoutPreset selected = LayoutAutoStartPolicy.chooseLayout(favoriteId, load(ctx));
        if (selected != null && !selected.id.equals(favoriteId)) {
            setFavoriteId(ctx, selected.id);
            AppLogger.i("LayoutPrefs", "auto-start repaired sole usable layout as favourite: "
                    + selected.name + " (" + selected.id + ")");
        }
        return selected;
    }
}
