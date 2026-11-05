// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.DeviceStore
import app.tellyscope.data.LastKnown
import app.tellyscope.data.SavedDevice
import app.tellyscope.data.ScanKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.YearMonth

class DeviceStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tv = SavedDevice("192.168.1.23", 5555, ScanKind.Classic, "xgimi-projector", "XGIMI Horizon Pro", 1_760_000_000_000)
    private val stick = SavedDevice("192.168.1.57", 37211, ScanKind.Wireless, null, null, null)

    @Test fun missingFileIsEmpty() {
        assertEquals(emptyList<SavedDevice>(), DeviceStore(tmp.root.resolve("devices.tsv")).load())
    }

    @Test fun survivesARestart() {
        val file = tmp.root.resolve("devices.tsv")
        DeviceStore(file).save(listOf(tv, stick))
        // A new store on the same file is what the next launch sees.
        assertEquals(listOf(tv, stick), DeviceStore(file).load())
    }

    @Test fun saveReplacesTheList() {
        val store = DeviceStore(tmp.root.resolve("devices.tsv"))
        store.save(listOf(tv, stick))
        store.save(listOf(stick))
        assertEquals(listOf(stick), store.load())
    }

    @Test fun networkNamesCannotBreakTheFormat() {
        val store = DeviceStore(tmp.root.resolve("devices.tsv"))
        store.save(listOf(tv.copy(name = "living\troom\nTV")))
        assertEquals(listOf(tv.copy(name = "living room TV")), store.load())
    }

    @Test fun damagedLinesCostOnlyThemselves() {
        val file = tmp.root.resolve("devices.tsv")
        DeviceStore(file).save(listOf(tv))
        file.appendText("v1\tbroken\n")
        file.appendText("v9\t192.168.1.88\t5555\tClassic\t\t\t\n")
        file.appendText("v1\t192.168.1.88\tnot-a-port\tClassic\t\t\t\n")
        assertEquals(listOf(tv), DeviceStore(file).load())
    }

    @Test fun keepsTheHistoryKey() {
        val file = tmp.root.resolve("devices.tsv")
        DeviceStore(file).save(listOf(tv.copy(historyKey = "sn:XK03H1234")))
        assertEquals(listOf(tv.copy(historyKey = "sn:XK03H1234")), DeviceStore(file).load())
    }

    @Test fun keepsTheLogout() {
        val file = tmp.root.resolve("devices.tsv")
        DeviceStore(file).save(listOf(tv.copy(loggedOutAt = 1_761_475_500_000), stick))
        assertEquals(listOf(tv.copy(loggedOutAt = 1_761_475_500_000), stick), DeviceStore(file).load())
    }

    @Test fun readsFilesFromBeforeTheLogout() {
        val file = tmp.root.resolve("devices.tsv")
        DeviceStore(file).save(listOf(tv))
        // The older format stopped at the last-known facts.
        file.writeText(file.readText().replace("\t\t\t\n", "\n"))
        assertEquals(14, file.readLines().single().split('\t').size)
        assertEquals(listOf(tv), DeviceStore(file).load())
    }

    @Test fun keepsTheWirelessService() {
        val file = tmp.root.resolve("devices.tsv")
        val named = stick.copy(service = "adb-QV7124ZK-3fRb2c")
        DeviceStore(file).save(listOf(tv, named))
        assertEquals(listOf(tv, named), DeviceStore(file).load())
    }

    @Test fun readsFilesFromBeforeTheService() {
        val file = tmp.root.resolve("devices.tsv")
        val out = tv.copy(loggedOutAt = 1_761_475_500_000)
        DeviceStore(file).save(listOf(out))
        // The older format stopped at the logout.
        file.writeText(file.readText().replace("\t\t\n", "\n"))
        assertEquals(15, file.readLines().single().split('\t').size)
        assertEquals(listOf(out), DeviceStore(file).load())
    }

    @Test fun keepsTheFireOsVersion() {
        val file = tmp.root.resolve("devices.tsv")
        val fire = stick.copy(lastKnown = LastKnown(manufacturer = "Amazon", android = "11", fireOs = "8.1.4.9"))
        DeviceStore(file).save(listOf(tv, fire))
        assertEquals(listOf(tv, fire), DeviceStore(file).load())
    }

    @Test fun readsFilesFromBeforeTheFireOsVersion() {
        val file = tmp.root.resolve("devices.tsv")
        val named = stick.copy(service = "adb-QV7124ZK-3fRb2c")
        DeviceStore(file).save(listOf(named))
        // The older format stopped at the service name.
        file.writeText(file.readText().replace("\t\n", "\n"))
        assertEquals(16, file.readLines().single().split('\t').size)
        assertEquals(listOf(named), DeviceStore(file).load())
    }

    @Test fun readsFilesFromBeforeTheHistoryKey() {
        val file = tmp.root.resolve("devices.tsv")
        DeviceStore(file).save(listOf(tv))
        // The older format had no eighth field at all.
        file.writeText(file.readText().replace("\t".repeat(10) + "\n", "\n"))
        assertEquals(7, file.readLines().single().split('\t').size)
        assertEquals(listOf(tv), DeviceStore(file).load())
    }

    @Test fun keepsTheLastKnownFacts() {
        val file = tmp.root.resolve("devices.tsv")
        val known = tv.copy(lastKnown = LastKnown("XGIMI", "XGIMI", "11", YearMonth.of(2023, 5), 9.4, 12.0))
        DeviceStore(file).save(listOf(known, stick))
        assertEquals(listOf(known, stick), DeviceStore(file).load())
    }

    @Test fun readsFilesFromBeforeTheLastKnownFacts() {
        val file = tmp.root.resolve("devices.tsv")
        val keyed = tv.copy(historyKey = "sn:XK03H1234")
        DeviceStore(file).save(listOf(keyed))
        // The older format stopped at the history key.
        file.writeText(file.readText().replace("\t".repeat(9) + "\n", "\n"))
        assertEquals(8, file.readLines().single().split('\t').size)
        assertEquals(listOf(keyed), DeviceStore(file).load())
    }

    @Test fun aDamagedLastKnownFactCostsOnlyThatTv() {
        val file = tmp.root.resolve("devices.tsv")
        DeviceStore(file).save(listOf(stick))
        file.appendText("v1\t192.168.1.88\t5555\tClassic\t\t\t\t\tXGIMI\t\t11\tMay 2023\t\t\n")
        file.appendText("v1\t192.168.1.89\t5555\tClassic\t\t\t\t\tXGIMI\t\t11\t\tlots\t\n")
        assertEquals(listOf(stick), DeviceStore(file).load())
    }
}
