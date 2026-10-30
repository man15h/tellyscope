// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.adb.ConnectionRequest
import app.tellyscope.data.resolve

/**
 * The TV's "Allow debugging?" prompt, answered from the phone, as a dialog like the TV's own. The
 * fingerprint is always shown, "Always allow" starts unticked for every new request, and Deny is
 * the safe choice. Dismissing answers nothing: the TV keeps asking, and this request stays hidden.
 * Under Fire OS's remote alert ([ConnectionRequest.behindAlert]) it waits instead, with no way to answer.
 */
@Composable
fun RequestDialog(
    request: ConnectionRequest,
    busy: Boolean,
    answer: (allow: Boolean, always: Boolean) -> Unit,
    dismiss: () -> Unit,
    closeAlert: () -> Unit = {},
) {
    if (request.behindAlert) return BehindAlertDialog(busy, closeAlert, dismiss)
    var always by rememberSaveable(request.fingerprint) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        icon = { Icon(AccessIcons.Shield, contentDescription = null) },
        title = { Text(Copy.REQUEST_TITLE.resolve()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Copy.REQUEST_TEXT.resolve())
                // Eight pairs a line, so a pair is never split when comparing it with the other device.
                Text(
                    request.fingerprint.split(':').chunked(8).joinToString("\n") { it.joinToString(":") },
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                )
                // No tick-box on the TV's prompt, none here: a tick that can't be applied would mislead.
                if (request.canAlwaysAllow) Row(
                    Modifier.fillMaxWidth().toggleable(always, enabled = !busy, role = Role.Checkbox) { always = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = always, onCheckedChange = null, enabled = !busy)
                    Text(Copy.REQUEST_ALWAYS.resolve(), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = { answer(true, always && request.canAlwaysAllow) }, enabled = !busy) { Text(Copy.REQUEST_ALLOW.resolve()) }
        },
        dismissButton = { TextButton(onClick = { answer(false, false) }, enabled = !busy) { Text(Copy.REQUEST_DENY.resolve()) } },
    )
}

/**
 * The same dialog while the prompt is under Fire OS's alert: no fingerprint, so no Allow and no Deny.
 * Press OK on the TV closes the alert with one Back, for when the remote it complains about isn't there.
 * Close answers nothing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BehindAlertDialog(busy: Boolean, closeAlert: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        icon = { Icon(AccessIcons.Shield, contentDescription = null) },
        title = { Text(Copy.REQUEST_TITLE.resolve()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Copy.REQUEST_BEHIND_ALERT.resolve())
                Text(Copy.REQUEST_THEN.resolve())
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(Copy.REQUEST_WAITING.resolve(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { FilledTonalButton(onClick = closeAlert, enabled = !busy) { Text(Copy.PRESS_OK.resolve()) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(R.string.request_close)) } },
    )
}
