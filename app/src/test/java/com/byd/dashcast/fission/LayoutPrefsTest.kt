package com.byd.dashcast.fission

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LayoutPrefsTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("layout-prefs-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        LayoutPrefs.setPrefsForTesting(prefs)
    }

    @After
    fun tearDown() {
        LayoutPrefs.setPrefsForTesting(null)
    }

    @Test
    fun `malformed primary is all or nothing and remains quarantined`() {
        prefs.edit().putString("presets", "[{\"id\":\"first\",\"name\":\"ok\",\"slots\":[]}, broken]").commit()

        val result = LayoutPrefs.loadResult(context)

        assertEquals(LayoutPrefs.LoadStatus.CORRUPT, result.status)
        assertTrue(result.presets.isEmpty())
        assertTrue(prefs.getString("presets_corrupt", "")!!.contains("broken"))
    }

    @Test
    fun `corrupt primary recovers complete valid backup`() {
        val preset = LayoutPreset("Recovered")
        val valid = org.json.JSONArray().put(preset.toJson()).toString()
        prefs.edit()
            .putString("presets", "not-json")
            .putString("presets_backup", valid)
            .commit()

        val result = LayoutPrefs.loadResult(context)

        assertEquals(LayoutPrefs.LoadStatus.RECOVERED, result.status)
        assertEquals(listOf("Recovered"), result.presets.map { it.name })
        assertEquals(valid, prefs.getString("presets", null))
    }

    @Test
    fun `failed commits are reported instead of assumed successful`() {
        val delegate = prefs
        LayoutPrefs.setPrefsForTesting(object : SharedPreferences by delegate {
            override fun edit(): SharedPreferences.Editor {
                val editor = delegate.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean = false
                }
            }
        })

        assertFalse(LayoutPrefs.save(context, listOf(LayoutPreset("Unsaved"))))
        assertFalse(LayoutPrefs.saveState(context, emptyList(), null))
        assertFalse(LayoutPrefs.setFavoriteId(context, "missing"))
    }

    @Test
    fun `successful save keeps previous valid list as recovery backup`() {
        assertTrue(LayoutPrefs.save(context, listOf(LayoutPreset("First"))))
        assertTrue(LayoutPrefs.save(context, listOf(LayoutPreset("Second"))))

        prefs.edit().putString("presets", "corrupt").commit()
        val recovered = LayoutPrefs.loadResult(context)
        assertEquals(listOf("First"), recovered.presets.map { it.name })
    }

    @Test
    fun `preset deletion and favorite clearing commit atomically`() {
        val preset = LayoutPreset("Active")
        assertTrue(LayoutPrefs.saveState(context, listOf(preset), preset.id))

        assertTrue(LayoutPrefs.saveState(context, emptyList(), null))

        assertTrue(LayoutPrefs.load(context).isEmpty())
        assertEquals(null, LayoutPrefs.getFavoriteId(context))
    }

    @Test
    fun `late activation cannot select a preset deleted from persisted state`() {
        val preset = LayoutPreset("Deleted")
        assertTrue(LayoutPrefs.saveState(context, listOf(preset), null))
        assertTrue(LayoutPrefs.saveState(context, emptyList(), null))

        assertFalse(LayoutPrefs.setFavoriteIdIfPresent(context, preset.id))
        assertEquals(LayoutPrefs.FavoriteWriteStatus.MISSING,
            LayoutPrefs.setFavoriteIdIfPresentResult(context, preset.id))
        assertEquals(null, LayoutPrefs.getFavoriteId(context))
    }

    @Test
    fun `orphaned favorite is ignored and cleared`() {
        val preset = LayoutPreset("Existing")
        assertTrue(LayoutPrefs.save(context, listOf(preset)))
        assertTrue(LayoutPrefs.setFavoriteId(context, "orphan"))

        assertEquals(null, LayoutPrefs.getValidFavoriteId(context, listOf(preset)))
        assertEquals(null, LayoutPrefs.getFavoriteId(context))
    }

    @Test
    fun `backup used in memory is not called durable when repair commit fails`() {
        val preset = LayoutPreset("Backup")
        val valid = org.json.JSONArray().put(preset.toJson()).toString()
        prefs.edit().putString("presets", "corrupt")
            .putString("presets_backup", valid).commit()
        val delegate = prefs
        LayoutPrefs.setPrefsForTesting(object : SharedPreferences by delegate {
            override fun edit(): SharedPreferences.Editor {
                val editor = delegate.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean = false
                }
            }
        })

        val result = LayoutPrefs.loadResult(context)

        assertEquals(LayoutPrefs.LoadStatus.STORAGE_ERROR, result.status)
        assertEquals(listOf("Backup"), result.presets.map { it.name })
        assertEquals("corrupt", delegate.getString("presets", null))
    }

    @Test
    fun `manager checks persistence before success UI or runtime teardown`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it,
                "app/src/main/java/com/byd/dashcast/fission/LayoutManagerActivity.kt").isFile }
        assertTrue("could not locate the repo root", root != null)
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/LayoutManagerActivity.kt").readText()
        val save = source.substringAfter("private fun saveLayout()")
            .substringBefore("private fun deleteLayout")
        val deactivate = source.substringAfter("private fun deactivateLayout(persistSelection: Boolean): Boolean")
            .substringBefore("private void purgeDaemonSlotsAsync")

        // indexOf-only ordering passes VACUOUSLY when the earlier call is deleted (-1 < n).
        // Proven by mutation: removing saveLayout's persistence check left this green.
        val saveCheck = save.indexOf("if (!LayoutPrefs.save")
        val saveToast = save.indexOf("R.string.lm_layout_saved_toast")
        assertTrue("saveLayout must check persistence before claiming success", saveCheck >= 0)
        assertTrue(saveToast >= 0)
        assertTrue(saveCheck < saveToast)

        val favWrite = deactivate.indexOf("LayoutPrefs.setFavoriteId")
        val teardown = deactivate.indexOf("FissionOrchestrator.stopAutoOrchestrator")
        assertTrue("deactivate must persist the selection before tearing down", favWrite >= 0)
        assertTrue(teardown >= 0)
        assertTrue(favWrite < teardown)
        assertTrue(source.contains("LayoutPrefs.setFavoriteIdIfPresentResult(appCtx, preset.id)"))
    }
}