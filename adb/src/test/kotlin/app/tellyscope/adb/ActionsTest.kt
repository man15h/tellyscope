// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActionsTest {
    private val home = "cmd package resolve-activity" to ok("priority=0 preferredOrder=0\ncom.google.android.tvlauncher/.MainActivity\n")

    @Test fun currentHomeIsTheDefaultLauncher() = runTest {
        val sh = FakeShell(mapOf("cmd package resolve-activity" to ok("priority=0\ncom.spocky.projengmenu/.ui.home.MainActivity\n")))
        assertEquals("com.spocky.projengmenu/.ui.home.MainActivity", Actions(sh).currentHome())
    }

    @Test fun currentHomeIsNullWhenAndroidWouldAsk() = runTest {
        val sh = FakeShell(mapOf("cmd package resolve-activity" to ok("priority=0\nandroid/com.android.internal.app.ResolverActivity\n")))
        assertEquals(null, Actions(sh).currentHome())
    }

    @Test fun disableReturnsEnableAsUndo() = runTest {
        val sh = FakeShell(mapOf(home, "pm disable-user" to ok("Package com.foo.ads new state: disabled-user\n")))
        assertEquals(ActionResult.Done(Action.ENABLE), Actions(sh).run(Action.DISABLE, "com.foo.ads"))
        assertTrue(sh.ran.contains("pm disable-user --user 0 com.foo.ads 2>&1"))
    }

    @Test fun stopUndoIsOpenAgain() = runTest {
        val sh = FakeShell(mapOf(home))
        assertEquals(ActionResult.Done(Action.OPEN), Actions(sh).run(Action.STOP, "com.google.android.youtube.tv"))
    }

    /** The list source's protected packages hold for a hand-picked Stop or Disable too, not only Debloat. */
    @Test fun refusesAPackageTheListProtects() = runTest {
        val sh = FakeShell(mapOf(home))
        try {
            Protected.extra = mapOf("org.droidtv.eum" to ProtectedReason.TV_INPUTS_REMOTE)
            assertEquals(ActionResult.Refused(ProtectedReason.TV_INPUTS_REMOTE), Actions(sh).run(Action.STOP, "org.droidtv.eum"))
            assertEquals(ActionResult.Refused(ProtectedReason.TV_INPUTS_REMOTE), Actions(sh).run(Action.DISABLE, "org.droidtv.eum"))
            assertTrue(sh.ran.none { it.startsWith("pm ") || it.startsWith("am ") })
        } finally {
            Protected.extra = emptyMap()
        }
    }

    @Test fun refusesCurrentLauncherWhateverItsName() = runTest {
        val sh = FakeShell(mapOf("cmd package resolve-activity" to ok("com.example.customhome/.Home\n")))
        assertEquals(ActionResult.Refused(ProtectedReason.HOME_SCREEN), Actions(sh).run(Action.DISABLE, "com.example.customhome"))
        assertTrue(sh.ran.none { it.startsWith("pm ") })
    }

    @Test fun noDefaultLauncherProtectsEveryLauncher() = runTest {
        val sh = FakeShell(mapOf(
            "cmd package resolve-activity" to ok("priority=0\nandroid/com.android.internal.app.ResolverActivity\n"),
            "cmd package query-activities" to ok("com.google.android.tvlauncher/.MainActivity\ncom.spocky.projengmenu/.Main\n"),
        ))
        assertEquals(setOf("com.google.android.tvlauncher", "com.spocky.projengmenu"), Actions(sh).homePackages())
        assertEquals(ActionResult.Refused(ProtectedReason.HOME_SCREEN), Actions(sh).run(Action.DISABLE, "com.spocky.projengmenu"))
    }

    @Test fun noDefaultLauncherLeavesTheUsersOwnUnlocked() = runTest {
        // Installing a second launcher clears the default: Android asks, and the TV's own is still there.
        val sh = FakeShell(mapOf(
            "cmd package resolve-activity" to ok("priority=0\nandroid/com.android.internal.app.ResolverActivity\n"),
            "cmd package query-activities" to ok("com.google.android.tvlauncher/.MainActivity\ncom.spocky.projengmenu/.Main\nme.efesser.flauncher/.MainActivity\n"),
            "pm list packages -3" to ok("package:com.spocky.projengmenu\npackage:me.efesser.flauncher\n"),
            "pm disable-user" to ok("Package me.efesser.flauncher new state: disabled-user\n"),
        ))
        assertEquals(setOf("com.google.android.tvlauncher"), Actions(sh).homePackages())
        assertEquals(ActionResult.Refused(ProtectedReason.HOME_SCREEN), Actions(sh).run(Action.DISABLE, "com.google.android.tvlauncher"))
        assertEquals(ActionResult.Done(Action.ENABLE), Actions(sh).run(Action.DISABLE, "me.efesser.flauncher"))
    }

    /** The TV's own launcher was turned off by hand, then a second launcher cleared the default. */
    private fun stockOff(vararg user: String): FakeShell {
        var home = "android/com.android.internal.app.ResolverActivity"
        val activities = mapOf("com.spocky.projengmenu" to "com.spocky.projengmenu/.ui.home.MainActivity", "me.efesser.flauncher" to "me.efesser.flauncher/.MainActivity")
        return FakeShell(mapOf(
            "cmd package query-activities" to ok(user.joinToString("") { activities.getValue(it) + "\n" }),
            "pm list packages -3" to ok(user.joinToString("") { "package:$it\n" }),
            "pm disable-user" to ok("Package me.efesser.flauncher new state: disabled-user\n"),
        )).apply {
            answer = { cmd ->
                when {
                    cmd.startsWith("cmd package resolve-activity") -> ok("priority=0\n$home\n")
                    cmd.startsWith("cmd package set-home-activity") -> {
                        home = cmd.substringAfter("'").substringBefore("'")
                        ok("Success\n")
                    }
                    else -> null
                }
            }
        }
    }

    @Test fun stockOffAndTwoOfTheUsersLaunchersLocksNeither() = runTest {
        val sh = stockOff("com.spocky.projengmenu", "me.efesser.flauncher")
        assertEquals(emptySet(), Actions(sh).homePackages())
        assertEquals(ActionResult.Done(Action.ENABLE), Actions(sh).run(Action.DISABLE, "me.efesser.flauncher"))
        assertEquals(HomeOptions(null, setOf("com.spocky.projengmenu", "me.efesser.flauncher"), default = false), Actions(sh).homeOptions())
    }

    @Test fun stockOffSetAsDefaultSetsHomeAndTurnsNothingOff() = runTest {
        val sh = stockOff("com.spocky.projengmenu", "me.efesser.flauncher")
        assertEquals(HomeResult.Switched(null, "com.spocky.projengmenu/.ui.home.MainActivity"), Actions(sh).makeHome("com.spocky.projengmenu"))
        assertTrue(sh.ran.none { it.startsWith("pm disable") || it.startsWith("pm enable") })
        assertEquals("com.spocky.projengmenu/.ui.home.MainActivity", Actions(sh).currentHome())
    }

    @Test fun aSwitchBetweenTheUsersLaunchersTurnsNeitherOff() = runTest {
        val sh = stockOff("com.spocky.projengmenu", "me.efesser.flauncher")
        Actions(sh).makeHome("com.spocky.projengmenu")
        assertEquals(
            HomeResult.Switched("com.spocky.projengmenu/.ui.home.MainActivity", "me.efesser.flauncher/.MainActivity", kept = true),
            Actions(sh).makeHome("me.efesser.flauncher"),
        )
        assertTrue(sh.ran.none { it.startsWith("pm disable") || it.startsWith("pm enable") })
        assertEquals("me.efesser.flauncher/.MainActivity", Actions(sh).currentHome())
    }

    @Test fun aSwitchBetweenTheUsersLaunchersThatDoesNotTakePutsHomeBack() = runTest {
        val sh = stockOff("com.spocky.projengmenu", "me.efesser.flauncher")
        Actions(sh).makeHome("com.spocky.projengmenu")
        sh.answer.let { prev -> sh.answer = { if (it.startsWith("cmd package set-home-activity --user 0 'me.")) ok("Success\n") else prev(it) } }
        assertEquals("the TV kept the home screen it has", (Actions(sh).makeHome("me.efesser.flauncher") as HomeResult.Failed).output)
        assertEquals("com.spocky.projengmenu/.ui.home.MainActivity", Actions(sh).currentHome())
        assertTrue(sh.ran.none { it.startsWith("pm ") && !it.startsWith("pm list") })
    }

    @Test fun stockOffAndOneOfTheUsersLaunchersKeepsItLocked() = runTest {
        // The only launcher left: without it the TV would have no home screen.
        val sh = stockOff("com.spocky.projengmenu")
        assertEquals(setOf("com.spocky.projengmenu"), Actions(sh).homePackages())
        assertEquals(ActionResult.Refused(ProtectedReason.HOME_SCREEN), Actions(sh).run(Action.DISABLE, "com.spocky.projengmenu"))
        assertEquals(ActionResult.Refused(ProtectedReason.HOME_SCREEN), Actions(sh).run(Action.UNINSTALL, "com.spocky.projengmenu"))
    }

    @Test fun unknownHomeRefusesStopAndDisable() = runTest {
        // Android 6 and older have no `cmd`: both launcher lookups come back empty.
        val sh = FakeShell(mapOf("cmd package" to ShellResult("/system/bin/sh: cmd: not found\n", 127)))
        val a = Actions(sh)
        assertEquals(ActionResult.Refused(ProtectedReason.UNKNOWN_HOME), a.run(Action.DISABLE, "com.example.tvlauncher"))
        assertEquals(ActionResult.Refused(ProtectedReason.UNKNOWN_HOME), a.run(Action.STOP, "com.foo.ads"))
        assertTrue(sh.ran.none { it.startsWith("pm ") || it.startsWith("am ") })
    }

    @Test fun launcherNamesAreProtectedEvenWhenNotDefault() = runTest {
        assertEquals(ActionResult.Refused(ProtectedReason.HOME_SCREEN), Actions(FakeShell(mapOf(home))).run(Action.DISABLE, "com.example.tvlauncher"))
    }

    @Test fun refusesProtectedPackages() = runTest {
        val a = Actions(FakeShell(mapOf(home)))
        assertEquals(ActionResult.Refused(ProtectedReason.PLAY_SERVICES), a.run(Action.DISABLE, "com.google.android.gms"))
        assertEquals(ActionResult.Refused(ProtectedReason.KEYBOARD), a.run(Action.STOP, "com.google.android.leanback.ime"))
        assertEquals(ActionResult.Refused(ProtectedReason.TV_INPUTS_REMOTE), a.run(Action.DISABLE, "com.xgimi.hdmiinput"))
    }

    @Test fun enablingProtectedIsAllowed() = runTest {
        val sh = FakeShell(mapOf(home, "pm enable" to ok("Package com.android.vending new state: enabled\n")))
        assertEquals(ActionResult.Done(Action.DISABLE), Actions(sh).run(Action.ENABLE, "com.android.vending"))
    }

    @Test fun pmErrorIsFailure() = runTest {
        val sh = FakeShell(mapOf(home, "pm disable-user" to ok("Error: java.lang.SecurityException: Shell cannot change component state\n")))
        assertTrue(Actions(sh).run(Action.DISABLE, "com.foo") is ActionResult.Failed)
    }

    @Test fun uninstallsOnlyWhatTheUserInstalled() = runTest {
        val sh = FakeShell(mapOf(
            home,
            "pm list packages -3" to ok("package:com.netflix.ninja\npackage:com.amazon.amazonvideo.livingroom\n"),
            "pm uninstall" to ok("Success\n"),
        ))
        assertEquals(ActionResult.Done(null), Actions(sh).run(Action.UNINSTALL, "com.netflix.ninja"))
        assertTrue(sh.ran.contains("pm uninstall com.netflix.ninja 2>&1"))
    }

    @Test fun uninstallsAnAddedLauncherThatIsNotHome() = runTest {
        val projectivy = "cmd package resolve-activity" to ok("priority=0\ncom.spocky.projengmenu/.ui.home.MainActivity\n")
        val sh = FakeShell(mapOf(projectivy, "pm list packages -3" to ok("package:com.spocky.projengmenu\npackage:me.efesser.flauncher\n"), "pm uninstall" to ok("Success\n")))
        assertEquals(ActionResult.Done(null), Actions(sh).run(Action.UNINSTALL, "me.efesser.flauncher"))
        assertEquals(ActionResult.Refused(ProtectedReason.HOME_SCREEN), Actions(sh).run(Action.UNINSTALL, "com.spocky.projengmenu"))
    }

    @Test fun refusesToUninstallWhatCameWithTheTv() = runTest {
        val sh = FakeShell(mapOf(home, "pm list packages -3" to ok("package:com.netflix.ninja\n")))
        assertEquals(ActionResult.Refused(ProtectedReason.CAME_WITH_TV), Actions(sh).run(Action.UNINSTALL, "com.google.android.youtube.tv"))
        assertTrue(sh.ran.none { it.startsWith("pm uninstall") })
    }

    @Test fun neverUninstallsForOneUser() = runTest {
        val sh = FakeShell(mapOf(home, "pm list packages -3" to ok("package:com.netflix.ninja\n"), "pm uninstall" to ok("Success\n")))
        Actions(sh).run(Action.UNINSTALL, "com.netflix.ninja")
        assertTrue(sh.ran.none { "--user" in it && "uninstall" in it })
    }

    @Test fun uninstallRefusesProtectedAndUnknownHome() = runTest {
        val user = "pm list packages -3" to ok("package:com.google.android.gms\npackage:com.foo.ads\n")
        assertEquals(ActionResult.Refused(ProtectedReason.PLAY_SERVICES), Actions(FakeShell(mapOf(home, user))).run(Action.UNINSTALL, "com.google.android.gms"))
        val noHome = FakeShell(mapOf(user, "cmd package" to ShellResult("/system/bin/sh: cmd: not found\n", 127)))
        assertEquals(ActionResult.Refused(ProtectedReason.UNKNOWN_HOME), Actions(noHome).run(Action.UNINSTALL, "com.foo.ads"))
        assertTrue(noHome.ran.none { it.startsWith("pm uninstall") })
    }

    @Test fun uninstallFailureIsReported() = runTest {
        val sh = FakeShell(mapOf(home, "pm list packages -3" to ok("package:com.foo\n"), "pm uninstall" to ok("Failure [DELETE_FAILED_INTERNAL_ERROR]\n")))
        assertTrue(Actions(sh).run(Action.UNINSTALL, "com.foo") is ActionResult.Failed)
    }

    @Test fun openFallsBackToPhoneLauncher() = runTest {
        val sh = FakeShell(mapOf(
            "monkey -p com.foo -c android.intent.category.LEANBACK_LAUNCHER" to ok("** No activities found to run, monkey aborted.\n"),
            "monkey -p com.foo -c android.intent.category.LAUNCHER" to ok("Events injected: 1\n"),
        ))
        assertEquals(ActionResult.Done(null), Actions(sh).run(Action.OPEN, "com.foo"))
        assertEquals(2, sh.ran.count { it.startsWith("monkey") })
    }

    @Test fun openSkipsSystemKeysForAFireTvRemote() = runTest {
        val sh = FakeShell()
        Actions(sh).run(Action.OPEN, "com.foo")
        assertTrue(sh.ran.single().contains("--pct-syskeys 0"))
    }

    @Test fun openFallsBackToHomeForALauncherWithNoIcon() = runTest {
        val none = ok("** No activities found to run, monkey aborted.\n")
        val sh = FakeShell(mapOf(
            "monkey -p com.foo -c android.intent.category.LEANBACK_LAUNCHER" to none,
            "monkey -p com.foo -c android.intent.category.LAUNCHER" to none,
            "monkey -p com.foo -c android.intent.category.HOME" to ok("Events injected: 1\n"),
        ))
        assertEquals(ActionResult.Done(null), Actions(sh).run(Action.OPEN, "com.foo"))
        assertEquals(3, sh.ran.count { it.startsWith("monkey") })
    }

    @Test fun rejectsShellInjection() = runTest {
        val sh = FakeShell()
        assertFailsWith<IllegalArgumentException> { Actions(sh).run(Action.STOP, "com.foo; reboot") }
        assertFailsWith<IllegalArgumentException> { Actions(sh).run(Action.STOP, "\$(reboot)") }
        assertTrue(sh.ran.isEmpty())
    }

    /**
     * A TV with the stock launcher and Projectivy; set-home-activity moves Home only if [accepts].
     * [outranks]: the old launcher has the higher Home priority, so Home opens it whatever the
     * default is until it's off, like Android TV Home.
     */
    private fun twoLaunchers(accepts: Boolean = true, outranks: Boolean = false): FakeShell {
        var home = "com.google.android.tvlauncher/.MainActivity"
        var oldOn = true
        return FakeShell(mapOf(
            "cmd package query-activities" to ok(
                if (outranks) "3 activities found:\n  Activity #0:\n    priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n    com.google.android.tvlauncher/.MainActivity\n" +
                    "  Activity #1:\n    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n    com.spocky.projengmenu/.ui.home.MainActivity\n"
                else "com.google.android.tvlauncher/.MainActivity\ncom.spocky.projengmenu/.ui.home.MainActivity\n",
            ),
            "pm disable-user" to ok("Package com.google.android.tvlauncher new state: disabled-user\n"),
            "pm enable" to ok("Package com.google.android.tvlauncher new state: enabled\n"),
        )).apply {
            answer = { cmd ->
                when {
                    cmd.startsWith("pm disable-user --user 0 com.google.android.tvlauncher") -> {
                        oldOn = false
                        ok("Package com.google.android.tvlauncher new state: disabled-user\n")
                    }
                    cmd.startsWith("pm enable --user 0 com.google.android.tvlauncher") -> {
                        oldOn = true
                        ok("Package com.google.android.tvlauncher new state: enabled\n")
                    }
                    cmd.startsWith("cmd package resolve-activity") -> {
                        val opens = if (outranks && oldOn) "com.google.android.tvlauncher/.MainActivity" else home
                        ok("priority=0 preferredOrder=0\n$opens\n")
                    }
                    cmd.startsWith("cmd package set-home-activity") -> {
                        if (accepts) home = cmd.substringAfter("'").substringBefore("'")
                        ok("Success\n")
                    }
                    else -> null
                }
            }
        }
    }

    /**
     * Google TV: its launcher outranks, and with it off Home falls to the setup app, whatever was
     * set, until the setup app is off too. [accepts]: Home then opens the launcher set.
     */
    private fun googleTv(accepts: Boolean = true): FakeShell {
        var home = "com.google.android.tvlauncher/.MainActivity"
        var oldOn = true
        var setupOn = true
        return FakeShell(mapOf(
            "cmd package query-activities" to ok(
                "2 activities found:\n  Activity #0:\n    priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n    com.google.android.tvlauncher/.MainActivity\n" +
                    "  Activity #1:\n    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n    com.spocky.projengmenu/.ui.home.MainActivity\n" +
                    "  Activity #2:\n    priority=1 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n    $SETUP/.MainActivity\n",
            ),
        )).apply {
            answer = { cmd ->
                when {
                    cmd.startsWith("pm disable-user --user 0 com.google.android.tvlauncher") -> ok("Package x new state: disabled-user\n").also { oldOn = false }
                    cmd.startsWith("pm enable --user 0 com.google.android.tvlauncher") -> ok("Package x new state: enabled\n").also { oldOn = true }
                    cmd.startsWith("pm disable-user --user 0 $SETUP") -> ok("Package x new state: disabled-user\n").also { setupOn = false }
                    cmd.startsWith("pm enable --user 0 $SETUP") -> ok("Package x new state: enabled\n").also { setupOn = true }
                    cmd.startsWith("cmd package resolve-activity") -> ok("priority=0 preferredOrder=0\n${when {
                        oldOn -> "com.google.android.tvlauncher/.MainActivity"
                        setupOn -> "$SETUP/.MainActivity"
                        else -> home
                    }}\n")
                    cmd.startsWith("cmd package set-home-activity") -> ok("Success\n").also { if (accepts) home = cmd.substringAfter("'").substringBefore("'") }
                    else -> null
                }
            }
        }
    }

    @Test fun offersOtherLaunchersOnly() = runTest {
        assertEquals(HomeOptions("com.google.android.tvlauncher", setOf("com.spocky.projengmenu")), Actions(twoLaunchers()).homeOptions())
    }

    @Test fun noSecondLauncherNothingOffered() = runTest {
        val sh = FakeShell(mapOf(home, "cmd package query-activities" to ok("com.google.android.tvlauncher/.MainActivity\n")))
        assertEquals(emptySet(), Actions(sh).homeOptions()?.others)
    }

    @Test fun noDefaultHomeOffersTheUsersLauncherInPlaceOfTheTvsOwn() = runTest {
        val sh = FakeShell(mapOf(
            "cmd package resolve-activity" to ok("priority=0\nandroid/com.android.internal.app.ResolverActivity\n"),
            "cmd package query-activities" to ok("com.google.android.tvlauncher/.MainActivity\ncom.spocky.projengmenu/.Main\n"),
            "pm list packages -3" to ok("package:com.spocky.projengmenu\n"),
        ))
        assertEquals(HomeOptions("com.google.android.tvlauncher", setOf("com.spocky.projengmenu"), default = false), Actions(sh).homeOptions())
    }

    @Test fun noDefaultHomeSwitchesFromTheTvsOwn() = runTest {
        var home = "android/com.android.internal.app.ResolverActivity"
        val sh = FakeShell(mapOf(
            "cmd package query-activities" to ok("com.google.android.tvlauncher/.MainActivity\ncom.spocky.projengmenu/.ui.home.MainActivity\n"),
            "pm list packages -3" to ok("package:com.spocky.projengmenu\n"),
            "pm disable-user" to ok("Package com.google.android.tvlauncher new state: disabled-user\n"),
        )).apply {
            answer = { cmd ->
                when {
                    cmd.startsWith("cmd package resolve-activity") -> ok("priority=0\n$home\n")
                    cmd.startsWith("cmd package set-home-activity") -> {
                        home = cmd.substringAfter("'").substringBefore("'")
                        ok("Success\n")
                    }
                    else -> null
                }
            }
        }
        assertEquals(
            HomeResult.Switched("com.google.android.tvlauncher/.MainActivity", "com.spocky.projengmenu/.ui.home.MainActivity"),
            Actions(sh).makeHome("com.spocky.projengmenu"),
        )
        assertTrue("pm disable-user --user 0 com.google.android.tvlauncher 2>&1" in sh.ran)
    }

    @Test fun noDefaultHomeMakesTheTvsOwnTheDefault() = runTest {
        var home = "android/com.android.internal.app.ResolverActivity"
        val sh = FakeShell(mapOf(
            "cmd package query-activities" to ok("com.google.android.tvlauncher/.MainActivity\ncom.spocky.projengmenu/.ui.home.MainActivity\n"),
            "pm list packages -3" to ok("package:com.spocky.projengmenu\n"),
        )).apply {
            answer = { cmd ->
                when {
                    cmd.startsWith("cmd package resolve-activity") -> ok("priority=0\n$home\n")
                    cmd.startsWith("cmd package set-home-activity") -> {
                        home = cmd.substringAfter("'").substringBefore("'")
                        ok("Success\n")
                    }
                    else -> null
                }
            }
        }
        assertEquals(HomeResult.Switched(null, "com.google.android.tvlauncher/.MainActivity"), Actions(sh).makeHome("com.google.android.tvlauncher"))
        assertTrue(sh.ran.none { it.startsWith("pm disable") || it.startsWith("pm enable") })
        // Once it's the default, it's Home: asking again changes nothing.
        assertTrue(Actions(sh).makeHome("com.google.android.tvlauncher") is HomeResult.Failed)
    }

    @Test fun makeHomeSwitchesChecksThenDisables() = runTest {
        val sh = twoLaunchers()
        assertEquals(
            HomeResult.Switched("com.google.android.tvlauncher/.MainActivity", "com.spocky.projengmenu/.ui.home.MainActivity"),
            Actions(sh).makeHome("com.spocky.projengmenu"),
        )
        val set = sh.ran.indexOf("cmd package set-home-activity --user 0 'com.spocky.projengmenu/.ui.home.MainActivity' 2>&1")
        val off = sh.ran.indexOf("pm disable-user --user 0 com.google.android.tvlauncher 2>&1")
        assertTrue(set >= 0 && off > set)
    }

    @Test fun outrankingLauncherIsTurnedOffThenHomeChecked() = runTest {
        val sh = twoLaunchers(outranks = true)
        assertEquals(
            HomeResult.Switched("com.google.android.tvlauncher/.MainActivity", "com.spocky.projengmenu/.ui.home.MainActivity"),
            Actions(sh).makeHome("com.spocky.projengmenu"),
        )
        assertEquals("com.spocky.projengmenu", Actions(sh).homeOptions()?.current)
        assertTrue(sh.ran.none { it.startsWith("pm enable") })
    }

    @Test fun homeCheckFailsNothingDisabled() = runTest {
        val sh = twoLaunchers(accepts = false)
        assertEquals("the TV kept its own home screen, so nothing was turned off", (Actions(sh).makeHome("com.spocky.projengmenu") as HomeResult.Failed).output)
        // Asking which launchers the user added is a read; nothing is turned off or on.
        assertTrue(sh.ran.none { it.startsWith("pm disable") || it.startsWith("pm enable") })
        // The old home is set back, in case the TV took the new one half-way.
        assertEquals("cmd package set-home-activity --user 0 'com.google.android.tvlauncher/.MainActivity' 2>&1", sh.ran.last { "set-home" in it })
    }

    @Test fun outrankingHomeCheckFailsOldLauncherBackOnAndHome() = runTest {
        val sh = twoLaunchers(accepts = false, outranks = true)
        val r = Actions(sh).makeHome("com.spocky.projengmenu") as HomeResult.Failed
        assertEquals("Home still didn't open it, so the old home screen was turned back on", r.output)
        assertEquals(null, r.setup)
        val off = sh.ran.indexOf("pm disable-user --user 0 com.google.android.tvlauncher 2>&1")
        val on = sh.ran.indexOf("pm enable --user 0 com.google.android.tvlauncher 2>&1")
        assertTrue(off >= 0 && on > off)
        // The old home is set back, in case the TV took the new one half-way.
        assertEquals("cmd package set-home-activity --user 0 'com.google.android.tvlauncher/.MainActivity' 2>&1", sh.ran.last { "set-home" in it })
        assertEquals("com.google.android.tvlauncher", Actions(sh).homeOptions()?.current)
    }

    /** Google TV: with its launcher off, Home falls to the setup app and stays there once the launcher is back on. */
    @Test fun rollbackThatHomeDoesNotTakeSaysSo() = runTest {
        val sh = twoLaunchers(accepts = false, outranks = true)
        var fell = false
        sh.answer.let { prev ->
            sh.answer = {
                if (it.startsWith("pm disable-user")) fell = true
                if (fell && it.startsWith("cmd package resolve-activity")) ok("priority=0\ncom.google.android.tungsten.setupwraith/.MainActivity\n") else prev(it)
            }
        }
        val r = Actions(sh).makeHome("com.spocky.projengmenu") as HomeResult.Failed
        assertEquals(Actions.HOME_NOT_BACK, r.output)
        assertTrue("Home opens com.google.android.tungsten.setupwraith/.MainActivity, not com.google.android.tvlauncher/.MainActivity" in r.details, r.details)
        // It still tried: the old launcher is on again and was set as Home.
        assertTrue(sh.ran.contains("pm enable --user 0 com.google.android.tvlauncher 2>&1"))
        assertEquals("cmd package set-home-activity --user 0 'com.google.android.tvlauncher/.MainActivity' 2>&1", sh.ran.last { "set-home" in it })
    }

    @Test fun homeCheckFailsAndHomeDoesNotGoBackSaysSo() = runTest {
        val sh = twoLaunchers(accepts = false)
        sh.answer.let { prev ->
            sh.answer = {
                if (it.startsWith("cmd package resolve-activity") && sh.ran.any { c -> "set-home" in c }) ok("priority=0\nandroid/com.android.internal.app.ResolverActivity\n") else prev(it)
            }
        }
        val r = Actions(sh).makeHome("com.spocky.projengmenu") as HomeResult.Failed
        assertEquals(Actions.HOME_NOT_BACK, r.output)
        assertTrue(r.details.endsWith("Home opens the chooser, not com.google.android.tvlauncher/.MainActivity"), r.details)
        assertTrue(sh.ran.none { it.startsWith("pm disable") })
    }

    @Test fun failedTurnBackOnSaysWhereToDoIt() = runTest {
        val sh = twoLaunchers(accepts = false, outranks = true)
        sh.answer.let { prev -> sh.answer = { if (it.startsWith("pm enable")) ok("Error: refused\n") else prev(it) } }
        val r = Actions(sh).makeHome("com.spocky.projengmenu") as HomeResult.Failed
        assertTrue(r.output.endsWith("Turn it back on from Apps → Built-in"), r.output)
        assertTrue(r.details.endsWith("\nError: refused"), r.details)
    }

    @Test fun failedDisablePutsHomeBack() = runTest {
        val sh = twoLaunchers()
        sh.answer.let { prev -> sh.answer = { if (it.startsWith("pm disable-user")) ok("Error: refused\n") else prev(it) } }
        assertTrue(Actions(sh).makeHome("com.spocky.projengmenu") is HomeResult.Failed)
        assertEquals("com.google.android.tvlauncher", Actions(sh).homeOptions()?.current)
    }

    @Test fun newHomeIsProtectedAfterTheSwitch() = runTest {
        val sh = twoLaunchers()
        Actions(sh).makeHome("com.spocky.projengmenu")
        assertEquals(ActionResult.Refused(ProtectedReason.HOME_SCREEN), Actions(sh).run(Action.DISABLE, "com.spocky.projengmenu"))
    }

    @Test fun makeHomeOnlyForInstalledLaunchers() = runTest {
        val sh = twoLaunchers()
        assertTrue(Actions(sh).makeHome("com.netflix.ninja") is HomeResult.Failed)
        assertTrue(Actions(sh).makeHome("com.google.android.tvlauncher") is HomeResult.Failed)
        assertTrue(sh.ran.none { it.startsWith("pm ") || "set-home" in it })
    }

    @Test fun settingsIsNotALauncher() = runTest {
        // TV Settings answers Home with its fallback screen; it's no launcher to offer.
        val sh = FakeShell(mapOf(
            home,
            "cmd package query-activities" to ok("com.google.android.tvlauncher/.MainActivity\ncom.android.tv.settings/.FallbackHome\n"),
        ))
        assertEquals(emptySet(), Actions(sh).homeOptions()?.others)
        assertTrue(Actions(sh).makeHome("com.android.tv.settings") is HomeResult.Failed)
        assertTrue(sh.ran.none { it.startsWith("pm ") || "set-home" in it })
    }

    @Test fun fireHomeStarterIsNotALauncher() = runTest {
        // Fire OS answers Home with its launcher, a backup starter and Settings' fallback; only the first is one.
        val sh = FakeShell(mapOf(
            "cmd package resolve-activity" to ok("priority=950 preferredOrder=0\ncom.amazon.tv.launcher/.ui.HomeActivity_vNext\n"),
            "cmd package query-activities" to ok(
                "priority=950 preferredOrder=0\ncom.amazon.tv.launcher/.ui.HomeActivity_vNext\n" +
                    "priority=1 preferredOrder=0\ncom.amazon.firehomestarter/.HomeStarterActivity\n" +
                    "priority=-1000 preferredOrder=0\ncom.amazon.tv.settings.v2/.system.FallbackHome\n",
            ),
        ))
        assertEquals(HomeOptions("com.amazon.tv.launcher", emptySet()), Actions(sh).homeOptions())
    }

    /** Android 10+ can apply Home after set-home-activity returns: a few reads in, it opens the new launcher. */
    @Test fun homeThatTakesLateStillSwitches() = runTest {
        val sh = twoLaunchers()
        var reads = 0
        sh.answer.let { prev ->
            sh.answer = {
                val r = prev(it)
                if (it.startsWith("cmd package resolve-activity") && sh.ran.any { c -> "set-home" in c } && ++reads <= 3) ok("priority=0\ncom.google.android.tvlauncher/.MainActivity\n") else r
            }
        }
        assertEquals(
            HomeResult.Switched("com.google.android.tvlauncher/.MainActivity", "com.spocky.projengmenu/.ui.home.MainActivity"),
            Actions(sh).makeHome("com.spocky.projengmenu"),
        )
        assertTrue(sh.ran.contains("pm disable-user --user 0 com.google.android.tvlauncher 2>&1"))
    }

    @Test fun anEmptyReadIsNotAMiss() = runTest {
        val sh = twoLaunchers()
        var empty = true
        sh.answer.let { prev ->
            sh.answer = {
                if (it.startsWith("cmd package resolve-activity") && sh.ran.any { c -> "set-home" in c } && empty) ok("").also { empty = false } else prev(it)
            }
        }
        assertTrue(Actions(sh).makeHome("com.spocky.projengmenu") is HomeResult.Switched)
    }

    /** An error from set-home-activity isn't waited out, and it goes into the details for a problem report. */
    @Test fun whatSetHomeSaidIsInTheDetails() = runTest {
        val sh = twoLaunchers(accepts = false)
        sh.answer.let { prev -> sh.answer = { if (it.startsWith("cmd package set-home-activity --user 0 'com.spocky")) ok("Error: role holder refused\n") else prev(it) } }
        val r = Actions(sh).makeHome("com.spocky.projengmenu") as HomeResult.Failed
        assertEquals("set-home-activity said: Error: role holder refused", r.details)
        val set = sh.ran.indexOfFirst { "set-home" in it }
        assertEquals(1, sh.ran.drop(set + 1).takeWhile { "set-home" !in it }.count { it.startsWith("cmd package resolve-activity") })
    }

    /** Google TV: Home falls to the setup app once its launcher is off, so the failure names that app. */
    @Test fun setupAppThatTakesHomeIsNamed() = runTest {
        val sh = googleTv()
        val r = Actions(sh).makeHome("com.spocky.projengmenu") as HomeResult.Failed
        assertEquals("Home still didn't open it, so the old home screen was turned back on", r.output)
        assertEquals(SETUP, r.setup)
        assertTrue(sh.ran.none { it.startsWith("pm disable-user --user 0 $SETUP") })
        assertEquals("com.google.android.tvlauncher", Actions(sh).homeOptions()?.current)
    }

    @Test fun setupAppOffForTheSwitchLetsItTake() = runTest {
        val sh = googleTv()
        assertEquals(
            HomeResult.Switched("com.google.android.tvlauncher/.MainActivity", "com.spocky.projengmenu/.ui.home.MainActivity", setup = SETUP),
            Actions(sh).makeHome("com.spocky.projengmenu", setupOff = SETUP),
        )
        val old = sh.ran.indexOf("pm disable-user --user 0 com.google.android.tvlauncher 2>&1")
        val setup = sh.ran.indexOf("pm disable-user --user 0 $SETUP 2>&1")
        assertTrue(old >= 0 && setup > old)
        assertEquals("com.spocky.projengmenu", Actions(sh).homeOptions()?.current)
    }

    @Test fun setupAppOffThatStillFailsTurnsBothBackOn() = runTest {
        val sh = googleTv(accepts = false)
        val r = Actions(sh).makeHome("com.spocky.projengmenu", setupOff = SETUP) as HomeResult.Failed
        assertEquals("Home still didn't open it, so the setup app and the old home screen were turned back on", r.output)
        assertTrue(sh.ran.contains("pm enable --user 0 $SETUP 2>&1"))
        assertTrue(sh.ran.contains("pm enable --user 0 com.google.android.tvlauncher 2>&1"))
        assertEquals("com.google.android.tvlauncher", Actions(sh).homeOptions()?.current)
    }

    @Test fun setupAppThatWontTurnOffChangesNothing() = runTest {
        val sh = googleTv()
        sh.answer.let { prev -> sh.answer = { if (it.startsWith("pm disable-user --user 0 $SETUP")) ok("Error: refused\n") else prev(it) } }
        val r = Actions(sh).makeHome("com.spocky.projengmenu", setupOff = SETUP) as HomeResult.Failed
        assertEquals("the setup app didn't turn off, so nothing changed", r.output)
        assertEquals("com.google.android.tvlauncher", Actions(sh).homeOptions()?.current)
    }

    @Test fun setupAppThatWontTurnBackOnSaysWhereToDoIt() = runTest {
        val sh = googleTv(accepts = false)
        sh.answer.let { prev -> sh.answer = { if (it.startsWith("pm enable --user 0 $SETUP")) ok("Error: refused\n") else prev(it) } }
        val r = Actions(sh).makeHome("com.spocky.projengmenu", setupOff = SETUP) as HomeResult.Failed
        assertEquals("Home didn't open it, and the setup app didn't turn back on. Turn it back on from Apps → Built-in", r.output)
        assertTrue(r.details.endsWith("\nError: refused"), r.details)
        // The old launcher still comes back, so Home has somewhere to go
        assertTrue(sh.ran.contains("pm enable --user 0 com.google.android.tvlauncher 2>&1"))
        assertEquals("com.google.android.tvlauncher", Actions(sh).homeOptions()?.current)
    }

    /** Neither app back on leaves Home nowhere to go, so the result names both, with both outputs. */
    @Test fun setupAppAndOldLauncherBothStayingOffSaysBoth() = runTest {
        val sh = googleTv(accepts = false)
        sh.answer.let { prev ->
            sh.answer = {
                when {
                    it.startsWith("pm enable --user 0 $SETUP") -> ok("Error: setup refused\n")
                    it.startsWith("pm enable --user 0 com.google.android.tvlauncher") -> ok("Error: launcher refused\n")
                    else -> prev(it)
                }
            }
        }
        val r = Actions(sh).makeHome("com.spocky.projengmenu", setupOff = SETUP) as HomeResult.Failed
        assertEquals("Home didn't open it, and neither the setup app nor the old home screen turned back on. Turn both back on from Apps → Built-in", r.output)
        assertTrue(r.details.endsWith("\nError: setup refused\nError: launcher refused"), r.details)
    }

    /** The old launcher back on but Home not back on it is the stuck case [Actions.HOME_NOT_BACK] covers. */
    @Test fun setupAppOffAndHomeNotBackSaysHomeNotBack() = runTest {
        val sh = googleTv(accepts = false)
        sh.answer.let { prev ->
            sh.answer = {
                when {
                    it.startsWith("pm enable --user 0 $SETUP") -> ok("Error: setup refused\n")
                    it.startsWith("cmd package set-home-activity --user 0 'com.google") -> ok("Error: refused\n")
                    it.startsWith("cmd package resolve-activity") && sh.ran.count { c -> c.startsWith("pm enable --user 0 com.google.android.tvlauncher") } > 0 -> ok("priority=0\ncom.spocky.projengmenu/.ui.home.MainActivity\n")
                    else -> prev(it)
                }
            }
        }
        val r = Actions(sh).makeHome("com.spocky.projengmenu", setupOff = SETUP) as HomeResult.Failed
        assertEquals(Actions.HOME_NOT_BACK, r.output)
        assertTrue("Error: setup refused" in r.details, r.details)
    }

    /** Only a setup app can be turned off for a switch: anything else stays protected. */
    @Test fun setupOffIsOnlyForASetupApp() = runTest {
        val sh = googleTv()
        assertFailsWith<IllegalArgumentException> { Actions(sh).makeHome("com.spocky.projengmenu", setupOff = "com.google.android.youtube.tv") }
        assertFailsWith<IllegalArgumentException> { Actions(sh).homeBack("com.google.android.tvlauncher", setup = "$SETUP;reboot") }
        assertTrue(sh.ran.none { it.startsWith("pm ") || "set-home" in it })
    }

    @Test fun homeBackTurnsTheSetupAppOnFirst() = runTest {
        val sh = googleTv()
        Actions(sh).makeHome("com.spocky.projengmenu", setupOff = SETUP)
        assertTrue(Actions(sh).homeBack("com.google.android.tvlauncher", "com.google.android.tvlauncher/.MainActivity", setup = SETUP) is HomeResult.Switched)
        val setup = sh.ran.indexOfLast { it == "pm enable --user 0 $SETUP 2>&1" }
        val old = sh.ran.indexOfLast { it == "pm enable --user 0 com.google.android.tvlauncher 2>&1" }
        assertTrue(setup >= 0 && old > setup)
        assertEquals("com.google.android.tvlauncher", Actions(sh).homeOptions()?.current)
    }

    @Test fun homeBackTurnsTheOldLauncherOnAndLeavesTheNewOneOn() = runTest {
        val sh = twoLaunchers()
        Actions(sh).makeHome("com.spocky.projengmenu")
        assertEquals(
            HomeResult.Switched("com.spocky.projengmenu/.ui.home.MainActivity", "com.google.android.tvlauncher/.MainActivity"),
            Actions(sh).homeBack("com.google.android.tvlauncher", "com.google.android.tvlauncher/.MainActivity"),
        )
        val on = sh.ran.indexOf("pm enable --user 0 com.google.android.tvlauncher 2>&1")
        val set = sh.ran.indexOfLast { it == "cmd package set-home-activity --user 0 'com.google.android.tvlauncher/.MainActivity' 2>&1" }
        assertTrue(on >= 0 && set > on)
        assertTrue(sh.ran.none { it == "pm disable-user --user 0 com.spocky.projengmenu 2>&1" })
        assertEquals("com.google.android.tvlauncher", Actions(sh).homeOptions()?.current)
    }

    @Test fun homeBackThatDoesNotTakePutsHomeBack() = runTest {
        val sh = twoLaunchers()
        Actions(sh).makeHome("com.spocky.projengmenu")
        sh.answer.let { prev -> sh.answer = { if (it.startsWith("cmd package set-home-activity")) ok("Success\n") else prev(it) } }
        assertTrue(Actions(sh).homeBack("com.google.android.tvlauncher", "com.google.android.tvlauncher/.MainActivity") is HomeResult.Failed)
        assertTrue(Actions(sh).homeBack("com.spocky.projengmenu") is HomeResult.Failed) // already Home
    }

    @Test fun homeBackThatLeavesHomeNowhereSaysSo() = runTest {
        val sh = twoLaunchers()
        Actions(sh).makeHome("com.spocky.projengmenu")
        sh.answer.let { prev ->
            sh.answer = {
                when {
                    it.startsWith("cmd package set-home-activity") -> ok("Success\n")
                    it.startsWith("cmd package resolve-activity") && sh.ran.count { c -> "set-home" in c } > 1 -> ok("priority=0\ncom.google.android.tungsten.setupwraith/.MainActivity\n")
                    else -> prev(it)
                }
            }
        }
        val r = Actions(sh).homeBack("com.google.android.tvlauncher", "com.google.android.tvlauncher/.MainActivity") as HomeResult.Failed
        assertEquals(Actions.HOME_NOT_BACK, r.output)
    }

    @Test fun launchableListsEveryAppWithALauncherIcon() = runTest {
        val sh = FakeShell(mapOf("cmd package query-activities" to ok(
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n" +
                "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity\n" +
                "org.jellyfin.androidtv/.ui.startup.StartupActivity\n" +
                "org.jellyfin.androidtv/.ui.startup.StartupActivity\n",
        )))
        assertEquals(setOf("com.google.android.youtube.tv", "org.jellyfin.androidtv"), Actions(sh).launchable())
    }

    @Test fun tvLaunchableAsksForTvHomeIconsOnly() = runTest {
        val sh = FakeShell(mapOf("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER" to ok(
            "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity\n",
        )))
        assertEquals(setOf("com.google.android.youtube.tv"), Actions(sh).tvLaunchable())
        assertEquals(null, Actions(FakeShell(mapOf("cmd package" to ok("")))).tvLaunchable())
    }

    @Test fun launchableIsUnknownWithoutCmd() = runTest {
        val sh = FakeShell(mapOf("cmd package" to ShellResult("", 127)))
        assertEquals(null, Actions(sh).launchable())
    }

    // What `pm enable` prints on Android 14 for a package the TV disabled (enabled=2).
    private val shellRefused = ShellResult(
        "Exception occurred while executing 'enable':\n" +
            "java.lang.SecurityException: Shell cannot change component state for com.android.nfc/null to 1\n" +
            "\tat com.android.server.pm.PackageManagerService.setEnabledSettings(PackageManagerService.java:3929)\n",
        255,
    )

    @Test fun enablingWhatTheTvDisabledIsRefused() = runTest {
        val sh = FakeShell(mapOf("pm enable" to shellRefused))
        assertEquals(ActionResult.Refused(ProtectedReason.DISABLED_BY_TV), Actions(sh).run(Action.ENABLE, "com.android.nfc"))
    }

    @Test fun enablingWhatTheUserDisabledStillWorks() = runTest {
        val sh = FakeShell(mapOf("pm enable" to ok("Package com.foo.ads new state: enabled\n")))
        assertEquals(ActionResult.Done(Action.DISABLE), Actions(sh).run(Action.ENABLE, "com.foo.ads"))
    }

    @Test fun otherEnableFailuresStayFailed() = runTest {
        val sh = FakeShell(mapOf("pm enable" to ShellResult("Error: Unknown package: com.gone\n", 255)))
        assertEquals(ActionResult.Failed("Error: Unknown package: com.gone"), Actions(sh).run(Action.ENABLE, "com.gone"))
    }

    @Test fun disabledByTvIsStateTwoOrFourOnly() = runTest {
        val dump = """
            |  Package [com.android.nfc] (3a1b2c):
            |    User 0: ceDataInode=0 installed=true hidden=false suspended=false distractionFlags=0 stopped=true notLaunched=false enabled=2 instant=false virtual=false
            |  Package [com.foo.ads] (4d5e6f):
            |    User 0: ceDataInode=0 installed=true hidden=false suspended=false distractionFlags=0 stopped=true notLaunched=false enabled=3 instant=false virtual=false
            |  Package [com.oem.lazy] (7a8b9c):
            |    User 0: installed=true hidden=false stopped=false notLaunched=false enabled=4
            |  Package [com.google.android.youtube.tv] (1f2e3d):
            |    User 0: ceDataInode=0 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
            |  Package [com.foo.ads] (0a0b0c):
            |    User 0: installed=true hidden=false stopped=false notLaunched=false enabled=2
            |""".trimMargin()
        // The second com.foo.ads is its hidden system package, listed after the installed ones.
        val sh = FakeShell(mapOf("dumpsys package packages" to ok(dump)))
        assertEquals(setOf("com.android.nfc", "com.oem.lazy"), Actions(sh).disabledByTv())
    }

    private val openFlauncher = "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n 'me.efesser.flauncher/.MainActivity' 2>&1"

    @Test fun aSwitchOnlySwitches() = runTest {
        // The hand-off waits for the caller: a service change may still have to come first.
        val sh = stockOff("com.spocky.projengmenu", "me.efesser.flauncher")
        Actions(sh).makeHome("com.spocky.projengmenu")
        assertTrue(Actions(sh).makeHome("me.efesser.flauncher") is HomeResult.Switched)
        val tv = twoLaunchers()
        assertTrue(Actions(tv).makeHome("com.spocky.projengmenu") is HomeResult.Switched)
        assertTrue(Actions(tv).homeBack("com.google.android.tvlauncher", "com.google.android.tvlauncher/.MainActivity") is HomeResult.Switched)
        assertTrue((sh.ran + tv.ran).none { it.startsWith("am ") }, (sh.ran + tv.ran).toString())
    }

    @Test fun handOffStopsTheOldHomeThenOpensTheNewOne() = runTest {
        val sh = FakeShell()
        assertEquals(null, Actions(sh).handOff("com.spocky.projengmenu", "me.efesser.flauncher/.MainActivity"))
        assertEquals(listOf("am force-stop com.spocky.projengmenu 2>&1", openFlauncher), sh.ran)
    }

    @Test fun handOffWithNothingToStopOnlyOpens() = runTest {
        val sh = FakeShell()
        Actions(sh).handOff(null, "me.efesser.flauncher/.MainActivity")
        // Never the new one, either.
        Actions(sh).handOff("me.efesser.flauncher", "me.efesser.flauncher/.MainActivity")
        assertEquals(listOf(openFlauncher, openFlauncher), sh.ran)
    }

    @Test fun handOffSaysWhenTheNewHomeDidNotOpen() = runTest {
        val sh = FakeShell(mapOf("am start" to ok("Error: Activity not started\n")))
        assertEquals(
            HandOffFailed("it didn't open, so press Home on the remote to go there", "Error: Activity not started"),
            Actions(sh).handOff("com.spocky.projengmenu", "me.efesser.flauncher/.MainActivity"),
        )
    }

    @Test fun handOffSaysWhenTheOldHomeDidNotStop() = runTest {
        val sh = FakeShell(mapOf("am force-stop" to ShellResult("Error: denied\n", 1)))
        assertEquals(HandOffFailed("the old home screen didn't close", "Error: denied"), Actions(sh).handOff("com.spocky.projengmenu", "me.efesser.flauncher/.MainActivity"))
        assertTrue(openFlauncher in sh.ran)
    }

    private companion object {
        const val SETUP = "com.google.android.tungsten.setupwraith"
    }
}
