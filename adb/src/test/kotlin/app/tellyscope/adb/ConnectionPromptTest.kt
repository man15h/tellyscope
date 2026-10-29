// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionPromptTest {
    private val fp = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"

    private fun dump(fingerprint: String = fp, checked: Boolean = false, pkg: String = "com.android.systemui") = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
        <node index="0" text="Allow USB debugging?" resource-id="android:id/alertTitle" package="$pkg" bounds="[200,300][1700,360]" />
        <node index="1" text="The computer's RSA key fingerprint is:&#10;$fingerprint" resource-id="android:id/message" package="$pkg" bounds="[200,380][1700,480]" />
        <node index="2" text="Always allow from this computer" resource-id="android:id/alwaysUse" checkable="true" checked="$checked" package="$pkg" bounds="[200,500][900,560]" />
        <node index="3" text="Cancel" resource-id="android:id/button2" package="$pkg" bounds="[1200,600][1400,680]" />
        <node index="4" text="Allow" resource-id="android:id/button1" package="$pkg" bounds="[1450,600][1650,680]" />
        </hierarchy>
    """.trimIndent()

    private fun tv(xml: String, focus: String = "mCurrentFocus=Window{1 u0 com.android.systemui/com.android.systemui.usb.UsbDebuggingActivity}") =
        FakeShell(mapOf("dumpsys window" to ok(focus), "uiautomator" to ok(xml)))

    @Test fun readsTheFingerprint() = runTest {
        assertEquals(ConnectionRequest(fp), ConnectionPrompt(tv(dump())).pending())
    }

    @Test fun aPromptWithoutTheTickBoxSaysSo() = runTest {
        val noBox = dump().lines().filterNot { "alwaysUse" in it }.joinToString("\n")
        assertEquals(ConnectionRequest(fp, canAlwaysAllow = false), ConnectionPrompt(tv(noBox)).pending())
    }

    @Test fun noPromptNoDump() = runTest {
        val sh = tv(dump(), focus = "mCurrentFocus=Window{1 u0 com.spocky.projengmenu/.ui.MainActivity}")
        assertNull(ConnectionPrompt(sh).pending())
        assertFalse(sh.ran.any { it.startsWith("uiautomator") })
    }

    @Test fun lookAlikeFromAnAppIsIgnored() = runTest {
        assertNull(ConnectionPrompt.parse(dump(pkg = "com.evil.app")))
        val sh = tv(dump(), focus = "mCurrentFocus=Window{1 u0 com.evil.app/com.evil.app.UsbDebuggingActivity}")
        assertNull(ConnectionPrompt(sh).pending())
    }

    @Test fun allowLeavesAlwaysUnticked() = runTest {
        val sh = tv(dump())
        assertTrue(ConnectionPrompt(sh).answer(ConnectionRequest(fp), allow = true, always = false))
        assertEquals(listOf("input tap 1550 640"), sh.ran.filter { it.startsWith("input") })
    }

    @Test fun alwaysIsTickedOnlyWhenAsked() = runTest {
        val sh = tv(dump())
        ConnectionPrompt(sh).answer(ConnectionRequest(fp), allow = true, always = true)
        assertEquals(listOf("input tap 550 530", "input tap 1550 640"), sh.ran.filter { it.startsWith("input") })
    }

    @Test fun denyPressesCancel() = runTest {
        val sh = tv(dump(checked = true))
        ConnectionPrompt(sh).answer(ConnectionRequest(fp), allow = false, always = false)
        assertEquals(listOf("input tap 1300 640"), sh.ran.filter { it.startsWith("input") })
    }

    @Test fun aDifferentPromptIsNotAnswered() = runTest {
        val sh = tv(dump(fingerprint = "11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF"))
        assertFalse(ConnectionPrompt(sh).answer(ConnectionRequest(fp), allow = true, always = false))
        assertFalse(sh.ran.any { it.startsWith("input") })
    }

    // Fire OS 8 on a Fire TV Stick 4K Max: its "Unable to Detect Your Remote" alert, a bare `android` window,
    // holds focus while the debugging prompt waits under it.
    private val alertFocus = "mCurrentFocus=Window{2 u0 android}\n" +
        "  Window #9 Window{1 u0 com.android.systemui/com.android.systemui.usb.UsbDebuggingActivity}:"
    private val alert = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
        <node index="0" text="" resource-id="amazon.fireos:id/auto_pair_troubleshoot_layout" package="android" bounds="[0,0][1920,1080]" />
        <node index="1" text="Unable to Detect Your Remote" resource-id="amazon.fireos:id/troubleshoot_title" package="android" bounds="[600,300][1320,360]" />
        <node index="2" text="OK" resource-id="amazon.fireos:id/ok_button" package="android" focused="true" bounds="[860,800][1060,860]" />
        </hierarchy>
    """.trimIndent()

    @Test fun aPromptUnderAmazonsAlertSaysSoWithoutAFingerprint() = runTest {
        assertEquals(ConnectionRequest("", canAlwaysAllow = false, behindAlert = true), ConnectionPrompt(tv(alert, alertFocus)).pending())
    }

    private val back = "dumpsys window | grep -q 'mCurrentFocus=Window{[^ ]* u[0-9]* android}' && input keyevent 4"

    @Test fun backIsSentOnceAndCheckedInTheSameCall() = runTest {
        val sh = tv(alert, alertFocus)
        assertTrue(ConnectionPrompt(sh).closeAlert())
        assertEquals(listOf(back), sh.ran.filter { "input" in it })
    }

    @Test fun noKeyOnceThePromptHasFocus() = runTest {
        val sh = tv(dump())
        assertFalse(ConnectionPrompt(sh).closeAlert())
        assertFalse(sh.ran.any { "input" in it })
    }

    @Test fun theAlertClosingDuringTheDumpSendsNothing() = runTest {
        // The TV's grep finds no alert by the final check, so `&&` never runs the key.
        val sh = tv(alert, alertFocus).apply { answer = { if (it == back) ShellResult("", 1) else null } }
        assertFalse(ConnectionPrompt(sh).closeAlert())
        assertEquals(listOf(back), sh.ran.filter { "input" in it })
    }

    @Test fun anotherSystemWindowIsNotTheAlert() = runTest {
        val sh = tv(alert.replace("amazon.fireos:id/ok_button", "android:id/button1"), alertFocus)
        assertNull(ConnectionPrompt(sh).pending())
        assertFalse(ConnectionPrompt(sh).closeAlert())
        assertFalse(sh.ran.any { "input" in it })
    }

    @Test fun theAlertAloneIsNoPrompt() = runTest {
        val sh = tv(alert, "mCurrentFocus=Window{2 u0 android}")
        assertNull(ConnectionPrompt(sh).pending())
        assertFalse(sh.ran.any { it.startsWith("uiautomator") })
    }
}
