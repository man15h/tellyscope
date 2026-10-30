// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.Action as AdbAction
import app.tellyscope.adb.Actions
import app.tellyscope.adb.AdbPipe
import app.tellyscope.adb.AdbSession
import app.tellyscope.adb.RemoteEntry
import app.tellyscope.adb.ShellResult
import app.tellyscope.data.Action
import app.tellyscope.data.ActionFailed
import app.tellyscope.data.HistoryBook
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.ResetRecord
import app.tellyscope.data.UiText
import app.tellyscope.data.UiTextException
import app.tellyscope.data.fireHomeBack
import app.tellyscope.data.fireServiceHolder
import app.tellyscope.data.fireServiceHome
import app.tellyscope.data.firstHomeService
import app.tellyscope.data.switchHome
import app.tellyscope.data.undoFireResetHome
import app.tellyscope.data.undoHomeSwitch
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okio.Sink
import okio.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val TV_HOME = "com.google.android.tvlauncher"
private const val PROJECTIVY = "com.spocky.projengmenu"
private const val FLAUNCHER = "me.efesser.flauncher"
private const val PROJECTIVY_SERVICE = "$PROJECTIVY/.HomeService"

/** A TV with its own launcher and two the user added; Projectivy has an accessibility service. */
private class LauncherTv(var home: String?) : AdbSession {
    val activities = mapOf(TV_HOME to "$TV_HOME/.MainActivity", PROJECTIVY to "$PROJECTIVY/.Main", FLAUNCHER to "$FLAUNCHER/.MainActivity")
    val enabled = activities.keys.toMutableSet()
    val user = setOf(PROJECTIVY, FLAUNCHER)
    val services = mutableListOf<String>()
    /** False: `am start` can't open a home screen, as on a TV that refuses it. */
    var opens = true
    val ran = mutableListOf<String>()

    override suspend fun shell(command: String): ShellResult {
        ran += command
        val pkg = command.substringAfter("--user 0 ").substringBefore(' ')
        val quoted = command.substringAfter("'").substringBefore("'")
        return when {
            command.startsWith("cmd package resolve-activity") -> ok("priority=0\n${home?.let(activities::getValue) ?: "android/com.android.internal.app.ResolverActivity"}\n")
            command.startsWith("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME") -> ok(activities.filterKeys { it in enabled }.values.joinToString("") { "priority=0\n$it\n" })
            command.startsWith("cmd package set-home-activity") -> ok("Success\n").also { quoted.substringBefore('/').takeIf { it in enabled }?.let { home = it } }
            command.startsWith("pm list packages -3") -> ok(user.joinToString("") { "package:$it\n" })
            command.startsWith("pm disable-user") -> ok("Package $pkg new state: disabled-user\n").also { enabled -= pkg }
            command.startsWith("pm enable") -> ok("Package $pkg new state: enabled\n").also { enabled += pkg }
            command.startsWith("cmd package query-services") -> ok("$PROJECTIVY_SERVICE\n")
            command.startsWith("settings get secure enabled_accessibility_services") -> ok(services.joinToString(":").ifEmpty { "null" } + "\n")
            command.startsWith("settings put secure enabled_accessibility_services") -> ok("").also { services.clear(); services += quoted.split(':') }
            command.startsWith("settings delete secure enabled_accessibility_services") -> ok("").also { services.clear() }
            command.startsWith("am start") && !opens -> ok("Error: Activity not started\n")
            else -> ok("")
        }
    }

    private fun ok(out: String) = ShellResult(out, 0)

    override suspend fun push(bytes: ByteArray, remotePath: String) = Unit
    override suspend fun reboot() = Unit
    override suspend fun exec(command: String): AdbPipe = throw IOException("no exec")
    override fun install(source: Source, size: Long, vararg options: String) = Unit
    override fun installMultiple(apks: List<File>, vararg options: String) = Unit
    override fun push(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) = Unit
    override fun pull(sink: Sink, remotePath: String) = Unit
    override fun list(remoteDir: String) = emptyList<RemoteEntry>()
    override fun close() = Unit
}

