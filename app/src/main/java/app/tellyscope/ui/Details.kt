// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.ui.theme.SmallNumber

/**
 * Technical facts (addresses, ids, what the TV actually said) one tap away, under the plain
 * sentence that explains them. Closed by default: most people never need them.
 */
@Composable
fun Details(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.details_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (open) R.string.details_hide else R.string.details_show),

                Modifier.size(18.dp).rotate(if (open) 180f else 0f),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (open) rows.forEach { (k, v) -> KeyValue(k, v) }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = SmallNumber)
    }
}
