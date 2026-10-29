// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.AdbClient
import app.tellyscope.adb.AllowedKey
import app.tellyscope.data.AccessClient.Who
import app.tellyscope.data.MineBook
import app.tellyscope.data.TvAccess
import app.tellyscope.ui.Copy
import app.tellyscope.ui.Removal
import app.tellyscope.ui.outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** When "another computer is connected" shows, and when Remove all other access may say Done. */
class TvAccessTest {
    @get:Rule val tmp = TemporaryFolder()

    private val phone = "192.0.2.10"
    private val other = "192.0.2.47"
    private val phoneKey = AllowedKey("AA", "tellyscope@phone", 1)
    private val laptop = AllowedKey("BB", "alex@laptop", 2)

    private fun access(vararg clients: AdbClient, keys: List<AllowedKey>? = listOf(phoneKey), mine: Set<String> = emptySet(), tv: String = "192.0.2.20") =
        TvAccess.of(clients.toList(), keys, setOf(phone, "fe80::1"), tv, mine, phoneFingerprint = "AA")

    @Test fun namesGoOnTheirAddressOnly() {
        val a = access(AdbClient(phone, 40000), AdbClient(other, 51234)).named(mapOf(other to "Alex-MacBook"))
        assertEquals(listOf(null, "Alex-MacBook"), a.connected!!.map { it.name })
        assertTrue(a.warning) // a name never makes a computer trusted
    }

    @Test fun onlyThisPhoneConnectedIsNoWarning() {
        val a = access(AdbClient(phone, 40000))
        assertFalse(a.warning)
        assertEquals(listOf(Who.Phone), a.connected!!.map { it.who })
    }

    @Test fun anotherAddressIsAWarningWithItsConnectionsCounted() {
        val a = access(AdbClient(phone, 40000), AdbClient(other, 51234), AdbClient(other, 51240))
        assertTrue(a.warning)
        assertEquals(other, a.others.single().address)
        assertEquals(2, a.others.single().connections)
    }

    @Test fun itsMineSilencesThatAddressOnly() {
        val a = access(AdbClient(other, 1), AdbClient("192.0.2.99", 2), mine = setOf(other))
        assertEquals(listOf("192.0.2.99"), a.others.map { it.address })
        assertEquals(Who.Mine, a.connected!!.first { it.address == other }.who)
    }

    @Test fun loopbackIsAnAppOnTheTvUnlessTellyscopeItselfUsesLoopback() {
        assertEquals(Who.OnTv, access(AdbClient("127.0.0.1", 1)).others.single().who)
        assertFalse(access(AdbClient("127.0.0.1", 1), tv = "127.0.0.1").warning)
        assertFalse(access(AdbClient("::1", 1), tv = "localhost").warning)
    }

    @Test fun cantReadTheTableIsNotNobody() {
        val a = TvAccess.of(null, null, setOf(phone), "tv", emptySet(), "AA")
        assertFalse(a.warning)
        assertFalse(a.onlyThisPhone)
        assertNull(a.connected)
    }

    @Test fun thisPhonesKeyIsMarkedAndFirst() {
        val a = access(keys = listOf(laptop, phoneKey))
        assertEquals(listOf(true, false), a.allowed!!.map { it.phone })
    }

    @Test fun doneOnlyAfterACheckThatFindsNobodyElse() {
        assertEquals(Removal.Step.Done, outcome(access(AdbClient(phone, 1))).step)
        // Not ticking "Always allow" leaves the list empty: still nobody else.
        assertEquals(Removal.Step.Done, outcome(access(AdbClient(phone, 1), keys = emptyList())).step)
        val still = outcome(access(AdbClient(phone, 1), AdbClient(other, 2)))
        assertEquals(Removal.Step.StillConnected, still.step)
        assertEquals(Copy.connectionFrom(other), still.still)
        assertEquals("alex@laptop", outcome(access(AdbClient(phone, 1), keys = listOf(phoneKey, laptop))).still)
        assertEquals(Removal.Step.Unconfirmed, outcome(null).step)
        assertEquals(Removal.Step.Unconfirmed, outcome(access(AdbClient(phone, 1), keys = null)).step)
    }

    @Test fun mineBookPersistsAndFollowsTheTv() {
        val f = tmp.newFile("mine.tsv")
        MineBook(f).apply { add("192.0.2.20", other); add("192.0.2.20", "bad\taddress"); rekey("192.0.2.20", "serial-1") }
        val book = MineBook(f)
        assertEquals(setOf(other), book["serial-1"])
        assertTrue(book["192.0.2.20"].isEmpty())
    }

    @Test fun aConnectionWhoseAllowPromptIsUpIsWaitingNotAWarning() {
        val a = TvAccess.of(listOf(AdbClient(phone, 1), AdbClient(other, 2)), listOf(phoneKey), setOf(phone), "192.0.2.20", emptySet(), "AA", pending = "CC")
        assertEquals(Who.Waiting, a.connected!!.first { it.address == other }.who)
        assertFalse(a.warning)
    }

    @Test fun anAllowedKeyOrASecondComputerIsNeverHiddenAsWaiting() {
        val allowed = TvAccess.of(listOf(AdbClient(other, 2)), listOf(phoneKey, laptop), setOf(phone), "192.0.2.20", emptySet(), "AA", pending = "BB")
        assertTrue(allowed.warning)
        val two = TvAccess.of(listOf(AdbClient(other, 2), AdbClient("192.0.2.99", 3)), listOf(phoneKey), setOf(phone), "192.0.2.20", emptySet(), "AA", pending = "CC")
        assertEquals(2, two.others.size)
    }

    @Test fun aSecondConnectionFromTheWaitingAddressKeepsTheWarning() {
        // An allowed computer opens one more connection with a throwaway key to raise a prompt: its live session stays Other.
        val a = TvAccess.of(listOf(AdbClient(phone, 1), AdbClient(other, 2), AdbClient(other, 3)), listOf(phoneKey), setOf(phone), "192.0.2.20", emptySet(), "AA", pending = "CC")
        assertEquals(Who.Other, a.connected!!.first { it.address == other }.who)
        assertTrue(a.warning)
    }

    @Test fun aKeyAllowedWhileTellyscopeWasntWatchingIsNew() {
        fun keys(known: Set<String>?) =
            TvAccess.of(emptyList(), listOf(phoneKey, laptop), setOf(phone), "192.0.2.20", emptySet(), "AA", known = known).allowed!!
        assertTrue(keys(known = setOf("AA")).single { it.fingerprint == "BB" }.new)
        // First look at this TV: what's there is known. Allowed from Tellyscope's own prompt: known too.
        assertTrue(keys(known = null).none { it.new })
        assertTrue(keys(known = setOf("AA", "BB")).none { it.new })
        assertTrue(keys(known = emptySet()).none { it.phone && it.new })
    }
}
