// SPDX-License-Identifier: GPL-3.0-only
// Tv, Stick, WifiOff, Logout and Accessibility are the Material Icons "tv", "settings_input_hdmi", "wifi_off", "logout" and
// "accessibility_new" (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.DeviceKind

/** Generic device shapes in the Material style; no maker's logo or artwork. */
object DeviceIcons {
    private const val TV =
        "M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z"
    private const val STICK =
        "M18 7V4c0-1.1-.9-2-2-2H8c-1.1 0-2 .9-2 2v3H5v6l3 6v3h8v-3l3-6V7h-1zM8 4h8v3h-2V5h-1v2h-2V5h-1v2H8V4z"

    /** A box with a lens on the right and two feet; Material has no projector icon. */
    private const val PROJECTOR =
        "M4 6h16c1.1 0 2 .9 2 2v8c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V8c0-1.1.9-2 2-2z" +
            "M16 8.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z" +
            "M16 10.5a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3z" +
            "M5 9h5v1.5H5z M5 12h5v1.5H5z M4 18h3v1.5H4z M17 18h3v1.5h-3z"

    /** A stick with its plug down and a small remote beside it, for Fire TV; Amazon's logo is theirs. */
    private const val FIRE_TV =
        "M5 2h4c.55 0 1 .45 1 1v12c0 .55-.45 1-1 1H5c-.55 0-1-.45-1-1V3c0-.55.45-1 1-1z" +
            "M5.5 16h3v4h-3z" +
            "M15 6h4c.83 0 1.5.67 1.5 1.5v13c0 .83-.67 1.5-1.5 1.5h-4c-.83 0-1.5-.67-1.5-1.5v-13c0-.83.67-1.5 1.5-1.5z" +
            "M17 8.25a1.75 1.75 0 1 0 0 3.5a1.75 1.75 0 1 0 0-3.5z" +
            "M15.5 14h3v1.5h-3z M15.5 17h3v1.5h-3z"

    private const val WIFI_OFF =
        "M22.99 9C19.15 5.16 13.8 3.76 8.84 4.78l2.52 2.52c3.47-.17 6.99 1.05 9.63 3.7l2-2zm-4 4c-1.29-1.29-2.84-2.2-4.49-2.73l3.52 3.52.97-.79z" +
            "M2 3.05L5.07 6.1C3.6 6.82 2.22 7.78 1 9l1.99 2c1.24-1.24 2.67-2.16 4.2-2.77l2.24 2.24C7.81 10.89 6.27 11.73 5 13v.01L6.99 15" +
            "c1.36-1.36 3.14-2.04 4.92-2.06L18.98 20l1.27-1.26L3.29 1.79 2 3.05zM9 17l3 3 3-3c-1.65-1.66-4.34-1.66-6 0z"

    private const val LOGOUT =
        "M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z"

    private const val ACCESSIBILITY =
        "M20.5 6c-2.61.7-5.67 1-8.5 1s-5.89-.3-8.5-1L3 8c1.86.5 4 .83 6 1v13h2v-6h2v6h2V9c2-.17 4.14-.5 6-1l-.5-2zM12 6c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2z"

    val Tv = icon("Tv", TV)
    val FireTv = icon("FireTv", FIRE_TV)
    val Stick = icon("Stick", STICK)
    val Projector = icon("Projector", PROJECTOR)
    val WifiOff = icon("WifiOff", WIFI_OFF)
    val Logout = icon("Logout", LOGOUT)
    val Accessibility = icon("Accessibility", ACCESSIBILITY)

    fun of(kind: DeviceKind) = when (kind) {
        DeviceKind.Tv -> Tv
        DeviceKind.Projector -> Projector
        DeviceKind.FireTv -> FireTv
        DeviceKind.Stick -> Stick
    }

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        .build()
}

/** Screen-reader name for the icon. */
@Composable
fun DeviceKind.word() = stringResource(
    when (this) {
        DeviceKind.Tv -> R.string.kind_tv
        DeviceKind.Projector -> R.string.kind_projector
        DeviceKind.FireTv -> R.string.kind_fire_tv
        DeviceKind.Stick -> R.string.kind_stick
    },
)