/** The switch rule: only the TV's own launcher goes off, and Undo reverses exactly what the switch did. */
class HomeSwitchTest {
    private val book = HistoryBook()

    private suspend fun switch(tv: LauncherTv, pkg: String, withService: Boolean = true): HistoryEntry =
        switchHome(Actions(tv), pkg, withService, setOf(PROJECTIVY), { it }, { UiText.Raw(it.name) }) { from, undo, previous ->
            book.record("tv", 0, Action.Home, from, pkg, undo, previous = previous)
        }

    /** What Undo in History runs for a switch: its old launcher on again, then the rest. */
    private suspend fun undo(tv: LauncherTv, entry: HistoryEntry) {
        Actions(tv).run(AdbAction.ENABLE, entry.packageName)
        assertTrue(undoHomeSwitch(Actions(tv), entry))
    }

    @Test fun theTvsOwnLauncherIsTurnedOffAndUndoTurnsItBackOn() = runTest {
        val tv = LauncherTv(home = TV_HOME)
        val entry = switch(tv, FLAUNCHER)
        assertEquals(FLAUNCHER, tv.home)
        assertFalse(TV_HOME in tv.enabled)
        assertEquals(listOf(TV_HOME), HistoryBook.leftDisabled(book.entries("tv")))
        undo(tv, entry)
        assertEquals(TV_HOME, tv.home)
        assertTrue(TV_HOME in tv.enabled)
    }

    @Test fun aLauncherTheUserAddedStaysOnAndUndoMakesItHomeAgain() = runTest {
        val tv = LauncherTv(home = FLAUNCHER)
        val entry = switch(tv, PROJECTIVY, withService = false)
        assertEquals(PROJECTIVY, tv.home)
        assertTrue(FLAUNCHER in tv.enabled)
        // Nothing went off, so Restore has nothing to turn on.
        assertEquals(emptyList<String>(), HistoryBook.leftDisabled(book.entries("tv")))
        undo(tv, entry)
        assertEquals(FLAUNCHER, tv.home)
    }

    @Test fun switchingAwayFromAServiceLauncherTurnsItsServiceOffAndUndoBackOn() = runTest {
        val tv = LauncherTv(home = PROJECTIVY).apply { services += PROJECTIVY_SERVICE }
        val entry = switch(tv, FLAUNCHER)
        assertEquals(FLAUNCHER, tv.home)
        assertTrue(PROJECTIVY in tv.enabled)
        assertTrue(tv.services.isEmpty())
        undo(tv, entry)
        assertEquals(PROJECTIVY, tv.home)
        assertEquals(listOf(PROJECTIVY_SERVICE), tv.services)
    }

    @Test fun switchingToAServiceLauncherTurnsItsServiceOnAndUndoOff() = runTest {
        val tv = LauncherTv(home = TV_HOME)
        val entry = switch(tv, PROJECTIVY)
        assertEquals(listOf(PROJECTIVY_SERVICE), tv.services)
        undo(tv, entry)
        assertEquals(TV_HOME, tv.home)
        assertTrue(tv.services.isEmpty())
    }

    @Test fun withNoDefaultTheTvsOwnLauncherBecomesTheDefaultWithNothingTurnedOff() = runTest {
        val tv = LauncherTv(home = null)
        val entry = switch(tv, TV_HOME)
        assertEquals(TV_HOME, tv.home)
        assertEquals(setOf(TV_HOME, PROJECTIVY, FLAUNCHER), tv.enabled)
        // Nothing to reverse: no launcher went off and no service came on.
        assertNull(entry.undo)
    }

