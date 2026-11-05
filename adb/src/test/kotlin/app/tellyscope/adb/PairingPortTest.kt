// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import com.flyfishxu.kadb.mdns.KadbMdns
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.flyfishxu.kadb.mdns.MdnsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** mDNS that already sees [endpoints]. */
private class FakeMdns(vararg endpoints: MdnsEndpoint) : KadbMdns {
    override val state = MutableStateFlow(
        MdnsDiscoveryState(MdnsStatus.STARTED, false, endpoints.filter { it.serviceType == MdnsServiceType.TLS_CONNECT }, endpoints.filter { it.serviceType == MdnsServiceType.TLS_PAIRING }),
    )
    var running = false
    override fun start() { running = true }
    override fun stop() { running = false }
}

class PairingPortTest {
    private val tv = "192.0.2.30"

    /** Pairs only on [open]; every other port refuses. Records the ports tried. */
    private class Tv(private val open: Int) {
        val tried = mutableListOf<Int>()
        suspend fun pair(port: Int): PairResult {
            tried += port
            return if (port == open) PairResult.Paired else PairResult.Unreachable("Connection refused")
        }
    }

    @Test fun findsThePairingPortForThatTvOnly() = runBlocking {
        val mdns = FakeMdns(
            MdnsEndpoint("adb-other", "192.0.2.31", 37001, MdnsServiceType.TLS_PAIRING),
            MdnsEndpoint("adb-tv", tv, 46237, MdnsServiceType.TLS_CONNECT),
            MdnsEndpoint("adb-tv", tv, 41089, MdnsServiceType.TLS_PAIRING),
        )
        assertEquals(41089, LanScanner(mdns, mdnsWindow = 200.milliseconds).pairingPort(tv))
        assertFalse(mdns.running)
    }

    @Test fun noPairingDialogMeansNoPort() = runBlocking {
        val mdns = FakeMdns(MdnsEndpoint("adb-tv", tv, 46237, MdnsServiceType.TLS_CONNECT))
        assertNull(LanScanner(mdns, mdnsWindow = 200.milliseconds).pairingPort(tv))
        assertFalse(mdns.running)
    }

    @Test fun findsTheConnectPortOfThatTvOnly() = runBlocking {
        // A second TV took over the address; only the saved instance name counts.
        val mdns = FakeMdns(
            MdnsEndpoint("adb-QV7124ZK-3fRb2c", "192.0.2.31", 37001, MdnsServiceType.TLS_CONNECT),
            MdnsEndpoint("adb-XK03H1234-a81Fq0", tv, 39115, MdnsServiceType.TLS_CONNECT),
            MdnsEndpoint("adb-QV7124ZK-3fRb2c", tv, 41089, MdnsServiceType.TLS_PAIRING),
            MdnsEndpoint("adb-QV7124ZK-3fRb2c", tv, 46237, MdnsServiceType.TLS_CONNECT),
        )
        assertEquals(46237, LanScanner(mdns, mdnsWindow = 200.milliseconds).connectPort(tv, "adb-QV7124ZK-3fRb2c"))
        assertFalse(mdns.running)
    }

    @Test fun anotherTvOnTheAddressIsNoConnectPort() = runBlocking {
        val mdns = FakeMdns(MdnsEndpoint("adb-XK03H1234-a81Fq0", tv, 39115, MdnsServiceType.TLS_CONNECT))
        assertNull(LanScanner(mdns, mdnsWindow = 200.milliseconds).connectPort(tv, "adb-QV7124ZK-3fRb2c"))
        assertFalse(mdns.running)
    }

    @Test fun unknownPortIsFoundAtPairTime() = runBlocking {
        val t = Tv(open = 41089)
        assertEquals(PairResult.Paired, pairOnFreshPort(hint = null, find = { 41089 }, pair = t::pair))
        assertEquals(listOf(41089), t.tried)
    }

    @Test fun aFreshPortBeatsTheCachedOne() = runBlocking {
        val t = Tv(open = 40211)
        assertEquals(PairResult.Paired, pairOnFreshPort(hint = 41089, find = { 40211 }, pair = t::pair))
        assertEquals(listOf(40211), t.tried)
    }

    @Test fun cachedPortRefusedThenAFreshOneIsFound() = runBlocking {
        // The first browse misses; the dialog was reopened, so the cached port is gone.
        val finds = ArrayDeque(listOf(null, 40211))
        val t = Tv(open = 40211)
        assertEquals(PairResult.Paired, pairOnFreshPort(hint = 41089, find = { finds.removeFirst() }, pair = t::pair))
        assertEquals(listOf(41089, 40211), t.tried)
    }

    @Test fun nothingAdvertisedAndNoHintNeverTriesTheConnectPort() = runBlocking {
        val t = Tv(open = 46237)
        assertIs<PairResult.Unreachable>(pairOnFreshPort(hint = null, find = { null }, pair = t::pair))
        assertEquals(emptyList(), t.tried)
    }

    @Test fun aWrongCodeIsNotRetried() = runBlocking {
        var finds = 0
        val r = pairOnFreshPort(hint = null, find = { finds++; 41089 }, pair = { PairResult.WrongCode("bad code") })
        assertEquals(PairResult.WrongCode("bad code"), r)
        assertEquals(1, finds)
    }
}
