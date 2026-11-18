// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.RemapAction
import app.tellyscope.data.RemapBook
import app.tellyscope.data.RemapButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemapBookTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun buttonsAndOnSurviveARestart() {
        val f = tmp.root.resolve("remap.tsv")
        val buttons = listOf(
            RemapButton("BUTTON_4", 0, RemapAction.Open("org.jellyfin.androidtv", "Jellyfin\tTV"), "Netflix"),
            RemapButton("PROG_RED", action = RemapAction.DoNothing),
            RemapButton("BUTTON_9"),
        )
        RemapBook(f).apply {
            set("tv", buttons)
            setOn("tv", true)
        }
        val again = RemapBook(f)
        assertEquals(buttons.map { if (it.key == "BUTTON_4") it.copy(action = RemapAction.Open("org.jellyfin.androidtv", "Jellyfin TV")) else it }, again["tv"])
        assertTrue(again.on("tv"))
    }

    @Test fun keysOffTheListAreNeverKept() {
        val book = RemapBook()
        book.set("tv", listOf(RemapButton("HOME", action = RemapAction.DoNothing), RemapButton("PROG_BLUE")))
        assertEquals(listOf("PROG_BLUE"), book["tv"].map { it.key })
    }

    @Test fun rekeyMovesButtonsToTheTvsRealKey() {
        val book = RemapBook()
        book.set("tv-address", listOf(RemapButton("PROG_RED")))
        book.setOn("tv-address", true)
        book.rekey("tv-address", "serial")
        assertEquals(listOf("PROG_RED"), book["serial"].map { it.key })
        assertTrue(book.on("serial"))
        assertFalse(book.on("tv-address"))
    }
}
