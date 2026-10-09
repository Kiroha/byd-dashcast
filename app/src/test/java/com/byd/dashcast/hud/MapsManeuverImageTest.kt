package com.byd.dashcast.hud

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Icon
import com.byd.dashcast.system.CanBusController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native pixels exercise the actual field PNG, rather than Robolectric's legacy bitmap stub. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsManeuverImageTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()

    @Test fun `original field capture and its horizontal reflection resolve distinct directions`() {
        val left = capture()
        val right = Bitmap.createBitmap(left, 0, 0, left.width, left.height,
            Matrix().apply { setScale(-1f, 1f) }, false)
        try {
            val a = MapsManeuverImage.read(ctx, Icon.createWithBitmap(left))
            val b = MapsManeuverImage.read(ctx, Icon.createWithBitmap(right))
            assertEquals(a.identity, CanBusController.ICON_TURN_LEFT, a.iconId)
            assertEquals(b.identity, CanBusController.ICON_TURN_RIGHT, b.iconId)
            assertNotEquals(a.identity, b.identity)
            assertFalse(left.isRecycled)
            assertFalse(right.isRecycled)
        } finally { left.recycle(); right.recycle() }
    }

    @Test fun `blank solid coloured and URI icons cannot become a turn`() {
        val bitmap = Bitmap.createBitmap(54, 54, Bitmap.Config.ARGB_8888)
        try {
            for (colour in listOf(Color.TRANSPARENT, Color.WHITE, Color.BLUE)) {
                bitmap.eraseColor(colour)
                assertEquals(-1, MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)).iconId)
            }
            assertEquals(-1, MapsManeuverImage.read(ctx,
                Icon.createWithContentUri("content://private/nav-image")).iconId)
            assertEquals(-1, MapsManeuverImage.read(ctx, null).iconId)
        } finally { bitmap.recycle() }
    }

    private fun capture(): Bitmap = requireNotNull(BitmapFactory.decodeStream(
        javaClass.getResourceAsStream("/navigation/maps-left-seal-20261009.png")))
}
