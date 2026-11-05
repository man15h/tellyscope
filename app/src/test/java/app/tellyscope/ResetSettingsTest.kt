// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.AdbPipe
import app.tellyscope.adb.AdbSession
import app.tellyscope.adb.RemoteEntry
import app.tellyscope.adb.ShellResult
import app.tellyscope.adb.Tweak
import app.tellyscope.adb.Tweaks
import app.tellyscope.data.Action
import app.tellyscope.data.HistoryBook
import app.tellyscope.data.ResetRecord
import app.tellyscope.data.Restorable
import app.tellyscope.data.TvSettings
import app.tellyscope.data.UiTextException
import app.tellyscope.data.resetSetting
import app.tellyscope.data.undoResetSetting
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okio.Sink
import okio.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A TV's global settings, as `settings get/put/delete global` see them; [refuse] makes every write fail. */
private class SettingsTv(vararg set: Pair<String, String>, var refuse: Boolean = false) : AdbSession {
    val global = mutableMapOf(*set)
    var writes = 0

    override suspend fun shell(command: String): ShellResult {
        if (command.startsWith("settings get")) {
            return ShellResult(command.split("; ").joinToString("\n") { global[it.substringAfterLast(' ')] ?: "null" }, 0)
        }
        writes++
        if (refuse) return ShellResult("java.lang.SecurityException: denied", 255)
        command.removeSuffix(" 2>&1").split(" && ").forEach { step ->
            val (verb, key, value) = step.removePrefix("settings ").split(' ', limit = 4).let { Triple(it[0], it[2], it.getOrNull(3)) }
            if (verb == "delete") global -= key else global[key] = value!!.removeSurrounding("'")
        }
        return ShellResult("", 0)
    }

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

class ResetSettingsTest {
    private val adguard = arrayOf("private_dns_mode" to "hostname", "private_dns_specifier" to TvSettings.ADGUARD)
    private val fast = Tweak.ANIMATIONS.keys.map { it to TvSettings.FAST }.toTypedArray()

    @Test fun aChangedSettingGoesBackToItsDefaultAndIsRecorded() = runTest {
        val tv = SettingsTv(*adguard, *fast)
        val dns = resetSetting(Tweaks(tv), Tweak.PRIVATE_DNS)
        val animations = resetSetting(Tweaks(tv), Tweak.ANIMATIONS)
        // Deleted, not set to a guess at the default.
        assertTrue(tv.global.isEmpty())
        assertEquals(mapOf(*adguard), ResetRecord.setting(dns, Tweak.PRIVATE_DNS))
        assertEquals(mapOf(*fast), ResetRecord.setting(animations, Tweak.ANIMATIONS))
        assertEquals(2, ResetRecord.changes(dns.orEmpty() + animations.orEmpty()))
    }

    @Test fun aSettingAtItsDefaultIsLeftAloneAndNotCounted() = runTest {
        for (tv in listOf(SettingsTv(), SettingsTv("private_dns_mode" to "opportunistic", "window_animation_scale" to "1.0", "animator_duration_scale" to "1"))) {
            assertNull(resetSetting(Tweaks(tv), Tweak.PRIVATE_DNS))
            assertNull(resetSetting(Tweaks(tv), Tweak.ANIMATIONS))
            assertEquals(0, tv.writes)
        }
    }

    @Test fun offDefault() {
        assertTrue(TvSettings.offDefault(Tweak.PRIVATE_DNS, mapOf("private_dns_mode" to "off")))
        assertTrue(TvSettings.offDefault(Tweak.PRIVATE_DNS, mapOf(*adguard)))
        assertFalse(TvSettings.offDefault(Tweak.PRIVATE_DNS, mapOf("private_dns_mode" to null, "private_dns_specifier" to TvSettings.ADGUARD)))
        // One scale of three is enough.
        assertTrue(TvSettings.offDefault(Tweak.ANIMATIONS, mapOf("window_animation_scale" to null, "animator_duration_scale" to "0")))
        assertFalse(TvSettings.offDefault(Tweak.ANIMATIONS, mapOf("window_animation_scale" to "1.0", "transition_animation_scale" to null)))
    }

    @Test fun aRefusedResetThrowsAndChangesNothing() = runTest {
        val tv = SettingsTv(*fast, refuse = true)
        val thrown = runCatching { resetSetting(Tweaks(tv), Tweak.ANIMATIONS) }.exceptionOrNull()
        assertTrue(thrown is UiTextException)
        assertEquals(mapOf(*fast), tv.global.toMap())
    }

    @Test fun undoPutsBackExactlyWhatWasThere() = runTest {
        // Only the window scale was set: Undo sets it again and leaves the other two unset.
        val tv = SettingsTv(*adguard, "window_animation_scale" to "0.5")
        val previous = resetSetting(Tweaks(tv), Tweak.PRIVATE_DNS).orEmpty() + resetSetting(Tweaks(tv), Tweak.ANIMATIONS).orEmpty()
        assertTrue(tv.global.isEmpty())
        assertTrue(undoResetSetting(Tweaks(tv), Tweak.PRIVATE_DNS, previous))
        assertTrue(undoResetSetting(Tweaks(tv), Tweak.ANIMATIONS, previous))
        assertEquals(mapOf(*adguard, "window_animation_scale" to "0.5"), tv.global.toMap())
    }

    @Test fun undoOfAResetThatLeftASettingAloneDoesNothingToIt() = runTest {
        val tv = SettingsTv(*fast)
        val previous = mapOf(ResetRecord.ON to "com.example.junk")
        assertTrue(undoResetSetting(Tweaks(tv), Tweak.ANIMATIONS, previous))
        assertEquals(0, tv.writes)
    }

    @Test fun aRefusedUndoSaysSo() = runTest {
        val tv = SettingsTv(*fast)
        val previous = resetSetting(Tweaks(tv), Tweak.ANIMATIONS).orEmpty()
        tv.refuse = true
        assertFalse(undoResetSetting(Tweaks(tv), Tweak.ANIMATIONS, previous))
    }

    @Test fun restoreEverythingReadsWhatAResetPutBack() = runTest {
        val tv = SettingsTv(*adguard)
        val book = HistoryBook()
        book.record("tv", 0, Action.Reset, "reset", "Reset TV", Action.Reset, previous = resetSetting(Tweaks(tv), Tweak.PRIVATE_DNS))
        assertEquals(mapOf(Tweak.PRIVATE_DNS to mapOf(*adguard)), Restorable.of(book.entries("tv")).settings)
    }
}
