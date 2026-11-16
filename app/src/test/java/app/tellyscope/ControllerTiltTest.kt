// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.view.Surface
import app.tellyscope.ui.screenTilt
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerTiltTest {
    private val g = 9.81f

    @Test fun flatIsNoTilt() {
        val t = screenTilt(0f, 0f, g, Surface.ROTATION_90)
        assertEquals(0f, t.x, 0.01f)
        assertEquals(0f, t.y, 0.01f)
    }

    @Test fun sidewaysTheLeftEdgeDownPointsLeft() {
        // Turned counter-clockwise (ROTATION_90), the screen's left edge is the phone's top. With it
        // 20° down the reading leans toward the phone's bottom, -y.
        val lean = g * kotlin.math.sin(Math.toRadians(20.0)).toFloat()
        val t = screenTilt(0f, -lean, kotlin.math.sqrt(g * g - lean * lean), Surface.ROTATION_90)
        assertEquals(-20f, t.x, 0.1f)
        assertEquals(0f, t.y, 0.1f)
    }

    @Test fun theNearEdgeDownPointsDown() {
        // Upright, the near (bottom) edge 20° down: the reading leans toward the top, +y.
        val lean = g * kotlin.math.sin(Math.toRadians(20.0)).toFloat()
        val t = screenTilt(0f, lean, kotlin.math.sqrt(g * g - lean * lean), Surface.ROTATION_0)
        assertEquals(0f, t.x, 0.1f)
        assertEquals(20f, t.y, 0.1f)
    }
}
