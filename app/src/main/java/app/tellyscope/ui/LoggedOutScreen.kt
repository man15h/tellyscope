// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.Device
import app.tellyscope.data.resolve

/** "Projector · XGIMI": the TV's name and maker, as the connect and logged-out pages head them. */
internal fun connectTitle(d: Device) = listOfNotNull(name(d), d.manufacturer ?: d.hit.maker).distinct().joinToString(" · ")

/**
 * A TV that had allowed this phone took the access back, so Tellyscope stopped asking. Laid out like
 * "No answer from the TV"; the same page whether it happened on screen or the app was opened later,
 * only the time line differs. Connect again leads to the Allow screen; the nickname is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggedOutScreen(device: Device, loggedOutAt: Long, now: Long, onConnect: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screen_back)) } },
            )
        },
        bottomBar = {
            ActionBar {
                TextButton(onClick = onBack) { Text(stringResource(R.string.logged_out_back_to_tvs)) }
                Button(onClick = onConnect) { Text(stringResource(R.string.logged_out_connect_again)) }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = PageMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(DeviceIcons.Tv, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(connectTitle(device), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusHero(HeroTone.Failed, DeviceIcons.Logout)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Copy.LOGGED_OUT.resolve(), style = MaterialTheme.typography.headlineMedium)
                Text(Copy.LOGGED_OUT_WHY.resolve(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Copy.loggedOutAt(loggedOutAt, now).resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(stringResource(R.string.logged_out_causes_title))
                Copy.LOGGED_OUT_CAUSES.forEach { Text(stringResource(R.string.logged_out_cause, it.resolve()), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
