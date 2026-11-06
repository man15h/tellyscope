// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.test.runTest
import okio.Buffer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TweaksTest {

    @Test fun unsetKeysReadAsNull() = runTest {
        val sh = FakeShell(mapOf("settings get global private_dns_specifier" to ok("null\nhostname\n")))
        assertEquals(
            mapOf("private_dns_specifier" to null, "private_dns_mode" to "hostname"),
            Tweaks(sh).read(Tweak.PRIVATE_DNS),
        )
    }

    @Test fun providerIsWrittenBeforeTheMode() = runTest {
        val sh = FakeShell()
        assertTrue(Tweaks(sh).write(Tweak.PRIVATE_DNS, mapOf("private_dns_mode" to "hostname", "private_dns_specifier" to "dns.adguard-dns.com")))
        assertEquals(
            "settings put global private_dns_specifier 'dns.adguard-dns.com' && settings put global private_dns_mode 'hostname' 2>&1",
            sh.ran.single(),
        )
    }

    @Test fun writeDeletesWhatWasUnset() = runTest {
        val sh = FakeShell()
        assertTrue(Tweaks(sh).write(Tweak.PRIVATE_DNS, mapOf("private_dns_mode" to "opportunistic", "private_dns_specifier" to null)))
        assertEquals(
            listOf("settings delete global private_dns_specifier && settings put global private_dns_mode 'opportunistic' 2>&1"),
            sh.ran,
        )
    }

    @Test fun valuesStayInsideTheQuotes() = runTest {
        val sh = FakeShell()
        Tweaks(sh).write(Tweak.PRIVATE_DNS, mapOf("private_dns_specifier" to "x;reboot"))
        assertEquals("settings put global private_dns_specifier 'x;reboot' 2>&1", sh.ran.single())
    }

    @Test fun foreignKeysAreRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { Tweaks(FakeShell()).write(Tweak.ANIMATIONS, mapOf("adb_enabled" to "0")) }
    }

    @Test fun exceptionFromSettingsIsARefusal() = runTest {
        val sh = FakeShell(mapOf("settings put" to ok("java.lang.SecurityException: Permission denial")))
        assertFalse(Tweaks(sh).write(Tweak.ANIMATIONS, mapOf("window_animation_scale" to "0.5")))
    }

    @Test fun clearCachesReportsSpaceFreed() = runTest {
        var calls = 0
        val sh = object : AdbSession by FakeShell() {
            override suspend fun shell(command: String): ShellResult = when {
                command.startsWith("df") -> ok("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-0 1000 ${if (calls++ == 0) 900 else 600} 100 90% /data\n")
                else -> ok("")
            }
        }
        assertEquals(300, Tweaks(sh).clearCaches())
    }

    @Test fun clearCachesWithoutStorageStillRuns() = runTest {
        val sh = FakeShell()
        assertNull(Tweaks(sh).clearCaches())
        assertTrue("pm trim-caches 999G 2>&1" in sh.ran)
    }

    @Test fun clearCachesRefusedThrows() = runTest {
        val sh = FakeShell(mapOf("pm trim-caches" to ShellResult("Error: no", 1)))
        assertFailsWith<IOException> { Tweaks(sh).clearCaches() }
    }

    @Test fun screenshotIsPulledAndRemoved() = runTest {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val sh = FakeShell()
        sh.transfer.files["/data/local/tmp/tellyscope-shot.png"] = png
        val out = Buffer()
        Tweaks(sh).screenshot(out)
        assertContentEquals(png, out.readByteArray())
        assertEquals("rm -f /data/local/tmp/tellyscope-shot.png", sh.ran.last())
    }

    @Test fun failedScreencapThrows() = runTest {
        val sh = FakeShell(mapOf("screencap" to ShellResult("Error: no display", 1)))
        assertFailsWith<IOException> { Tweaks(sh).screenshot(Buffer()) }
    }

    @Test fun unknownHostEveryTryIsNoLookups() = runTest {
        val sh = FakeShell(mapOf("ping" to ShellResult("ping: unknown host connectivitycheck.gstatic.com", 2)))
        assertFalse(Tweaks(sh).resolves(wait = 0))
        assertEquals(3, sh.ran.size)
    }

    @Test fun resolvedButNoReplyStillResolves() = runTest {
        val sh = FakeShell(mapOf("ping" to ShellResult("1 packets transmitted, 0 received, 100% packet loss", 1)))
        assertTrue(Tweaks(sh).resolves(wait = 0))
    }

    /** One network's link properties as `dumpsys connectivity` prints them, cut down. */
    private fun lp(host: String, validated: Boolean) =
        "NetworkAgentInfo{ lp{{InterfaceName: wlan0 DnsAddresses: [ /192.0.2.1 ] UsePrivateDns: true PrivateDnsServerName: $host " +
            (if (validated) "ValidatedPrivateDnsAddresses: [94.140.14.14] " else "") + "Domains: null MTU: 0 }} }\n"

    @Test fun aValidatedProviderPasses() = runTest {
        val sh = FakeShell(mapOf("dumpsys connectivity" to ShellResult(lp("dns.adguard-dns.com", validated = true), 0)))
        assertEquals(true, Tweaks(sh).dnsValidated("dns.adguard-dns.com", wait = 0))
        assertEquals("dumpsys connectivity | grep -F 'PrivateDnsServerName: dns.adguard-dns.com '", sh.ran.single())
    }

    /** Named but never validated: strict mode is on and the provider doesn't answer. A cached lookup can't pass it. */
    @Test fun aProviderThatNeverValidatesFails() = runTest {
        val sh = FakeShell(mapOf("dumpsys connectivity" to ShellResult(lp("dns.typo.example", validated = false), 0), "ping" to ShellResult("1 received", 0)))
        assertEquals(false, Tweaks(sh).dnsValidated("dns.typo.example", tries = 3, wait = 0))
        assertEquals(3, sh.ran.size)
    }

    /** Validation can take a moment after strict mode applies: a later poll still passes. */
    @Test fun aProviderThatValidatesLaterPasses() = runTest {
        var polls = 0
        val sh = FakeShell().apply { answer = { ShellResult(lp("dns.adguard-dns.com", validated = ++polls == 3), 0) } }
        assertEquals(true, Tweaks(sh).dnsValidated("dns.adguard-dns.com", wait = 0))
        assertEquals(3, polls)
    }

    @Test fun aProviderTheTvNeverNamesIsUnknown() = runTest {
        assertNull(Tweaks(FakeShell()).dnsValidated("dns.adguard-dns.com", tries = 2, wait = 0))
    }

    @Test fun hostnames() {
        assertTrue(Tweaks.isHostname("dns.adguard-dns.com"))
        assertFalse(Tweaks.isHostname("94.140.14.14"))
        assertFalse(Tweaks.isHostname("dns adguard.com"))
        assertFalse(Tweaks.isHostname("-bad.com"))
    }
}
