// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.Snapshot
import app.tellyscope.data.UiText
import app.tellyscope.ui.Copy
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthTest {
    private fun snap(ram: Double = 0.5, storage: Double? = 0.5) = Snapshot(
        at = 0, cpuPercent = 10,
        ramUsedMb = (ram * 1000).toInt(), ramTotalMb = 1000,
        storageUsedGb = storage?.let { it * 10 }, storageTotalGb = storage?.let { 10.0 },
        uptimeSeconds = 60, screenOn = true, procs = emptyList(),
    )

    @Test fun fine() = assertEquals(Copy.Health(true, UiText(R.string.copy_health_fine)), Copy.health(snap(), listOf("YouTube")))

    @Test fun storageBeforeMemory() {
        assertEquals("Storage almost full", Copy.health(snap(ram = 0.95, storage = 0.95), emptyList()).text.text())
    }

    @Test fun thresholdIsNinetyPercent() {
        assertEquals(true, Copy.health(snap(ram = 0.89, storage = 0.89), emptyList()).ok)
        assertEquals(false, Copy.health(snap(storage = 0.9), emptyList()).ok)
        assertEquals(false, Copy.health(snap(ram = 0.9), emptyList()).ok)
    }

    @Test fun namesTheHeaviestOnlyWhenKnown() {
        assertEquals(
            "Memory almost full: YouTube and Netflix use the most",
            Copy.health(snap(ram = 0.95), listOf("YouTube", "Netflix", "Kodi")).text.text(),
        )
        assertEquals("Memory almost full", Copy.health(snap(ram = 0.95), emptyList()).text.text())
    }

    @Test fun missingStatsAreNotProblems() {
        assertEquals(true, Copy.health(snap(storage = null), emptyList()).ok)
    }
}
