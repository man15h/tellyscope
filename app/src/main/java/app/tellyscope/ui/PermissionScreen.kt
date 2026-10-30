// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellyscope.R

/**
 * Shown before the first scan on Android 17+, where talking to anything on the
 * Wi-Fi needs the "Nearby devices" permission. After a refusal the screen says
 * why it matters and offers Settings, which works whether or not Android will
 * still show its own dialog; [canAsk] adds "Ask again" while it will.
 */
@Composable
fun PermissionScreen(denied: Boolean, canAsk: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(72.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Search, contentDescription = null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.permission_local_network_text),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (denied) {
                Text(
                    stringResource(R.string.permission_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSettings) { Text(stringResource(R.string.permission_open_settings)) }
                    if (canAsk) TextButton(onClick = onAllow) { Text(stringResource(R.string.permission_ask_again)) }
                }
            } else {
                Button(onClick = onAllow) { Text(stringResource(R.string.screen_continue)) }
            }
        }
    }
}
