// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.RemapKeys
import app.tellyscope.helper.KeyLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutTest {
    private val kl = """
        # Remote
        key 398   PROG_RED
        key 0x18f PROG_GREEN   # hex code
        key 102   HOME
        key usage 0x0c0224 BACK
        key usage 0x0c0077 BUTTON_4 FUNCTION
        axis 0x00 X
        key oops BUTTON_1
    """.trimIndent()

    @Test fun layoutNamesKeysByCodeAndUsage() {
        val l = KeyLayout.parse(kl)
        assertEquals("PROG_RED", l.name(398, -1))
        assertEquals("PROG_GREEN", l.name(0x18f, -1))
        assertEquals("BUTTON_4", l.name(1, 0x0c0077))
        // The usage wins, as in Android's EventHub; an unknown usage falls back to the code.
        assertEquals("BACK", l.name(398, 0x0c0224))
        assertEquals("PROG_RED", l.name(398, 0x0c9999))
        assertNull(l.name(999, -1))
        assertTrue(l.names(setOf("BUTTON_4")))
        assertFalse(l.names(setOf("BUTTON_1")))
    }

    private val dumpsys = """
        Event Hub State:
          BuiltInKeyboardId: -2
          Devices:
            -1: Virtual
              Classes: KEYBOARD | ALPHAKEY | VIRTUAL
              Path: <virtual>
              KeyLayoutFile: /system/usr/keylayout/Generic.kl
            3: Sony TV remote
              Classes: 0x00000021
              Path: /dev/input/event3
              KeyLayoutFile: /vendor/usr/keylayout/Vendor_054c.kl
            5: Air Mouse
              Classes: KEYBOARD | CURSOR | EXTERNAL
              Path: /dev/input/event5
              KeyLayoutFile: /system/usr/keylayout/Generic.kl
            6: Pad
              Classes: KEYBOARD | GAMEPAD | JOYSTICK
              Path: /dev/input/event6
              KeyLayoutFile: /system/usr/keylayout/Generic.kl
            7: Tellyscope keyboard
              Classes: KEYBOARD | EXTERNAL
              Path: /dev/input/event7
              KeyLayoutFile: /system/usr/keylayout/Generic.kl
            8: No layout
              Classes: KEYBOARD
              Path: /dev/input/event8
              KeyLayoutFile:
          Unattached video devices:
        Input Reader State:
    """.trimIndent()

    @Test fun devicesFromDumpsysInEitherClassesForm() {
        val all = KeyLayout.devices(dumpsys)
        assertEquals(listOf("Virtual", "Sony TV remote", "Air Mouse", "Pad", "Tellyscope keyboard", "No layout"), all.map { it.name })
        val remotes = all.filter { it.remote() }
        assertEquals(listOf("Sony TV remote", "Air Mouse"), remotes.map { it.name })
        assertEquals("/dev/input/event3", remotes[0].path)
        assertEquals("/vendor/usr/keylayout/Vendor_054c.kl", remotes[0].layout)
        assertFalse(remotes[0].pointer())
        assertTrue(remotes[1].pointer())
    }

    @Test fun mapKeepsOnlyAllowedNamesAndWellFormedActions() {
        val m = KeyLayout.map(
            "PROG_RED\t-\nHOME\t-\nBUTTON_4\torg.jellyfin.androidtv/.ui.startup.StartupActivity\n" +
                "BUTTON_14\t@com.example.tv\nPROG_BLUE\trm -rf /\nAPP_1\n",
        )
        assertEquals(
            mapOf("PROG_RED" to "-", "BUTTON_4" to "org.jellyfin.androidtv/.ui.startup.StartupActivity", "BUTTON_14" to "@com.example.tv"),
            m,
        )
    }

    @Test fun scriptAndAppAgreeOnTheList() {
        assertEquals(RemapKeys.ALLOWED, KeyLayout.ALLOWED)
    }
}
