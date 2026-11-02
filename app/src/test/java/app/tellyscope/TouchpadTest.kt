// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.RemoteKey
import app.tellyscope.data.KeyHaptics
import app.tellyscope.ui.repeatWhileStill
import app.tellyscope.ui.swipeKey
import java.io.File
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TouchpadTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun aSwipeSendsTheArrowItWentFurthestIn() {
        assertEquals(RemoteKey.RIGHT, swipeKey(60f, 10f))
        assertEquals(RemoteKey.LEFT, swipeKey(-60f, -30f))
        assertEquals(RemoteKey.DOWN, swipeKey(-10f, 60f))
        assertEquals(RemoteKey.UP, swipeKey(20f, -60f))
        // Exactly diagonal reads as sideways, the way lists on a TV mostly scroll.
        assertEquals(RemoteKey.RIGHT, swipeKey(40f, 40f))
    }

    @Test fun aSwipeStillMovingSendsOneArrow() = runTest {
        var presses = 0
        // A slow swipe: a second of the finger never settling, then the lift.
        repeatWhileStill(RemoteKey.RIGHT, held = { currentTime < 1_000 }, stillFor = { 0 }) { presses++; true }
        assertEquals(1, presses)
    }

    @Test fun aSwipeHeldStillRepeatsAfterTheDelay() = runTest {
        var presses = 0
        // Moving for 300 ms, then still: repeats begin 400 ms after it stopped, at 700 ms.
        repeatWhileStill(RemoteKey.RIGHT, held = { currentTime < 1_000 }, stillFor = { (currentTime - 300).coerceAtLeast(0) }) { presses++; true }
        // The first press, then one every 50 ms from 700 to 950.
        assertEquals(1 + 6, presses)
    }

    @Test fun theModeIsKeptForThePhoneAndTheDpadIsTheDefault() {
        val file = File(dir.root, "remote.properties")
        assertFalse(KeyHaptics(file).touchpad)
        KeyHaptics(file).touchpad = true
        assertTrue(KeyHaptics(file).touchpad)
        // The other settings in the same file are left alone.
        assertTrue(KeyHaptics(file).on)
        KeyHaptics(file).touchpad = false
        assertFalse(KeyHaptics(file).touchpad)
    }
}
