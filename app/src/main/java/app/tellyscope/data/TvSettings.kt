// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.R
import app.tellyscope.adb.Tweak

/** What each TV tweak writes, and how History names it afterwards. */
object TvSettings {
    /** AdGuard's public ad-blocking resolver: what the ad-blocking switch offers first. */
    const val ADGUARD = "dns.adguard-dns.com"
    const val FAST = "0.5"
    /** History's name for a cache clear: "Cleared app caches". */
    val CACHES: UiText = UiText(R.string.data_history_app_caches)
    /** Android 9, the first with Private DNS. */
    const val DNS_SDK = 28

    /** Off goes back to Automatic, Android's default, rather than turning encrypted DNS off. */
    fun dns(host: String?): Map<String, String?> =
        if (host == null) mapOf("private_dns_mode" to "opportunistic")
        else mapOf("private_dns_specifier" to host, "private_dns_mode" to "hostname")

    fun animations(fast: Boolean): Map<String, String?> = Tweak.ANIMATIONS.keys.associateWith { if (fast) FAST else "1.0" }

    fun tweaks(dns: Map<String, String?>, animations: Map<String, String?>, sdk: Int?) = TvTweaks(
        dnsHost = dns["private_dns_specifier"]?.takeIf { dns["private_dns_mode"] == "hostname" },
        animationScale = animations["window_animation_scale"],
        // A TV that doesn't say is let through: the lookup check after the change still guards it.
        dnsSupported = sdk == null || sdk >= DNS_SDK,
    )

    /** History's line for a change that left the TV at [values]. */
    fun label(tweak: Tweak, values: Map<String, String?>): UiText = when (tweak) {
        Tweak.PRIVATE_DNS -> when (values["private_dns_mode"]) {
            "hostname" -> UiText(R.string.data_history_dns_host, values["private_dns_specifier"].toString())
            "off" -> UiText(R.string.data_history_dns_off)
            else -> UiText(R.string.data_history_dns_automatic)
        }
        Tweak.ANIMATIONS -> when (val v = values["window_animation_scale"]?.toFloatOrNull()) {
            null, 1f -> UiText(R.string.data_history_animations_normal)
            0f -> UiText(R.string.data_history_animations_off)
            else -> UiText(R.string.data_history_animations_scale, v.toString().removeSuffix(".0"))
        }
    }

    val DNS_TOO_OLD: UiText = UiText(R.string.data_dns_too_old)

    fun dnsUnreachable(host: String?) = UiText(R.string.data_dns_unreachable, host.toString())

    fun dnsStuck(host: String?) = UiText(R.string.data_dns_stuck, host.toString())
}
