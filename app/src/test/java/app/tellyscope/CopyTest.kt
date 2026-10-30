// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.ui.Copy
import org.junit.Assert.assertEquals
import org.junit.Test

class CopyTest {
    @Test fun upForSpellsOutTheLargestUnits() {
        assertEquals("3 days, 4 hours", Copy.upFor(3 * 86_400L + 4 * 3_600 + 59).text())
        assertEquals("1 day", Copy.upFor(86_400L + 120).text())
        assertEquals("1 hour", Copy.upFor(3_600L + 300).text())
        assertEquals("12 minutes", Copy.upFor(12 * 60L).text())
    }

    @Test fun speedUsesTheSameUnitsAsSizes() {
        assertEquals("2.3 MB/s", Copy.speed(2_400_000).text())
        assertEquals("12 KB/s", Copy.speed(12_500).text())
        assertEquals("300 bytes/s", Copy.speed(300).text())
    }
}
