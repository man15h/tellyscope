// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            Modifier.fillMaxSize().padding(pad).padding(horizontal = PageMargin, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusHero(HeroTone.Neutral, Icons.Default.Search)
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
                ButtonRow {
                    if (canAsk) TextButton(onClick = onAllow) { Text(stringResource(R.string.permission_ask_again)) }
                    Button(onClick = onSettings) { Text(stringResource(R.string.permission_open_settings)) }
                }
            } else {
                ButtonRow { Button(onClick = onAllow) { Text(stringResource(R.string.screen_continue)) } }
            }
        }
    }
}
