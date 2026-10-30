// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tellyscope.ui.Copy
import app.tellyscope.ui.DebloatGate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebloatGateTest {
    @get:Rule val compose = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun gate(launcher: Boolean) = compose.setContent {
        DebloatGate(launcher, onChoose = { taps += "choose" }, onApps = { taps += "apps" }, onNeverAgain = { taps += "never" }, onDismiss = { taps += "dismiss" })
    }

    @Test fun withoutAnAddedLauncherAHomeScreenLeads() {
        gate(launcher = true)
        compose.onNodeWithText(Copy.GATE_LAUNCHER.text()).assertExists()
        compose.onNodeWithText(Copy.CHOOSE_HOME.text()).performClick()
        compose.onNodeWithText(Copy.GO_TO_APPS.text()).performClick()
        compose.onNodeWithText(Copy.DONT_SHOW_AGAIN.text()).performClick()
        assertEquals(listOf("choose", "apps", "never"), taps)
    }

    @Test fun withALauncherSetAppsLeads() {
        gate(launcher = false)
        compose.onNodeWithText(Copy.GATE_APPS.text()).assertExists()
        compose.onNodeWithText(Copy.CHOOSE_HOME.text()).assertDoesNotExist()
        compose.onNodeWithText(Copy.DONT_SHOW_AGAIN.text()).performClick()
        assertEquals(listOf("never"), taps)
    }
}
