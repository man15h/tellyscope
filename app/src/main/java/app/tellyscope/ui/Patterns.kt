// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// One style per pattern, used on every screen: section headers, cards, status heroes, bottom
// bars, destructive confirms and search.

/** The side margin of every screen: M3's compact margin. */
internal val PageMargin = 16.dp

/**
 * A section's header: titleSmall in primary, sentence case, 16 dp above and 8 dp below. [padded] is
 * false inside a parent whose spacedBy already makes the gaps, so the two don't add up.
 */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier, padded: Boolean = true) {
    Text(text, if (padded) modifier.padding(top = 16.dp, bottom = 8.dp) else modifier, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/**
 * The one card: surfaceContainer with large corners. A status card passes its role colour as
 * [color] and keeps the shape; the content colour follows it.
 */
@Composable
internal fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = color, contentColor = contentColorFor(color))
    if (onClick == null) Card(modifier, shape = MaterialTheme.shapes.large, colors = colors, content = content)
    else Card(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, colors = colors, content = content)
}

/** What a [StatusHero] stands for, which sets its colours. */
internal enum class HeroTone { Done, Failed, Waiting, Neutral }

/**
 * The status hero of a page: a 96 dp Cookie9Sided with a 44 dp icon. [HeroTone.Waiting] is the same
 * cookie with a loading indicator inside, so the result swaps in place and nothing moves; given an
 * [icon], it shows that instead, for a page whose spinner is already further down.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusHero(tone: HeroTone, icon: ImageVector?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val c = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        HeroTone.Done -> c.primaryContainer to c.onPrimaryContainer
        HeroTone.Failed -> c.errorContainer to c.onErrorContainer
        HeroTone.Waiting -> c.secondaryContainer to c.onSecondaryContainer
        HeroTone.Neutral -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
    Box(modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(container), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, contentDescription, Modifier.size(44.dp), tint = content)
        else if (tone == HeroTone.Waiting) LoadingIndicator(Modifier.size(48.dp), color = content)
    }
}

/**
 * Every bottom action bar: surfaceContainerHigh, so a card scrolled under it keeps its edge, with
 * no elevation or divider. An optional [note] goes above the buttons, which sit at the end, 8 dp
 * apart, the main one last.
 */
@Composable
internal fun ActionBar(modifier: Modifier = Modifier, note: String? = null, buttons: @Composable RowScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.navigationBarsPadding().padding(PageMargin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons)
        }
    }
}

/** A row of buttons inside content: end-aligned, 8 dp apart, the main one last. */
@Composable
internal fun ButtonRow(modifier: Modifier = Modifier, buttons: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons)
}

/** "Try again" inside content: tonal, with the refresh icon. In a bar it's that bar's filled main action instead. */
@Composable
internal fun TryAgainButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
        Text(label, Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

/**
 * A confirm for something that can't be taken back: the error icon on top, a centred title,
 * [dismiss] as a text button on the left and [confirm] as a filled error button on the right.
 */
@Composable
internal fun DestructiveDialog(
    icon: ImageVector,
    title: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    text: @Composable (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null, tint = c.error) },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = text,
        confirmButton = {
            Button(onClick = onConfirm, enabled = confirmEnabled, colors = ButtonDefaults.buttonColors(containerColor = c.error, contentColor = c.onError)) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

/**
 * Search: the M3 search bar's input field, 56 dp, a full pill in surfaceContainerHigh, with the
 * magnifier at the start and a clear button once something is typed. It never expands, because the
 * results are the list underneath. Where the field takes the place of the top bar, [onClose] puts
 * ← at the start instead, so there is still a way out on screen. The Search key hides the keyboard
 * unless [onSearch] does something else.
 */
@Composable
internal fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, clear: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null, onClose: (() -> Unit)? = null, close: String? = null) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = close) }
            else Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = clear) } }
        },
        singleLine = true,
        shape = SearchBarDefaults.inputFieldShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (onSearch != null) onSearch() else keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}
