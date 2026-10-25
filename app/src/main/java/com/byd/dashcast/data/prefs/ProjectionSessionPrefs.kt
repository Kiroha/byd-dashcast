package com.byd.dashcast.data.prefs

import android.content.Context
import android.graphics.Rect
import com.byd.dashcast.fission.LayoutPreset
import com.byd.dashcast.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/** The resumable projection, separate from the cleanup history of possibly stranded tasks. */
object ProjectionSessionPrefs {
    private const val KEY = "last_projection_session_v1"
    data class App(val packageName: String, val bounds: Rect? = null)
    data class Session(val apps: List<App>, val layout: LayoutPreset? = null)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized fun read(ctx: Context): Session? = try {
        val raw = prefs(ctx).getString(KEY, null)
        if (raw == null) null else {
            val json = JSONObject(raw)
            if (json.has("layout")) {
                val layout = LayoutPreset.fromJson(json.getJSONObject("layout"))
                require(layout.slots.size in 1..16)
                layout.slots.forEach { require(it.x >= 0 && it.y >= 0 && it.w in 1..8192 && it.h in 1..8192) }
                val apps = layout.slots.mapNotNull { it.packageName }.map { App(it) }
                require(apps.isNotEmpty() && apps.all { validPackage(it.packageName) })
                Session(apps, layout)
            } else {
                val entries = json.getJSONArray("apps")
                require(entries.length() in 1..2)
                val apps = (0 until entries.length()).map { index ->
                    val entry = entries.getJSONObject(index)
                    val pkg = entry.getString("pkg")
                    require(validPackage(pkg))
                    val rect = entry.optJSONArray("bounds")?.let {
                        require(it.length() == 4)
                        Rect(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)).also { r ->
                            require(r.left >= 0 && r.top >= 0 && r.right <= 8192 && r.bottom <= 8192 && !r.isEmpty)
                        }
                    }
                    App(pkg, rect)
                }
                require(apps.map { it.packageName }.distinct().size == apps.size)
                Session(apps)
            }
        }
    } catch (error: Exception) {
        AppLogger.w("ProjectionResume", "saved session unavailable: ${error.message}")
        null
    }

    @Synchronized fun rememberClassic(ctx: Context, pkg: String, bounds: Rect?) {
        if (!validPackage(pkg)) return
        val previous = read(ctx)?.takeIf { it.layout == null }?.apps.orEmpty()
        val apps = if (bounds == null) listOf(App(pkg)) else
            previous.toMutableList().apply {
                val index = indexOfFirst { it.packageName == pkg }
                if (index >= 0) this[index] = App(pkg, Rect(bounds)) else add(App(pkg, Rect(bounds)))
            }.takeLast(2)
        write(ctx, Session(apps))
    }

    @Synchronized fun rememberLayout(ctx: Context, layout: LayoutPreset, running: Set<String>) {
        val snapshot = layout.copy()
        snapshot.slots.forEach { if (it.packageName !in running) it.packageName = null }
        val apps = snapshot.slots.mapNotNull { it.packageName }.map { App(it) }
        if (apps.isEmpty()) clear(ctx) else write(ctx, Session(apps, snapshot))
    }

    @Synchronized fun replaceClassic(ctx: Context, apps: List<App>) {
        require(apps.size <= 2 && apps.all { validPackage(it.packageName) })
        if (apps.isEmpty()) clear(ctx) else write(ctx, Session(apps))
    }

    @Synchronized fun remove(ctx: Context, pkg: String) {
        val session = read(ctx) ?: return
        val remaining = session.apps.filter { it.packageName != pkg }
        if (remaining.isEmpty()) clear(ctx)
        else if (session.layout != null) rememberLayout(ctx, session.layout, remaining.map { it.packageName }.toSet())
        else write(ctx, Session(remaining))
    }

    @Synchronized fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }

    private fun write(ctx: Context, session: Session) {
        val json = if (session.layout != null) JSONObject().put("layout", session.layout.toJson()) else {
            val apps = JSONArray()
            session.apps.forEach { app ->
                val entry = JSONObject().put("pkg", app.packageName)
                app.bounds?.let { entry.put("bounds", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
                apps.put(entry)
            }
            JSONObject().put("apps", apps)
        }
        prefs(ctx).edit().putString(KEY, json.toString()).apply()
    }

    private fun validPackage(pkg: String) = pkg.length <= 255 && pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
}
