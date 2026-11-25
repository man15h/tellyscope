// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SideloadTest {
    private val dir = createTempDirectory("sideload").toFile()
    private fun apk(name: String, size: Int = 20_000) = File(dir, name).apply { writeBytes(ByteArray(size)) }

    private val dumpsys = """
        Packages:
          Package [com.example.player] (3c1a2b):
            userId=10123
            versionCode=42 minSdk=24 targetSdk=34
            versionName=4.2.0
          Package [com.example.other] (77aa):
            versionCode=7 minSdk=21 targetSdk=33
            versionName=0.7
        Hidden system packages:
          Package [com.example.player] (1f00):
            versionCode=1 minSdk=24 targetSdk=34
            versionName=1.0-factory
    """.trimIndent()

    private val player = ApkInfo("com.example.player", 42, "4.2.0")

    @Test fun readsTheActiveVersionNotTheFactoryCopy() {
        assertEquals(InstalledVersion(42, "4.2.0"), Sideload.parseInstalled(dumpsys, "com.example.player"))
        assertEquals(InstalledVersion(7, "0.7"), Sideload.parseInstalled(dumpsys, "com.example.other"))
        assertNull(Sideload.parseInstalled(dumpsys, "com.example.absent"))
        assertNull(Sideload.parseInstalled("Unable to find package: com.example.absent\n", "com.example.absent"))
    }

    @Test fun kindComparesVersionCodes() {
        val on = InstalledVersion(42, "4.2.0")
        assertEquals(InstallKind.NEW, installKind(player, null))
        assertEquals(InstallKind.UPDATE, installKind(player.copy(versionCode = 43), on))
        assertEquals(InstallKind.REINSTALL, installKind(player, on))
        assertEquals(InstallKind.DOWNGRADE, installKind(player.copy(versionCode = 41), on))
    }

    @Test fun singleApkStreamsWithProgressAndReplaces() = runTest {
        val sh = FakeShell()
        val seen = mutableListOf<Progress>()
        val r = Sideload(sh).install(listOf(apk("app.apk")), player, allowDowngrade = false) { seen += it }
        assertEquals(InstallResult.Done, r)
        assertEquals(listOf("stream:20000") to listOf("-r"), sh.transfer.installs.single())
        assertEquals(1f, seen.last().fraction)
        assertTrue(seen.size in 2..101, "throttled to about one update per percent, got ${seen.size}")
    }

    @Test fun splitApkGoesThroughInstallMultiple() = runTest {
        val sh = FakeShell()
        val parts = listOf(apk("base.apk"), apk("split_config.arm64_v8a.apk", 500))
        val seen = mutableListOf<Progress>()
        assertEquals(InstallResult.Done, Sideload(sh).install(parts, player, allowDowngrade = false) { seen += it })
        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk") to listOf("-r"), sh.transfer.installs.single())
        assertNull(seen.single().fraction)
    }

    @Test fun downgradeIsRefusedBeforeAnythingIsSent() = runTest {
        val sh = FakeShell(mapOf("dumpsys package" to ok(dumpsys)))
        val r = Sideload(sh).install(listOf(apk("old.apk")), player.copy(versionCode = 40), allowDowngrade = false)
        assertEquals(InstallRefusal.DOWNGRADE, (r as InstallResult.Refused).reason)
        assertTrue(sh.transfer.installs.isEmpty())
    }

    @Test fun downgradeOnlyWhenAskedAddsDashD() = runTest {
        val sh = FakeShell(mapOf("dumpsys package" to ok(dumpsys)))
        Sideload(sh).install(listOf(apk("old.apk")), player.copy(versionCode = 40), allowDowngrade = true)
        assertEquals(listOf("-r", "-d"), sh.transfer.installs.single().second)
    }

    @Test fun allowDowngradeDoesNothingForAnUpdate() = runTest {
        val sh = FakeShell(mapOf("dumpsys package" to ok(dumpsys)))
        Sideload(sh).install(listOf(apk("new.apk")), player.copy(versionCode = 50), allowDowngrade = true)
        assertEquals(listOf("-r"), sh.transfer.installs.single().second)
    }

    @Test fun tvRefusalBecomesAReasonNotACrash() = runTest {
        val sh = FakeShell()
        sh.transfer.installFails = IllegalStateException(
            "Install failed: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.example.player signatures do not match previously installed version; ignoring!]",
        )
        val r = Sideload(sh).install(listOf(apk("app.apk")), player, allowDowngrade = false) as InstallResult.Refused
        assertEquals(InstallRefusal.SIGNATURE, r.reason)
        assertTrue(r.detail.startsWith("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
    }

    @Test fun splitSessionFailureIsMappedToo() = runTest {
        val sh = FakeShell()
        sh.transfer.installFails = IOException("Install failed: Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]")
        val r = Sideload(sh).install(listOf(apk("base.apk"), apk("split.apk")), player, allowDowngrade = false)
        assertEquals(InstallRefusal.NO_SPACE, (r as InstallResult.Refused).reason)
    }

    private val streamRefused = IOException("ADB stream closed before OKAY for localId: -3b33f1f")
    private val tmp = "'${Sideload.TMP_APK}'"

    @Test fun refusedStreamFallsBackToCopyThenPmInstall() = runTest {
        val sh = FakeShell(mapOf("pm install" to ok("Success\n")))
        sh.transfer.installFails = streamRefused
        val seen = mutableListOf<Progress>()
        val r = Sideload(sh).install(listOf(apk("app.apk")), player, allowDowngrade = false) { seen += it }
        assertEquals(InstallResult.Done, r)
        assertEquals(20_000, sh.transfer.files.getValue(Sideload.TMP_APK).size)
        assertEquals(listOf("pm install -r $tmp", "rm -f $tmp"), sh.ran.filter { it.startsWith("pm ") || it.startsWith("rm ") })
        assertEquals(1f, seen.last().fraction)
    }

    @Test fun fallbackKeepsDashDAndDeletesTheCopyWhenPmRefuses() = runTest {
        val sh = FakeShell(mapOf("dumpsys package" to ok(dumpsys), "pm install" to ok("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]\n")))
        sh.transfer.installFails = streamRefused
        val r = Sideload(sh).install(listOf(apk("old.apk")), player.copy(versionCode = 40), allowDowngrade = true) as InstallResult.Refused
        assertEquals(InstallRefusal.SIGNATURE, r.reason)
        assertEquals(listOf("pm install -r -d $tmp", "rm -f $tmp"), sh.ran.filter { it.startsWith("pm ") || it.startsWith("rm ") })
    }

    @Test fun splitApkWithRefusedStreamIsNotRetried() = runTest {
        val sh = FakeShell()
        sh.transfer.installFails = streamRefused
        val r = Sideload(sh).install(listOf(apk("base.apk"), apk("split.apk")), player, allowDowngrade = false)
        assertEquals(InstallRefusal.UNKNOWN, (r as InstallResult.Refused).reason)
        assertTrue(sh.transfer.files.isEmpty())
        assertTrue(sh.ran.none { it.startsWith("pm ") })
    }

    @Test fun refusalCodes() {
        val cases = mapOf(
            "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]" to InstallRefusal.DOWNGRADE,
            "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]" to InstallRefusal.NO_SPACE,
            "Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]" to InstallRefusal.TV_SAID_NO,
            "Failure [INSTALL_FAILED_VERIFICATION_FAILURE]" to InstallRefusal.TV_SAID_NO,
            "Failure [INSTALL_FAILED_OLDER_SDK: Requires newer sdk version #34 (current version is #30)]" to InstallRefusal.TV_TOO_OLD,
            "Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]" to InstallRefusal.WRONG_CPU,
            "Failure [INSTALL_FAILED_DEPRECATED_SDK_VERSION: App package must target at least SDK version 23]" to InstallRefusal.APP_TOO_OLD,
            "Failure [INSTALL_FAILED_MISSING_FEATURE: Package requires unavailable feature android.hardware.touchscreen]" to InstallRefusal.MISSING_FEATURE,
            "Failure [INSTALL_FAILED_MISSING_SPLIT: Missing split for com.example.player]" to InstallRefusal.MISSING_PARTS,
            "Failure [INSTALL_FAILED_TEST_ONLY]" to InstallRefusal.TEST_ONLY,
            "Failure [INSTALL_FAILED_DUPLICATE_PERMISSION: ...]" to InstallRefusal.CONFLICT,
            "Failure [INSTALL_PARSE_FAILED_NOT_APK: Failed to parse /data/app/vmdl.tmp/base.apk]" to InstallRefusal.BROKEN,
            "Failure [INSTALL_FAILED_INVALID_APK]" to InstallRefusal.BROKEN,
            "Failure [INSTALL_FAILED_SOMETHING_NEW]" to InstallRefusal.UNKNOWN,
            "Error: java.io.IOException: Requested internal only, but not enough space" to InstallRefusal.NO_SPACE,
            "Kadb is closed" to InstallRefusal.UNKNOWN,
        )
        cases.forEach { (out, want) -> assertEquals(want, Sideload.refusal(out), out) }
    }
}
