// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.adb.Tweak
import app.tellyscope.adb.Tweaks
import app.tellyscope.data.TvSettings
import app.tellyscope.data.resolve

/**
 * Whole-TV settings and housekeeping. The two switches land in History with Undo; clearing
 * caches says up front what it keeps; a screenshot asks where to save it first.
 */
@Composable
fun TweaksBlock(ui: DeviceUi, actions: DeviceActions) {
    val tweaks = ui.tweaks
    var askDns by rememberSaveable { mutableStateOf(false) }
    val dnsOn = tweaks?.dnsHost != null
    if (ui.busy in TWEAK_BUSY) LinearProgressIndicator(Modifier.fillMaxWidth())
    val dnsOk = tweaks?.dnsSupported != false
    SwitchRow(
        Copy.DNS_TITLE.resolve(),
        if (dnsOk) Copy.dnsState(tweaks?.dnsHost).resolve() else Copy.DNS_TOO_OLD.resolve(),
        dnsOn,
        enabled = tweaks != null && dnsOk && ui.busy == null,
    ) { on ->
        if (on) askDns = true else actions.setDns(null)
    }
    val fast = tweaks?.animationScale == TvSettings.FAST
    SwitchRow(Copy.ANIMATIONS_TITLE.resolve(), Copy.animationsState(tweaks?.animationScale).resolve(), fast, enabled = tweaks != null && ui.busy == null) {
        actions.setFastAnimations(it)
    }
    ButtonRow(Copy.CACHES_TITLE.resolve(), Copy.CACHES_TEXT.resolve(), Copy.CACHES_BUTTON.resolve(), enabled = ui.busy == null, actions.clearCaches)
    ButtonRow(Copy.SCREENSHOT_TITLE.resolve(), Copy.SCREENSHOT_TEXT.resolve(), Copy.SCREENSHOT_BUTTON.resolve(), enabled = ui.busy == null, actions.screenshot)
    if (askDns) DnsDialog(onDismiss = { askDns = false }) { host -> askDns = false; actions.setDns(host) }
}

@Composable
private fun SwitchRow(title: String, state: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, not just the thumb: a bigger target, read as one switch by TalkBack.
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ButtonRow(title: String, text: String, button: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onClick, enabled = enabled) { Text(button) }
    }
}

/** AdGuard is filled in; any other DoT hostname works too. */
@Composable
private fun DnsDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var host by rememberSaveable { mutableStateOf(TvSettings.ADGUARD) }
    val valid = Tweaks.isHostname(host.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Copy.DNS_DIALOG.resolve()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Copy.DNS_TEXT.resolve())
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(Copy.DNS_FIELD.resolve()) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { if (!valid) Text(Copy.DNS_BAD.resolve()) },
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(host.trim()) }, enabled = valid) { Text(Copy.DNS_CONFIRM.resolve()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screen_cancel)) } },
    )
}

/** [DeviceUi.busy] values that belong to this block. */
private val TWEAK_BUSY = Tweak.entries.map { it.name } + listOf(CLEARING, SCREENSHOT)
