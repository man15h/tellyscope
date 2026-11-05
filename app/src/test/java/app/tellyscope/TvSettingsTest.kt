// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.Tweak
import app.tellyscope.data.TvSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TvSettingsTest {
    @Test fun fastLeavesAnimatorAtNormal() {
        assertEquals(
            mapOf("window_animation_scale" to "0.5", "transition_animation_scale" to "0.5", "animator_duration_scale" to "1.0"),
            TvSettings.animations(fast = true),
        )
    }

    @Test fun normalResetsAllThree() {
        assertEquals(Tweak.ANIMATIONS.keys.associateWith { "1.0" }, TvSettings.animations(fast = false))
    }

    @Test fun switchReadsWindowScale() {
        val tweaks = TvSettings.tweaks(emptyMap(), TvSettings.animations(fast = true), sdk = 30)
        assertEquals(TvSettings.FAST, tweaks.animationScale)
    }
}
