// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayStoreTest {
    private val pkg = "com.spocky.projengmenu"

    @Test fun opensTheStoreAtTheLaunchersPage() = runTest {
        val sh = FakeShell(mapOf("am start" to ok("Starting: Intent { act=android.intent.action.VIEW }\nStatus: ok\nLaunchState: COLD\n")))
        assertTrue(Actions(sh).openStore(pkg))
        assertEquals("am start -W -a android.intent.action.VIEW -p com.android.vending -d 'market://details?id=$pkg' 2>&1", sh.ran.single())
    }

    @Test fun noStoreIsAnError() = runTest {
        // Fire TV, or a Store that's off: am resolves nothing and says so, still exiting 0 on some builds.
        val sh = FakeShell(mapOf("am start" to ok("Starting: Intent { ... }\nError: Activity not started, unable to resolve Intent { ... }\n")))
        assertFalse(Actions(sh).openStore(pkg))
    }

    @Test fun onlyAPackageNameGoesIntoTheLink() = runTest {
        assertFailsWith<IllegalArgumentException> { Actions(FakeShell()).openStore("x' ; reboot ; '") }
        assertFailsWith<IllegalArgumentException> { Actions(FakeShell()).installed("x; reboot") }
    }

    @Test fun installedIsWhatPmPathNames() = runTest {
        assertTrue(Actions(FakeShell(mapOf("pm path" to ok("package:/data/app/~~x==/$pkg-1/base.apk\n")))).installed(pkg))
        assertFalse(Actions(FakeShell()).installed(pkg))
    }

    @Test fun theStoreOnScreenOnEitherAndroid() = runTest {
        val old = "  mResumedActivity: ActivityRecord{1a2b u0 com.android.vending/com.google.android.finsky.activities.MainActivity t12}\n"
        val new = "    topResumedActivity=ActivityRecord{3c4d u0 com.android.vending/com.google.android.finsky.tvmainactivity.TvMainActivity t40}\n"
        val elsewhere = "  mResumedActivity: ActivityRecord{5e6f u0 com.google.android.tvlauncher/.MainActivity t3}\n"
        assertTrue(Actions(FakeShell(mapOf("dumpsys activity" to ok(old)))).storeOnScreen())
        assertTrue(Actions(FakeShell(mapOf("dumpsys activity" to ok(new)))).storeOnScreen())
        assertFalse(Actions(FakeShell(mapOf("dumpsys activity" to ok(elsewhere)))).storeOnScreen())
    }

    @Test fun aSignInOrConsentScreenIsStillTheStore() = runTest {
        val signIn = "    topResumedActivity=ActivityRecord{7a8b u0 com.google.android.gms/.auth.uiflows.addaccount.AccountIntroActivity t41}\n"
        val oldSignIn = "  mResumedActivity: ActivityRecord{9c0d u0 com.google.android.gsf.login/.LoginActivity t13}\n"
        assertTrue(Actions(FakeShell(mapOf("dumpsys activity" to ok(signIn)))).storeOnScreen())
        assertTrue(Actions(FakeShell(mapOf("dumpsys activity" to ok(oldSignIn)))).storeOnScreen())
    }
}