    @Test fun aHomeThatDoesNotOpenStillSwitchesAndIsRecordedBeforeItSays() = runTest {
        val tv = LauncherTv(TV_HOME).apply { opens = false }
        val e = runCatching { switch(tv, FLAUNCHER) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e is ActionFailed)
        assertEquals("$FLAUNCHER is Home now, but it didn't open, so press Home on the remote to go there", (e as UiTextException).text.text())
        assertEquals("Error: Activity not started", (e as ActionFailed).details)
        assertEquals(FLAUNCHER, tv.home)
        assertEquals(TV_HOME, book.entries("tv").single().packageName)
    }

    private val stop = { pkg: String -> "am force-stop $pkg 2>&1" }
    private val open = { pkg: String -> "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n '$pkg' 2>&1" }

    @Test fun aLauncherTheUserAddedIsClosedThenTheNewOneOpens() = runTest {
        val tv = LauncherTv(home = FLAUNCHER)
        switch(tv, PROJECTIVY, withService = false)
        val set = tv.ran.indexOfFirst { it.startsWith("cmd package set-home-activity --user 0 '$PROJECTIVY/") }
        val closed = tv.ran.indexOf(stop(FLAUNCHER))
        val opened = tv.ran.indexOf(open(tv.activities.getValue(PROJECTIVY)))
        assertTrue(tv.ran.toString(), set >= 0 && closed > set && opened > closed)
        assertFalse(stop(PROJECTIVY) in tv.ran)
    }

    @Test fun theTvsOwnLauncherIsNotStoppedTurningItOffClosedIt() = runTest {
        val tv = LauncherTv(home = TV_HOME)
        switch(tv, FLAUNCHER)
        val off = tv.ran.indexOfFirst { it.startsWith("pm disable-user --user 0 $TV_HOME") }
        assertTrue(tv.ran.toString(), off >= 0 && tv.ran.indexOf(open(tv.activities.getValue(FLAUNCHER))) > off)
        assertTrue(tv.ran.none { it.startsWith("am force-stop") })
    }

    @Test fun theNewHomeOpensOnlyAfterItsServiceIsOn() = runTest {
        val tv = LauncherTv(home = TV_HOME)
        switch(tv, PROJECTIVY)
        val on = tv.ran.indexOfLast { it.startsWith("settings put secure enabled_accessibility_services") }
        assertTrue(tv.ran.toString(), on >= 0 && tv.ran.indexOf(open(tv.activities.getValue(PROJECTIVY))) > on)
    }

    @Test fun theNewHomeOpensOnlyAfterTheOldOnesServiceIsOff() = runTest {
        // Projectivy's service still on would pull Home straight back to it.
        val tv = LauncherTv(home = PROJECTIVY).apply { services += PROJECTIVY_SERVICE }
        switch(tv, FLAUNCHER)
        val off = tv.ran.indexOfLast { it.startsWith("settings delete secure enabled_accessibility_services") || it.startsWith("settings put secure enabled_accessibility_services") }
        val opened = tv.ran.indexOf(open(tv.activities.getValue(FLAUNCHER)))
        assertTrue(tv.ran.toString(), off >= 0 && opened > off && tv.ran.indexOf(stop(PROJECTIVY)) > off)
    }

    private suspend fun fire(tv: LauncherTv, withService: Boolean = true): HistoryEntry =
        fireServiceHome(Actions(tv), PROJECTIVY, withService, { it }) { previous ->
            book.record("tv", 0, Action.Home, PROJECTIVY, PROJECTIVY, Action.Enable.takeIf { previous.isNotEmpty() }, previous = previous)
        }

