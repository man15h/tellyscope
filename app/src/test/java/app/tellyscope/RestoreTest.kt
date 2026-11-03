// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.Tweak
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.HistoryBook
import app.tellyscope.data.ResetPlan
import app.tellyscope.data.ResetApp
import app.tellyscope.data.ResetRecord
import app.tellyscope.data.RestoreRun
import app.tellyscope.data.UiText
import app.tellyscope.data.Was
import app.tellyscope.data.summary
import app.tellyscope.ui.DeviceUi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restore everything puts each thing back as it was before Tellyscope's first change to it, in one step or none. */
class RestoreTest {
    private val tv = "tv"
    private val netflix = "com.netflix.ninja"
    // Off on the demo TV before anything changed.
    private val recommendations = "com.google.android.tvrecommendations"

    private suspend fun FakeDeviceSource.enabled(pkg: String) = apps(tv).single { it.packageName == pkg }.enabled
    private suspend fun FakeDeviceSource.count() = DeviceUi(apps = apps(tv), tweaks = tweaks(tv), restorable = restorable(tv)).restore.count

    @Test fun offFiveTimesOnFourIsOneTurnOn() = runTest {
        val source = FakeDeviceSource()
        repeat(4) { source.act(tv, Action.Disable, netflix); source.act(tv, Action.Enable, netflix) }
        source.act(tv, Action.Disable, netflix)
        assertEquals(1, source.count())
        assertEquals(1, source.restoreAll(tv).done)
        assertTrue(source.enabled(netflix))
    }

    @Test fun offFiveTimesOnFiveIsNothing() = runTest {
        val source = FakeDeviceSource()
        repeat(5) { source.act(tv, Action.Disable, netflix); source.act(tv, Action.Enable, netflix) }
        assertEquals(0, source.count())
        assertEquals(0, source.restoreAll(tv).done)
        assertTrue(source.enabled(netflix))
    }

    @Test fun anAppThatWasOffGoesOffAgain() = runTest {
        val source = FakeDeviceSource()
        source.act(tv, Action.Enable, recommendations)
        source.act(tv, Action.Disable, recommendations)
        source.act(tv, Action.Enable, recommendations)
        assertEquals(1, source.count())
        assertEquals(1, source.restoreAll(tv).done)
        assertFalse(source.enabled(recommendations))
    }

    @Test fun whatResetTurnedOnGoesOffAgain() = runTest {
        val source = FakeDeviceSource()
        source.reset(tv, ResetPlan(turnOn = listOf(ResetApp(recommendations, "TV Recommendations"))), null) {}
        assertTrue(source.enabled(recommendations))
        assertEquals(1, source.restoreAll(tv).done)
        assertFalse(source.enabled(recommendations))
    }

    @Test fun debloatThenResetThenDebloatTurnsAllBackOn() {
        val book = HistoryBook()
        val apps = (1..91).map { "com.bloat.app$it" }
        var t = 0L
        apps.forEach { book.record(tv, t++, Action.Disable, it, it, Action.Enable) }
        book.record(tv, t++, Action.Reset, "reset", "Reset TV", Action.Reset, previous = mapOf(ResetRecord.ON to apps.joinToString(",")))
        apps.forEach { book.record(tv, t++, Action.Disable, it, it, Action.Enable) }
        val plan = book.restorable(tv).plan { Was.Off }
        assertEquals(apps.toSet(), plan.turnOn.toSet())
        assertEquals(91, plan.turnOn.size)
        assertTrue(plan.turnOff.isEmpty())
    }

    @Test fun homeAfterTwoSwitchesGoesBackToTheFirst() {
        // L → A → B: the TV's own launcher L is Home again, and both turned-off launchers come back on.
        val own = "com.google.android.tvlauncher"
        val a = "me.efesser.launcher"
        val b = "com.spocky.projengmenu"
        val book = HistoryBook()
        book.record(tv, 1, Action.Home, own, "A", Action.Enable, previous = mapOf("from" to "$own/.Main", "to" to "$a/.Main"))
        book.record(tv, 2, Action.Home, a, "B", Action.Enable, previous = mapOf("from" to "$a/.Main", "to" to "$b/.Main"))
        val restorable = book.restorable(tv)
        assertEquals("$own/.Main", restorable.home)
        // Newest first: undoing A → B makes A Home, then L → A makes L Home.
        assertEquals(listOf(a, own), restorable.plan { if (it == b) Was.On else Was.Off }.turnOn)

        fun app(pkg: String, on: Boolean, home: Boolean = false) = AppInfo(pkg, pkg, null, false, on, null, "1", home = home)
        // Turning both on already lands on L, so Home is no extra step.
        val both = restorable.summary(listOf(app(own, false), app(a, false), app(b, true, home = true)), null)
        assertEquals(2, both.turnOn)
        assertFalse(both.home)
        // L turned back on from the TV: A's switch back leaves Home on A, so Restore moves it to L.
        val onlyA = restorable.summary(listOf(app(own, true), app(a, false), app(b, true, home = true)), null)
        assertEquals(1, onlyA.turnOn)
        assertTrue(onlyA.home)
    }

