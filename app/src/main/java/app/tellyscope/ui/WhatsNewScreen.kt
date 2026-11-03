// SPDX-License-Identifier: GPL-3.0-only
// The What's new icon is the Material Symbols "new_releases" (Apache-2.0), see NOTICE. The update card is in
// app/src/github (UpdateDialog.kt).

package app.tellyscope.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.Changelog
import app.tellyscope.data.Changelog.Change
import app.tellyscope.data.Changelog.Kind
import app.tellyscope.data.Changelog.Release
import app.tellyscope.data.Update
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The bundled CHANGELOG.md's released sections; empty if the asset is missing. */
@Composable
fun rememberChangelog(): List<Release> {
    val context = LocalContext.current
    return remember { runCatching { context.assets.open(Changelog.ASSET).bufferedReader().use { Changelog.parse(it.readText()) } }.getOrDefault(emptyList()) }
}

/**
 * Settings › What's new: every release in the bundled CHANGELOG.md, newest first, one card each.
 * Inside a card the changes keep the file's order, which is New, then Improved, then Fixed, so a
 * heading goes in wherever the kind changes. [installed] is the running versionName; its release
 * gets an Installed chip whether or not this is an alpha.
 *
 * Once the update check has found [available], an Update card opens its offer again, and the
 * [newer] sections from GitHub go on top with a Not installed chip. Null [newer] means they
 * couldn't be read: one grey line says so instead. The Play build has no update check, so
 * [available] stays null there and its card is an empty stub.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(
    releases: List<Release>,
    back: () -> Unit,
    installed: String = installedVersion(),
    available: Update? = null,
    newer: List<Release>? = emptyList(),
    onUpdate: () -> Unit = {},
) {
    val browser = LocalUriHandler.current
    val openPr = { pr: Int -> runCatching { browser.openUri(Changelog.pullUrl(pr)) }; Unit }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_whats_new)) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.whats_new_back)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (available != null) {
                if (newer == null) {
                    item(key = "failed") { NewerFailed() }
                } else {
                    item(key = "update") { UpdateCard(available, installed, onUpdate) }
                }
            }
            val ahead = if (available == null) emptyList() else newer.orEmpty().filter { n -> releases.none { it.version == n.version } }
            items(ahead + releases, key = { it.version }) { r ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VersionHeading(r, isInstalled = r.version == installed.substringBefore('-'), notInstalled = r in ahead)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        r.changes.forEachIndexed { i, c ->
                            val kindChanged = i == 0 || r.changes[i - 1].kind != c.kind
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                            if (kindChanged && c.kind != null) KindHeading(c.kind)
                            ChangeItem(c, openPr, topPadding = if (kindChanged && c.kind != null) 4.dp else 16.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionHeading(r: Release, isInstalled: Boolean, notInstalled: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(r.version, style = MaterialTheme.typography.titleLarge)
        r.date?.let {
            Spacer(Modifier.width(8.dp))
            Text(shortDate(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        if (isInstalled) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                stringResource(R.string.whats_new_installed),
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (notInstalled) Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Text(
                stringResource(R.string.whats_new_not_installed),
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "29 Oct 2026" from the heading's ISO date; the date as written if it isn't one. */
private fun shortDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }.getOrDefault(iso)

@Composable
private fun KindHeading(kind: Kind) {
    Text(
        stringResource(
            when (kind) {
                Kind.New -> R.string.whats_new_new
                Kind.Improved -> R.string.whats_new_improved
                Kind.Fixed -> R.string.whats_new_fixed
            },
        ).uppercase(),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * One change and its indented points. Its #PR opens the pull request on the main repo. The link's tap
 * target is 48 dp tall and runs to the end of its line, so it overlaps plain text but never another
 * link; it stays inside this item's 16 dp padding.
 */
@Composable
private fun ChangeItem(c: Change, openPr: (Int) -> Unit, topPadding: Dp) {
    val link = MaterialTheme.colorScheme.primary
    val text = remember(c, link) {
        buildAnnotatedString {
            append(c.text)
            if (c.pr != null) {
                append(' ')
                withStyle(SpanStyle(color = link)) { append("#${c.pr}") }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, onTextLayout = { layout = it })
            c.details.forEach { d ->
                Row(Modifier.padding(start = 8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(d, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val l = layout
        if (c.pr != null && l != null) {
            val start = text.length - "#${c.pr}".length
            val line = l.getLineForOffset(start)
            val left = l.getHorizontalPosition(start, usePrimaryDirection = true)
            val middle = (l.getLineTop(line) + l.getLineBottom(line)) / 2
            with(density) {
                val target = 48.dp.toPx()
                Box(
                    Modifier
                        .offset { IntOffset((16.dp.toPx() + left).roundToInt(), (topPadding.toPx() + middle - target / 2).roundToInt().coerceAtLeast(0)) }
                        .size(width = (l.size.width - left).toDp(), height = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "#${c.pr}") { openPr(c.pr) },
                )
            }
        }
    }
}

internal val NewReleases = symbol(
    "NewReleases",
    "m344-60-76-128-144-32 14-148-98-112 98-112-14-148 144-32 76-128 136 58 136-58 76 128 144 32-14 148 98 112-98 112 14 148-144 32-76 128-136-58-136 58Zm34-102 102-44 104 44 56-96 110-26-10-112 74-84-74-86 10-112-110-24-58-96-102 44-104-44-56 96-110 24 10 112-74 86 74 84-10 114 110 24 58 96Zm102-318Zm-42 142 226-226-56-58-170 170-86-84-56 56 142 142Z",
)

internal fun symbol(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f)
    .addGroup(translationY = 960f)
    .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black))
    .clearGroup()
    .build()
