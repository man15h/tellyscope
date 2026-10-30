// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.Access
import app.tellyscope.adb.AdbClient
import app.tellyscope.adb.AllowedKey
import app.tellyscope.data.AccessClient.Who
import app.tellyscope.data.AccessKey
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
    /** Another computer is allowed, so its connections may be in and are listed. */
    private val both = listOf(phoneKey, laptop)

    private fun access(vararg clients: AdbClient, keys: List<AllowedKey>? = listOf(phoneKey), mine: Set<String> = emptySet(), tv: String = "192.0.2.20") =
        TvAccess.of(clients.toList(), keys, setOf(phone, "fe80::1"), tv, mine, phoneFingerprint = "AA")

    @Test fun namesGoOnTheirAddressOnly() {
        val a = access(AdbClient(phone, 40000), AdbClient(other, 51234), keys = both).named(mapOf(other to "Alex-MacBook"))
        assertEquals(listOf(null, "Alex-MacBook"), a.connected!!.map { it.name })
        assertTrue(a.warning) // a name never makes a computer trusted
    }

    @Test fun onlyThisPhoneConnectedIsNoWarning() {
        val a = access(AdbClient(phone, 40000))
        assertFalse(a.warning)
        assertEquals(listOf(Who.Phone), a.connected!!.map { it.who })
    }

    @Test fun anotherAddressIsAWarningWithItsConnectionsCounted() {
        val a = access(AdbClient(phone, 40000), AdbClient(other, 51234), AdbClient(other, 51240), keys = both)
        assertTrue(a.warning)
        assertEquals(other, a.others.single().address)
        assertEquals(2, a.others.single().connections)
    }

    @Test fun itsMineSilencesThatAddressOnly() {
        val a = access(AdbClient(other, 1), AdbClient("192.0.2.99", 2), keys = both, mine = setOf(other))
        assertEquals(listOf("192.0.2.99"), a.others.map { it.address })
        assertEquals(Who.Mine, a.connected!!.first { it.address == other }.who)
    }

    @Test fun loopbackIsAnAppOnTheTvUnlessTellyscopeItselfUsesLoopback() {
        assertEquals(Who.OnTv, access(AdbClient("127.0.0.1", 1), keys = both).others.single().who)
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
        // A connection left over once only this phone is allowed can't be in.
        assertEquals(Removal.Step.Done, outcome(access(AdbClient(phone, 1), AdbClient(other, 2))).step)
        val still = outcome(access(AdbClient(phone, 1), AdbClient(other, 2), keys = both))
        assertEquals(Removal.Step.StillConnected, still.step)
        assertEquals(Copy.connectionFrom(other), still.still)
        assertEquals("alex@laptop", outcome(access(AdbClient(phone, 1), keys = listOf(phoneKey, laptop))).still?.text())
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
        val two = TvAccess.of(listOf(AdbClient(other, 2), AdbClient("192.0.2.99", 3)), both, setOf(phone), "192.0.2.20", emptySet(), "AA", pending = "CC")
        assertEquals(2, two.others.size)
    }

    @Test fun aSecondConnectionFromTheWaitingAddressKeepsTheWarning() {
        // An allowed computer opens one more connection with a throwaway key to raise a prompt: its live session stays Other.
        val a = TvAccess.of(listOf(AdbClient(phone, 1), AdbClient(other, 2), AdbClient(other, 3)), both, setOf(phone), "192.0.2.20", emptySet(), "AA", pending = "CC")
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

    /**
     * A Fire TV (Fire OS 8) with this phone allowed and a second computer connected whose prompt
     * was left unanswered, as the TV printed them; addresses and keys swapped for test ones.
     */
    private val phoneLine = "QAAAADXck8LjaYpJ3oP79QqoEB3ndqfRPvDkW7cENIXOfhfMhQro5k2QHVdmSgnErB/HixaHOBfxV3+ggEtcBpLMLFP0dRYarC233tG6MzYZdflAvibrbMQHRjAfAvoPQW2RAAj86mqRg9RC8av0ZV0/jedJ/qchmzY3N1kfdhCF1S1ibK9YDImTu2NzVSZdv2B1m8BlveXGXpibcVZ3EP5+qma2wWZ4ufNffeENRD/04tcRqsXCLbqPvvK6OP2Zz38KdWt0zYs5vPB+EBcluh7sgqGhUPy4OYFJlRpE3kO/bTYCOkEkTWC0dFyWi8S9xnGWvdYVUWIds9cqO4NMAMJZYJAAPMnMWVQhTk5ibxHmqyCYiYSHqj4cF/E/QP5sK3mlld0TpoYssJYRMcmawoZG3BU8YWN21g8Gw8ja/Hj187D6d85wh/SGsP271ckq5/M0fIxm3hUbwuodeXmjFcUZmAKGhwICjf97Ycx7y75GPa0rFE0+mMX98eAqqMjNCKlSbZEMW/Al2KCQfVNN8IRhK7z8F/hLsZQ3VeSCu1w+EVIHnJjz/89+A18DuN/ymtf2lkDCyrABduPlSupS0xae2sPvBzVUcpTtGMa+4TXvlrcsfBAajttxmLaZLztXfzSztUwLVYmKYvfs7dxKGVuV1CMsb3iqJsyr57L5fuelW7yxr7pEmQEAAQA= tellyscope@phone"
    private val laptopLine = "QAAAAOldhNintZnTuU76eSlXmTOY+n6tHXIWrVLcyju1Jhb+p7jn0WmdQ7iYZvD9Jz6/NNh5ovkHSiKoHILb0EN/4jlkU1jMG5KDp2WvjmEtF4S7EOVVgZvJclAbJP98iHV6iO68AsDs40KOIqnQ76SGkQVX27WQLjMaUQfucEZ1OUgvpp6sW5MEgtv2DV1DvpqZVPGPQWeP1XRC91kdVkZxZfuzSqpq75yj2fVywX36ErR+OWRbUcwjrIQ2sTs6R2D6/nRveJplwjRR3jcoz5TOMRAYP+eTERCTLLugrww9OLvLZbcPhcr/b5it/b7bgvETlC5jOfVFuJysEraNsbor95TnwL3TLpdfjWNBxOj6hTv8vg6l9lB6lDZDWB+hRf92KNJVUwTCMPrUHtJxsVjBvsO1jwrPxj1qxFKotH0YWaGqlp6uK7/2BsUZV/Zyc3gbdQ9q0rGhuCf77TqmWaz/giD2yxyBb9bLPAy3hbBOvzJ14s1+ne8FrqTl4wU1ZU6MOC2Q7xOIUsErXXBiQy/m+LeD8awKFfbG6ilKuEMzh5KaEMIt/joUYqe36xjWpiEFae+Gqfs/eDPVIliWJUhC/a+VNMVYW3qUhob+y1+Jg0+D1AsbNBqX19J1KiAiWNB9k2JPuzKWJ9GfA7m0Y5zoZEioxXX1RQzVzwtlLi2jA4IaO4jGpwEAAQA= alex@laptop"
    private val prompt = "5E:A1:07:3B:C2:94:6D:18:F0:2C:8B:E3:41:7A:D6:90"

    /** `dumpsys adb` with [lines] allowed; none is the TV after a revoke, with no `user_keys` at all. */
    private fun dumpsys(vararg lines: String) = listOfNotNull(
        "ADB MANAGER STATE (dumpsys adb):",
        "{",
        "  debugging_manager={",
        "    connected_to_adb=true",
        "    last_key_received=$prompt",
        lines.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "    user_keys="),
        "    ",
        "    keystore=<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
        "    <keyStore version=\"1\">",
    ) + lines.map { "    <adbKey key=\"$it\" lastConnection=\"1793345481953\" />" } + listOf(
        "    </keyStore>",
        "    ",
        "  }",
        "}",
    )

    /** `/proc/net/tcp6`: adbd listening on 5555, this phone's session, and the unanswered one. */
    private val tcp6 = """
          sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 00000000000000000000000000000000:15B3 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 14528 1 0000000000000000 100 0 0 10 0
           1: 0000000000000000FFFF0000140200C0:15B3 0000000000000000FFFF00000A0200C0:A0A8 01 00000000:00000000 02:000A2F9A 00000000  2000        0 14529 2 0000000000000000 20 4 30 10 -1
           2: 0000000000000000FFFF0000140200C0:15B3 0000000000000000FFFF00002F0200C0:C53A 01 00000000:00000000 02:000A2FA1 00000000  2000        0 75142 2 0000000000000000 20 4 30 10 -1
    """.trimIndent()

    private fun captured(dumpsys: String, pending: String? = null) = TvAccess.of(
        Access.clients(tcp6, setOf(Access.CLASSIC_PORT)), Access.keys(dumpsys), setOf(phone), "192.0.2.20", emptySet(),
        Access.fingerprint(phoneLine.substringBefore(' ')), pending = pending,
    )

    private fun captured(lines: List<String>, pending: String? = null) = captured(lines.joinToString("\n"), pending)

    @Test fun withOnlyThisPhoneAllowedTheOtherConnectionIsntListed() {
        val a = captured(dumpsys(phoneLine))
        assertEquals(listOf(Who.Phone), a.connected!!.map { it.who })
        assertFalse(a.warning)
        assertTrue(a.onlyThisPhone)
    }

    @Test fun afterARevokeLeftoverConnectionsArentListed() {
        val a = captured(dumpsys())
        assertEquals(emptyList<AccessKey>(), a.allowed)
        assertEquals(listOf(Who.Phone), a.connected!!.map { it.who })
    }

    @Test fun withASecondAllowedKeyTheOtherConnectionIsListed() {
        val a = captured(dumpsys(phoneLine, laptopLine))
        assertEquals(listOf(Who.Phone, Who.Other), a.connected!!.map { it.who })
        assertEquals(other, a.others.single().address)
        assertTrue(a.warning)
    }

    @Test fun whenTheKeysCantBeReadEveryConnectionIsListed() {
        val a = captured("Can't find service: adb")
        assertNull(a.allowed)
        assertEquals(listOf(Who.Phone, Who.Other), a.connected!!.map { it.who })
        assertTrue(a.warning)
    }

    @Test fun aWaitingConnectionIsListedWithOnlyThisPhoneAllowed() {
        val a = captured(dumpsys(phoneLine), pending = prompt)
        assertEquals(listOf(Who.Phone, Who.Waiting), a.connected!!.map { it.who })
        assertFalse(a.warning)
    }
}
