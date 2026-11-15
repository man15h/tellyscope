// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.ui.RowState
import app.tellyscope.ui.ScanScreen
import app.tellyscope.ui.ScanUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Add your TV" moves between its steps only when the user does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideStepsTest {
    @get:Rule val compose = createComposeRule()

    private val tv = ScanHit("192.0.2.7", 37211, ScanKind.Wireless, "chromecast", "Google")
    private val found = ScanUi(scanning = true, hits = listOf(tv), rows = mapOf(tv.ip to RowState.Found))

    private fun show(first: ScanUi): (ScanUi) -> Unit {
        var scan by mutableStateOf(first)
        compose.setContent { ScanScreen(scan, 0L, {}, {}, { _, _ -> }, {}, { null }) }
        return { scan = it }
    }

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    @Test fun aTvFoundMidStepOffersToSkipInsteadOfJumping() {
        val update = show(ScanUi(scanning = true))
        update(found)
        compose.waitForIdle()
        compose.onNodeWithText("Done, next").assertExists()
        compose.onNodeWithText("chromecast").assertDoesNotExist()
        tap("1 TV found, skip ahead")
        compose.onNodeWithText("chromecast").performScrollTo().assertIsDisplayed()
    }

    @Test fun aFinishedStepOpensAgainWithoutLeavingTheLastOne() {
        show(ScanUi(scanning = true))
        tap("Done, next")
        tap("Done, next")
        compose.onNodeWithText("Android TV OS build", substring = true).assertDoesNotExist()
        tap("Unlock developer options")
        compose.onNodeWithText("Android TV OS build", substring = true).assertExists()
        compose.onNodeWithText("Done, next").assertDoesNotExist()
        tap("Unlock developer options")
        compose.onNodeWithText("Android TV OS build", substring = true).assertDoesNotExist()
    }

    @Test fun anotherBrandStartsItsStepsOver() {
        show(found)
        compose.onNodeWithText("chromecast").assertExists()
        tap("Fire TV")
        compose.onNodeWithText("My Fire TV", substring = true).assertExists()
        compose.onNodeWithText("Done, next").assertExists()
        compose.onNodeWithText("1 TV found, skip ahead").assertExists()
    }
}
