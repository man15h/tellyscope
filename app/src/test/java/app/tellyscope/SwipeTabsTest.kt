// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.ui.swipedTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeTabsTest {
    @Test fun aSwipeLeftGoesToTheNextTabAndRightToThePrevious() {
        assertEquals(2, swipedTab(1, 4, dx = -80f, rtl = false, far = 48f))
        assertEquals(0, swipedTab(1, 4, dx = 80f, rtl = false, far = 48f))
    }

    @Test fun rightToLeftLayoutsSwipeTheOtherWay() {
        assertEquals(0, swipedTab(1, 4, dx = -80f, rtl = true, far = 48f))
        assertEquals(2, swipedTab(1, 4, dx = 80f, rtl = true, far = 48f))
    }

    @Test fun aShortSwipeOrOnePastTheEndStays() {
        assertNull(swipedTab(1, 4, dx = -20f, rtl = false, far = 48f))
        assertNull(swipedTab(3, 4, dx = -80f, rtl = false, far = 48f))
        assertNull(swipedTab(0, 4, dx = 80f, rtl = false, far = 48f))
    }
}
