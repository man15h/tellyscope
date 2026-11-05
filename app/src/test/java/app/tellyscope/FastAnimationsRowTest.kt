// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.TvSettings
import app.tellyscope.data.TvTweaks
import app.tellyscope.ui.fastAnimationsShown
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fire OS 8's side menu breaks with animations sped up (#356): the row shows there only to turn it off. */
class FastAnimationsRowTest {
    private fun tweaks(scale: String?, fireOs: Boolean) = TvTweaks(dnsHost = null, animationScale = scale, fireOs = fireOs)

    @Test fun fireTvHidesTheRowWhenOff() {
        assertFalse(fastAnimationsShown(tweaks(null, fireOs = true)))
        assertFalse(fastAnimationsShown(tweaks("1.0", fireOs = true)))
    }

    @Test fun fireTvShowsTheRowWhileLeftOn() {
        assertTrue(fastAnimationsShown(tweaks(TvSettings.FAST, fireOs = true)))
    }

    @Test fun otherTvsAlwaysShowTheRow() {
        assertTrue(fastAnimationsShown(tweaks(null, fireOs = false)))
        assertTrue(fastAnimationsShown(tweaks("1.0", fireOs = false)))
        assertTrue(fastAnimationsShown(tweaks(TvSettings.FAST, fireOs = false)))
    }

    @Test fun fireOsReachesTheTweaks() {
        assertTrue(TvSettings.tweaks(emptyMap(), emptyMap(), sdk = 30, fireOs = true).fireOs)
        assertFalse(TvSettings.tweaks(emptyMap(), emptyMap(), sdk = 30).fireOs)
    }
}