    @Test fun onFireOsOnlyTheServiceGoesOnAndItsUndoIsTheServiceAlone() = runTest {
        val tv = LauncherTv(home = TV_HOME)
        val entry = fire(tv)
        assertEquals(listOf(PROJECTIVY_SERVICE), tv.services)
        // Fire OS refuses both, so neither is tried: the TV's own launcher stays on and stays the default.
        assertEquals(TV_HOME, tv.home)
        assertTrue(TV_HOME in tv.enabled)
        assertFalse(tv.ran.toString(), tv.ran.any { it.startsWith("pm disable-user") || it.startsWith("cmd package set-home-activity") })
        assertEquals(emptyList<String>(), HistoryBook.leftDisabled(book.entries("tv")))
        // A home with nothing replaced: its Undo is the service alone.
        assertEquals(PROJECTIVY_SERVICE, entry.firstHomeService)
        assertEquals(Action.Enable, entry.undo)
        // Then Projectivy opens, so the TV shows it without a Home press.
        assertTrue(tv.ran.toString(), tv.ran.any { it.startsWith("am start") && it.contains(tv.activities.getValue(PROJECTIVY)) })
    }

    @Test fun onFireOsAServiceAlreadyOnChangesNothing() = runTest {
        val tv = LauncherTv(home = TV_HOME).apply { services += PROJECTIVY_SERVICE }
        val entry = fire(tv)
        assertNull(entry.undo)
        assertFalse(tv.ran.toString(), tv.ran.any { it.startsWith("settings put") })
    }

    @Test fun onFireOsNoToTheServiceChangesNothing() = runTest {
        val tv = LauncherTv(home = TV_HOME)
        try {
            fire(tv, withService = false)
            fail("expected a refusal")
        } catch (_: IllegalStateException) {
        }
        assertTrue(tv.ran.toString(), tv.ran.isEmpty())
        assertTrue(book.entries("tv").isEmpty())
    }

    @Test fun onFireOsTheLauncherWhoseServiceIsOnHoldsHome() = runTest {
        val tv = LauncherTv(home = TV_HOME)
        assertNull(Actions(tv).fireServiceHolder(listOf(FLAUNCHER, PROJECTIVY)))
        tv.services += PROJECTIVY_SERVICE
        assertEquals(PROJECTIVY to PROJECTIVY_SERVICE, Actions(tv).fireServiceHolder(listOf(FLAUNCHER, PROJECTIVY)))
    }

    @Test fun onFireOsGoingBackTurnsTheServiceOffAndItsUndoOn() = runTest {
        val tv = LauncherTv(home = TV_HOME).apply { services += PROJECTIVY_SERVICE }
        val back = fireHomeBack(Actions(tv), PROJECTIVY to PROJECTIVY_SERVICE, { it }) { to, previous ->
            book.record("tv", 0, Action.HomeBack, PROJECTIVY, to ?: "", Action.Home, previous = previous)
        }
        assertTrue(tv.services.isEmpty())
        assertEquals(TV_HOME, back.label)
        assertEquals(mapOf("service" to PROJECTIVY_SERVICE), back.previous)
        // The default never moved: Home only opens the TV's own, which is on screen now.
        assertEquals(TV_HOME, tv.home)
        assertTrue(tv.ran.toString(), tv.ran.any { it.contains(tv.activities.getValue(TV_HOME)) && it.startsWith("am start") })
        fire(tv)
        assertEquals(listOf(PROJECTIVY_SERVICE), tv.services)
    }

    @Test fun onFireOsUndoOfAResetTurnsTheServiceBackOn() = runTest {
        val tv = LauncherTv(home = TV_HOME)
        assertEquals(true, undoFireResetHome(Actions(tv), mapOf(ResetRecord.SERVICE to PROJECTIVY_SERVICE)))
        assertEquals(listOf(PROJECTIVY_SERVICE), tv.services)
        assertEquals(TV_HOME, tv.home)
    }

    @Test fun undoOfAResetThatMovedHomeLeavesTheServiceToItsOwnBranch() = runTest {
        val tv = LauncherTv(home = TV_HOME)
        val previous = mapOf(ResetRecord.TO to "$TV_HOME/.MainActivity", ResetRecord.SERVICE to PROJECTIVY_SERVICE)
        assertNull(undoFireResetHome(Actions(tv), previous))
        assertTrue(tv.ran.toString(), tv.ran.isEmpty())
    }
}
