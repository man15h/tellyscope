// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtectedTest {
    @Test fun coreRuleWithSpareProviders() {
        assertEquals(ProtectedReason.SYSTEM, Protected.reason("com.google.android.permissioncontroller"))
        assertEquals(ProtectedReason.SYSTEM, Protected.reason("com.android.providers.tv"))
        assertEquals(ProtectedReason.SYSTEM, Protected.reason("com.google.android.overlay.modules.ext.services"))
        assertNull(Protected.reason("com.android.providers.calendar"))
    }

    @Test fun vendorSettingsAndTheMediaModule() {
        assertEquals(ProtectedReason.SETTINGS, Protected.reason("com.amazon.tv.settings.v2"))
        assertEquals(ProtectedReason.SETTINGS, Protected.reason("org.droidtv.settings"))
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
}
