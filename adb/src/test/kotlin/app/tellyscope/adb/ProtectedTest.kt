// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtectedTest {
    @Test fun aLauncherTheUserInstalledIsProtectedOnlyWhileItIsHome() {
        val projectivyHome = setOf("com.spocky.projengmenu")
        assertNull(Protected.reason("me.efesser.flauncher", projectivyHome, userApp = true))
        assertEquals(ProtectedReason.HOME_SCREEN, Protected.reason("me.efesser.flauncher", setOf("me.efesser.flauncher"), userApp = true))
        // A built-in one keeps the name guard, Home or not.
        assertEquals(ProtectedReason.HOME_SCREEN, Protected.reason("com.google.android.tvlauncher", projectivyHome))
    }

    @Test fun coreRuleWithSpareProviders() {
        assertEquals(ProtectedReason.SYSTEM, Protected.reason("com.google.android.permissioncontroller"))
        assertEquals(ProtectedReason.SYSTEM, Protected.reason("com.android.providers.tv"))
        assertEquals(ProtectedReason.SYSTEM, Protected.reason("com.google.android.overlay.modules.ext.services"))
        assertNull(Protected.reason("com.android.providers.calendar"))
    }

    @Test fun vendorSettingsAndTheMediaModule() {
        assertEquals(ProtectedReason.SETTINGS, Protected.reason("com.amazon.tv.settings.v2"))
        assertEquals(ProtectedReason.SYSTEM, Protected.reason("com.google.android.providers.media.module"))
    }

    /** Package names from an Android TV 11 XGIMI projector. */
    @Test fun xgimiPackages() {
        assertEquals(ProtectedReason.TV_INPUTS_REMOTE, Protected.reason("com.xgimi.tvinput"))
        assertEquals(ProtectedReason.TV_INPUTS_REMOTE, Protected.reason("com.xgimi.bluetoothservice"))
        assertEquals(ProtectedReason.TV_INPUTS_REMOTE, Protected.reason("com.google.android.tv.remote.service"))
        assertEquals(ProtectedReason.KEYBOARD, Protected.reason("com.google.android.inputmethod.latin"))
        assertEquals(ProtectedReason.SETUP_WIZARD, Protected.reason("com.google.android.tungsten.setupwraith"))
        assertEquals(ProtectedReason.SETUP_WIZARD, Protected.reason("com.xgimi.overseas.xgimiatvbootwizard"))
        assertEquals(ProtectedReason.SYSTEM, Protected.reason("com.android.networkstack.tethering.inprocess"))
        assertEquals(ProtectedReason.SETTINGS, Protected.reason("com.android.tv.settings"))
        // Debloat targets stay allowed.
        assertNull(Protected.reason("com.google.android.katniss"))
        assertNull(Protected.reason("com.google.android.tvrecommendations"))
        assertNull(Protected.reason("com.amazon.amazonvideo.livingroom"))
    }

    @Test fun updaters() {
        for (pkg in listOf(
            "com.droidlogic.updater", "com.xiaomi.mitv.updateservice", "com.sony.dtv.systemupdate",
            "com.xgimi.upgrade", "com.coocaa.os.upgrade", "com.amazon.tv.forcedotaupdater.v2",
            "com.amazon.device.software.ota", "com.xgimi.ota.accessories", "com.vt.tv.usbota",
            "com.apps.authota", "com.htc.htcotaupdate", "com.nvidia.ota",
        )) assertEquals(ProtectedReason.UPDATES, Protected.reason(pkg), pkg)
        // "ota" inside another word is not an updater.
        assertNull(Protected.reason("com.tcl.bootadservice"))
        assertNull(Protected.reason("com.itwgame.mota.nearme.gamecenter"))
    }

    @Test fun wifiAndSharedLibraries() {
        for (pkg in listOf(
            "com.android.wifi.resources", "com.google.android.wifi.resources", "com.android.wifi.resources.xiaomi",
            "com.android.connectivity.resources", "com.google.android.connectivity.resources",
            "com.google.android.trichromelibrary", "com.google.android.trichromelibrary_602614532",
        )) assertEquals(ProtectedReason.SYSTEM, Protected.reason(pkg), pkg)
        // Wi-Fi extras the lists turn off stay allowed.
        assertNull(Protected.reason("com.sony.dtv.networkapp.wifidirect"))
        assertNull(Protected.reason("com.amazon.wifilocker"))
    }

    /** One maker's packages come from the list source, not the app. */
    @Test fun makerPackagesAreProtectedOnlyByTheList() {
        assertNull(Protected.reason("org.droidtv.eum"))
        assertNull(Protected.reason("org.droidtv.settings"))
        try {
            Protected.extra = mapOf("org.droidtv.eum" to ProtectedReason.TV_INPUTS_REMOTE, "org.droidtv.settings" to ProtectedReason.SETTINGS)
            assertEquals(ProtectedReason.TV_INPUTS_REMOTE, Protected.reason("org.droidtv.eum"))
            assertEquals(ProtectedReason.SETTINGS, Protected.reason("org.droidtv.settings"))
        } finally {
            Protected.extra = emptyMap()
        }
    }

    /** A fork's list can leave a built-in package out or give it another reason; neither takes the protection away. */
    @Test fun aListCannotTakeABuiltInProtectionAway() {
        assertEquals(ProtectedReason.SETTINGS, Protected.reason("com.android.settings"))
        try {
            Protected.extra = mapOf("com.android.settings" to ProtectedReason.UPDATES, "com.acme.extra" to ProtectedReason.SYSTEM)
            assertEquals(ProtectedReason.SETTINGS, Protected.reason("com.android.settings"))
            assertEquals(ProtectedReason.HOME_SCREEN, Protected.reason("com.google.android.tvlauncher"))
            assertEquals(ProtectedReason.PLAY_SERVICES, Protected.reason("com.google.android.gms"))
        } finally {
            Protected.extra = emptyMap()
        }
    }
}