    @Test fun settingsGoBackToTheirFirstValue() = runTest {
        val source = FakeDeviceSource()
        listOf("one.dns.example", "two.dns.example", "three.dns.example").forEach {
            source.change(tv, Tweak.PRIVATE_DNS, mapOf("private_dns_specifier" to it, "private_dns_mode" to "hostname"), null)
        }
        listOf("0.5", "0", "2.0").forEach { source.change(tv, Tweak.ANIMATIONS, mapOf("window_animation_scale" to it), null) }
        assertEquals(mapOf("private_dns_specifier" to null, "private_dns_mode" to "opportunistic"), source.restorable(tv).settings[Tweak.PRIVATE_DNS])
        assertEquals(2, source.count())

        assertEquals(2, source.restoreAll(tv).done)
        val tweaks = source.tweaks(tv)
        assertNull(tweaks.dnsHost)
        assertNull(tweaks.animationScale)
        assertEquals(0, source.count())
    }

    @Test fun aSecondRestoreStillAimsAtTheFirstState() = runTest {
        val source = FakeDeviceSource()
        source.act(tv, Action.Enable, recommendations)
        source.restoreAll(tv)
        // Turned on again after the first Restore: still off before Tellyscope, so off it goes.
        source.act(tv, Action.Enable, recommendations)
        source.act(tv, Action.Disable, netflix)
        assertEquals(2, source.count())
        assertEquals(2, source.restoreAll(tv).done)
        assertFalse(source.enabled(recommendations))
        assertTrue(source.enabled(netflix))
        assertEquals(0, source.restoreAll(tv).done)
    }

    @Test fun whatTheTvAlreadyShowsAsBeforeIsSkipped() {
        val book = HistoryBook()
        book.record(tv, 1, Action.Disable, netflix, "Netflix", Action.Enable)
        book.record(tv, 2, Action.Enable, recommendations, "TV Recommendations", Action.Disable)
        // Netflix turned back on and Recommendations off from the TV itself.
        val plan = book.restorable(tv).plan { if (it == netflix) Was.On else Was.Off }
        assertTrue(plan.turnOn.isEmpty() && plan.turnOff.isEmpty() && plan.remove.isEmpty())
    }

    @Test fun anAppResetBroughtBackIsRemovedAgain() {
        val book = HistoryBook()
        book.record(tv, 1, Action.Reset, "reset", "Reset TV", Action.Reset, previous = mapOf(ResetRecord.BACK to "com.xgimi.ota"))
        assertEquals(listOf("com.xgimi.ota"), book.restorable(tv).plan { Was.On }.remove)
    }

    @Test fun anUninstalledAppIsNamedNotCounted() = runTest {
        val source = FakeDeviceSource()
        source.act(tv, Action.Disable, netflix)
        source.act(tv, Action.Uninstall, netflix)
        val restore = DeviceUi(apps = source.apps(tv), restorable = source.restorable(tv)).restore
        assertEquals(1, restore.gone)
        assertEquals(0, restore.count)
    }

    @Test fun aRefusalDoesntStopTheRest() = runTest {
        val run = RestoreRun()
        var ran = 0
        run.step(UiText.Raw("Netflix")) { ran++ }
        run.step(UiText.Raw("Jellyfin")) { throw IllegalStateException("the TV refused") }
        run.step(UiText.Raw("YouTube")) { throw java.io.IOException("connection reset") }
        run.step(UiText.Raw("XGIMI Updates")) { ran++ }
        val r = run.result()
        assertEquals(2, ran)
        assertEquals(2, r.done)
        assertEquals(listOf("Jellyfin", "YouTube"), r.failed.map { it.what.text() })
        assertEquals("Put back 2 of 4 changes. 2 didn't go back, Jellyfin among them: the TV refused",
            UiText.plural(R.plurals.vm_put_back_partly, r.failed.size, r.done, r.done + r.failed.size, r.failed.size, r.failed[0].what, r.failed[0].why).text())
    }

    @Test fun aFailedStepStaysRestorable() = runTest {
        val book = HistoryBook()
        book.record(tv, 1, Action.Disable, netflix, "Netflix", Action.Enable)
        // Nothing is recorded for a step the TV refused, so the next Restore tries it again.
        RestoreRun().step(UiText.Raw("Netflix")) { throw IllegalStateException("the TV refused") }
        assertEquals(listOf(netflix), book.restorable(tv).plan { Was.Off }.turnOn)
    }
}
