// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.RemotePad

/** One of the Remote's four modes: its glyph, its name and what it's for. */
private data class Mode(val pad: RemotePad, val glyph: ImageVector, val label: Int, val line: Int)

private val MODES = listOf(
    Mode(RemotePad.DPAD, RemoteIcons.Gamepad, R.string.remote_dpad, R.string.remote_mode_dpad_line),
    Mode(RemotePad.TOUCHPAD, RemoteIcons.TouchApp, R.string.remote_touchpad, R.string.remote_mode_touchpad_line),
    Mode(RemotePad.POINTER, AccessIcons.Mouse, R.string.remote_pointer, R.string.remote_mode_pointer_line),
    Mode(RemotePad.CONTROLLER, AccessIcons.Controller, R.string.remote_controller, R.string.remote_mode_controller_line),
)

/**
 * The mode button beside ⋮: the current mode's glyph, opening the menu of all four. [tip] shows
 * "Switch how you control your TV" under it once, ringed, and [onTipSeen] runs once it's tapped away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeButton(pad: RemotePad, pointer: Boolean, onPick: (RemotePad) -> Unit, tip: Boolean = false, onTipSeen: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    val tipState = rememberTooltipState(isPersistent = true)
    LaunchedEffect(tip) {
        if (tip) {
            // Returns once the tip is dismissed, so leaving the Remote with it up shows it again next time.
            tipState.show()
            onTipSeen()
        }
    }
    val mode = MODES.first { it.pad == pad }
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text(stringResource(R.string.remote_mode_tip)) } },
            state = tipState,
            enableUserInput = false,
        ) {
            val ring = if (tipState.isVisible) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier
            FilledTonalIconButton(onClick = { open = true }, modifier = ring) {
                Icon(mode.glyph, contentDescription = stringResource(R.string.remote_mode_button, stringResource(mode.label)))
            }
        }
        ModeMenu(open, pad, pointer, onPick = { open = false; if (it != pad) onPick(it) }, onDismiss = { open = false })
    }
}

/**
 * The four modes, each with its glyph, name and one line on what it's for, the current one ticked.
 * Pointer grays out, with the reason, on a TV that won't take a virtual mouse; the controller says it turns the phone.
 */
@Composable
private fun ModeMenu(open: Boolean, pad: RemotePad, pointer: Boolean, onPick: (RemotePad) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    DropdownMenu(open, onDismissRequest = onDismiss, shape = MaterialTheme.shapes.large) {
        for (m in MODES) {
            val current = m.pad == pad
            val allowed = m.pad != RemotePad.POINTER || pointer
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(m.label))
                        Text(stringResource(if (allowed) m.line else R.string.remote_pointer_refused), style = MaterialTheme.typography.bodySmall)
                    }
                },
                leadingIcon = { Icon(m.glyph, null) },
                // The current mode's tick; the controller's mark that the phone turns sideways.
                trailingIcon = {
                    if (current) Icon(Icons.Default.Check, null)
                    else if (m.pad == RemotePad.CONTROLLER) Icon(ScreenRotation, null)
                },
                enabled = allowed,
                modifier = Modifier.background(if (current) c.secondaryContainer else Color.Transparent).semantics { selected = current },
                onClick = { onPick(m.pad) },
            )
        }
    }
}

// Absolute commands only, as in RemoteIcons: Compose's path parser misplaces a relative move after a close.
private val ScreenRotation = ImageVector.Builder("ScreenRotation", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        PathParser().parsePathString(
            "M16.48 2.52C19.75 4.07 22.09 7.24 22.45 11L23.95 11C23.44 4.84 18.29 0 12 0L11.34 0.03L15.15 3.84L16.48 2.52Z" +
                "M10.23 1.75C9.64 1.16 8.69 1.16 8.11 1.75L1.75 8.11C1.16 8.7 1.16 9.65 1.75 10.23L13.77 22.25C14.36 22.84 15.31 22.84 15.89 22.25L22.25 15.89C22.84 15.3 22.84 14.35 22.25 13.77L10.23 1.75Z" +
                "M14.83 21.19L2.81 9.17L9.17 2.81L21.19 14.83L14.83 21.19Z" +
                "M7.52 21.48C4.25 19.94 1.91 16.76 1.55 13L0.05 13C0.56 19.16 5.71 24 12 24L12.66 23.97L8.85 20.16L7.52 21.48Z",
        ).toNodes(),
        pathFillType = PathFillType.NonZero,
        fill = SolidColor(Color.Black),
    )
    .build()
