// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

/**
 * The `enabled_accessibility_services` setting, for launchers that keep Home with their own
 * accessibility service. Which launchers those are is the app's `ACCESSIBILITY_LAUNCHERS`.
 */
object HomeServices {
    /** [list] is the `enabled_accessibility_services` setting: colon-separated, "null" when unset. */
    fun has(list: String?, service: String) = entries(list).any { full(it) == full(service) }

    fun with(list: String?, service: String) = if (has(list, service)) entries(list) else entries(list) + service

    fun without(list: String?, service: String) = entries(list).filterNot { full(it) == full(service) }

    private fun entries(list: String?) = list.orEmpty().trim().takeUnless { it == "null" }.orEmpty()
        .split(':').filter { it.isNotBlank() }

    /** `pkg/.Service` and `pkg/pkg.Service` name the same service. */
    private fun full(c: String) = c.substringAfter('/').let { cls ->
        c.substringBefore('/') + "/" + if (cls.startsWith('.')) c.substringBefore('/') + cls else cls
    }
}
