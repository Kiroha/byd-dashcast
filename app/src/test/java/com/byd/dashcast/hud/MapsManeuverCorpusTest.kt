package com.byd.dashcast.hud

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import com.byd.dashcast.system.CanBusController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fixtures come from the APK's notification SVGs, independently rasterized with Cairo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsManeuverCorpusTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val manifest get() = JSONObject(requireNotNull(javaClass.getResourceAsStream(
        "/navigation/maps-26.33/manifest.json")).bufferedReader().use { it.readText() })

    @Test fun `notification SVG corpus resolves all labelled directions`() {
        val entries = manifest.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val name = entry.getString("name")
            val bitmap = fixture(name)
            try {
                val result = MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap))
                assertEquals("$name: ${result.identity}", entry.getInt("iconId"), result.iconId)
                assertEquals(name, if (entry.isNull("clockwise")) null else entry.getBoolean("clockwise"),
                    result.roundaboutClockwise)
                assertFalse(bitmap.isRecycled)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `resampled notifications retain direction across common bitmap sizes`() {
        val entries = manifest.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val name = entry.getString("name")
            val original = fixture(name)
            try {
                for (edge in listOf(48, 64, 72, 96, 108)) {
                    val scaled = Bitmap.createScaledBitmap(original, edge, edge, true)
                    try {
                        assertEquals("$name at $edge", entry.getInt("iconId"),
                            MapsManeuverImage.read(ctx, Icon.createWithBitmap(scaled)).iconId)
                    } finally { scaled.recycle() }
                }
            } finally { original.recycle() }
        }
    }

    @Test fun `opaque black and white notification backgrounds retain roundabout direction`() {
        for (name in listOf("ic_roundabout_left", "ic_roundabout_straight",
                "ic_roundabout_right_mirrored", "ic_u_turn", "ic_straight")) {
            val source = fixture(name)
            try {
                val expected = MapsManeuverImage.read(ctx, Icon.createWithBitmap(source)).iconId
                for (background in listOf(Color.BLACK, Color.WHITE)) {
                    val bitmap = Bitmap.createBitmap(54, 54, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(background)
                        val paint = Paint().apply {
                            colorFilter = android.graphics.PorterDuffColorFilter(
                                if (background == Color.WHITE) Color.BLACK else Color.WHITE,
                                android.graphics.PorterDuff.Mode.SRC_IN)
                        }
                        Canvas(bitmap).drawBitmap(source, 0f, 0f, paint)
                        assertTrue(name, expected > 0)
                        assertEquals("$name on $background", expected,
                            MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)).iconId)
                    } finally { bitmap.recycle() }
                }
            } finally { source.recycle() }
        }
    }

    @Test fun `unknown and transport pictograms never become a driving maneuver`() {
        for (name in listOf("da_turn_unknown", "da_turn_ferry", "ferry_train")) {
            val bitmap = fixture(name)
            try {
                assertEquals(name, -1, MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)).iconId)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `cropped or combined guidance glyphs cannot silently become a different turn`() {
        val source = fixture("ic_roundabout_left")
        val composite = Bitmap.createBitmap(108, 54, Bitmap.Config.ARGB_8888)
        try {
            Canvas(composite).apply {
                drawBitmap(source, 0f, 0f, null)
                drawBitmap(source, 54f, 0f, null)
            }
            assertEquals(-1, MapsManeuverImage.read(ctx, Icon.createWithBitmap(composite)).iconId)
            val cropped = Bitmap.createBitmap(source, 0, 18, source.width, source.height - 18)
            try {
                assertEquals(-1, MapsManeuverImage.read(ctx, Icon.createWithBitmap(cropped)).iconId)
            } finally { cropped.recycle() }
        } finally { source.recycle(); composite.recycle() }
    }

    @Test fun `flattening a roundabout highlighted path removes reliable circulation evidence`() {
        val source = fixture("ic_roundabout_left")
        val bitmap = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
        source.recycle()
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) >= 128) bitmap.setPixel(x, y, Color.WHITE)
                else bitmap.setPixel(x, y, Color.TRANSPARENT)
            }
            assertEquals(-1, MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)).iconId)
        } finally { bitmap.recycle() }
    }

    @Test fun `canonical directions retain the explicit domain mapping`() {
        val cases = listOf("ic_straight" to CanBusController.ICON_STRAIGHT_SOLID,
            "ic_u_turn" to CanBusController.ICON_U_TURN_LEFT,
            "ic_u_turn_mirrored" to CanBusController.ICON_U_TURN_RIGHT,
            "ic_roundabout_left" to CanBusController.ICON_ROUNDABOUT_3_4_LEFT,
            "ic_roundabout_right" to CanBusController.ICON_ROUNDABOUT_1_4_LEFT,
            "ic_roundabout_straight" to CanBusController.ICON_ROUNDABOUT_STRAIGHT_L,
            "ic_roundabout_straight_mirrored" to CanBusController.ICON_ROUNDABOUT_STRAIGHT_R)
        for ((name, expected) in cases) {
            val bitmap = fixture(name)
            try {
                assertEquals(name, expected, MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)).iconId)
            } finally { bitmap.recycle() }
        }
    }

    private fun fixture(name: String): Bitmap = requireNotNull(BitmapFactory.decodeStream(
        javaClass.getResourceAsStream("/navigation/maps-26.33/$name.png")))
}
