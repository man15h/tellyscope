// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import java.io.File
import java.time.YearMonth

/** What Tellyscope remembers about a TV between launches. */
data class SavedDevice(
    val host: String,
    val port: Int,
    val kind: ScanKind,
    val name: String?,
    val model: String?,
    val lastSeen: Long?,
    /** [historyKey] once the TV has answered; null before that. */
    val historyKey: String? = null,
    /** Cleared only by Forget, which drops the whole line. */
    val lastKnown: LastKnown? = null,
    /** Epoch millis the TV took this phone's key back; null while it hasn't, or once it lets it in again. */
    val loggedOutAt: Long? = null,
    /**
     * Wireless debugging only: the TV's `_adb-tls-connect` instance name (`adb-<serial>-…`), saved at
     * first connect, so its new port is looked up for this TV and no other.
     */
    val service: String? = null,
)

/**
 * Remembered TVs, one tab-separated line each. Plain text so it needs no library and a damaged
 * line costs one device, not the list. Writes go to a temp file and are renamed into place.
 */
class DeviceStore(private val file: File) {

    @Synchronized
    fun load(): List<SavedDevice> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(::parse)
    }

    @Synchronized
    fun save(devices: List<SavedDevice>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(devices.joinToString("") { format(it) + "\n" })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun format(d: SavedDevice) = listOf(
        VERSION, d.host, d.port.toString(), d.kind.name, clean(d.name), clean(d.model), d.lastSeen?.toString().orEmpty(),
        clean(d.historyKey),
        clean(d.lastKnown?.manufacturer), clean(d.lastKnown?.brand), clean(d.lastKnown?.android),
        d.lastKnown?.softwareMonth?.toString().orEmpty(),
        d.lastKnown?.storageUsedGb?.toString().orEmpty(), d.lastKnown?.storageTotalGb?.toString().orEmpty(),
        d.loggedOutAt?.toString().orEmpty(), clean(d.service),
    ).joinToString("\t")

    private fun parse(line: String): SavedDevice? {
        val f = line.split('\t')
        // 7 fields: written before the history key existed; 8 before the last-known facts; 14 before logout;
        // 15 before the wireless service name.
        if (f.size !in setOf(7, 8, 14, 15, 16) || f[0] != VERSION) return null
        val lastKnown = if (f.size >= 14 && f.subList(8, 14).any { it.isNotEmpty() }) {
            LastKnown(
                manufacturer = f[8].ifEmpty { null },
                brand = f[9].ifEmpty { null },
                android = f[10].ifEmpty { null },
                softwareMonth = f[11].ifEmpty { null }?.let { runCatching { YearMonth.parse(it) }.getOrNull() ?: return null },
                storageUsedGb = f[12].ifEmpty { null }?.let { it.toDoubleOrNull() ?: return null },
                storageTotalGb = f[13].ifEmpty { null }?.let { it.toDoubleOrNull() ?: return null },
            )
        } else null
        return SavedDevice(
            host = f[1].ifEmpty { return null },
            port = f[2].toIntOrNull() ?: return null,
            kind = ScanKind.entries.firstOrNull { it.name == f[3] } ?: return null,
            name = f[4].ifEmpty { null },
            model = f[5].ifEmpty { null },
            lastSeen = f[6].toLongOrNull(),
            historyKey = f.getOrNull(7)?.ifEmpty { null },
            lastKnown = lastKnown,
            loggedOutAt = f.getOrNull(14)?.toLongOrNull(),
            service = f.getOrNull(15)?.ifEmpty { null },
        )
    }

    /** Names come from the network (mDNS, reverse DNS): keep them on one field. */
    private fun clean(s: String?) = s.orEmpty().replace(Regex("[\\t\\r\\n]"), " ")

    private companion object {
        const val VERSION = "v1"
    }
}
