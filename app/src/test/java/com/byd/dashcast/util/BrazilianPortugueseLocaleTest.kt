package com.byd.dashcast.util

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import com.byd.dashcast.R
import com.byd.dashcast.ui.welcome.WelcomeActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrazilianPortugueseLocaleTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var originalLocale: Locale
    private var originalLanguage: String? = null
    private var originalSetup = false

    @Before fun rememberPreferences() {
        originalLocale = Locale.getDefault()
        originalLanguage = LocaleHelper.getSavedLanguage(context)
        originalSetup = LocaleHelper.isSetupDone(context)
        context.getSharedPreferences(LocaleHelper.PREF_FILE, Context.MODE_PRIVATE).edit()
            .remove(LocaleHelper.PREF_LANGUAGE).remove(LocaleHelper.PREF_SETUP_DONE).commit()
    }

    @After fun restorePreferences() {
        Locale.setDefault(originalLocale)
        context.getSharedPreferences(LocaleHelper.PREF_FILE, Context.MODE_PRIVATE).edit()
            .putString(LocaleHelper.PREF_LANGUAGE, originalLanguage)
            .putBoolean(LocaleHelper.PREF_SETUP_DONE, originalSetup).commit()
    }

    @Test fun `saved Brazilian locale restores country resources and switches back to French`() {
        val selected = LocaleHelper.setLocale(context, LocaleHelper.LANG_PT_BR)
        assertEquals("pt-BR", LocaleHelper.getSavedLanguage(context))
        assertEquals("pt", selected.resources.configuration.locales[0].language)
        assertEquals("BR", selected.resources.configuration.locales[0].country)
        assertEquals("Satélite conectado", selected.getString(R.string.satellite_connection_connected))
        assertEquals("Aguardando orientações de navegação", selected.getString(R.string.satellite_guidance_waiting))
        val recreated = LocaleHelper.applyLocale(context)
        assertEquals("pt-BR", recreated.resources.configuration.locales[0].toLanguageTag())
        assertEquals("Voltar à exibição BYD", recreated.getString(R.string.diag_cluster_rcc_restore))
        assertEquals("Mapa em tela cheia + instrumentos BYD", recreated.getString(R.string.fs_page_title))
        val french = LocaleHelper.setLocale(context, LocaleHelper.LANG_FR)
        assertEquals("fr", LocaleHelper.getSavedLanguage(context))
        assertEquals("Récepteur désactivé", french.getString(R.string.satellite_connection_disabled))
    }

    @Test fun `welcome button saves Brazilian choice and opens main without starting its services`() {
        val controller = Robolectric.buildActivity(WelcomeActivity::class.java).setup()
        try {
            val button = controller.get().findViewById<Button>(R.id.btn_lang_pt_br)
            assertTrue(button.text.contains("Português (Brasil)"))
            button.performClick()
            assertEquals("pt-BR", LocaleHelper.getSavedLanguage(context))
            assertTrue(LocaleHelper.isSetupDone(context))
            assertEquals("com.byd.dashcast.MainActivity", shadowOf(controller.get()).nextStartedActivity.component!!.className)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun `Portuguese picker remains scrollable and legible in short landscape at larger font scale`() {
        val config = Configuration(context.resources.configuration).apply {
            screenWidthDp = 640
            screenHeightDp = 360
            orientation = Configuration.ORIENTATION_LANDSCAPE
            fontScale = 1.3f
            setLocale(Locale.forLanguageTag("pt-BR"))
        }
        val localized = context.createConfigurationContext(config)
        val themed = android.view.ContextThemeWrapper(localized, R.style.AppTheme)
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_welcome, null) as ScrollView
        val button = root.findViewById<Button>(R.id.btn_lang_pt_br)
        val density = themed.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        assertTrue(root.getChildAt(0).height > root.height)
        assertTrue(button.height >= (48 * density).toInt())
        assertTrue(button.width > 0)
        assertNull(button.ellipsize)
        assertTrue(button.layout.lineCount >= 1)
        for (line in 0 until button.layout.lineCount) {
            assertEquals(0, button.layout.getEllipsisCount(line))
            assertTrue(button.layout.getLineWidth(line) <= button.width - button.compoundPaddingLeft - button.compoundPaddingRight + 1f)
        }
        assertTrue(button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
        assertEquals(button.text.length, button.layout.getLineEnd(button.layout.lineCount - 1))
    }
}
