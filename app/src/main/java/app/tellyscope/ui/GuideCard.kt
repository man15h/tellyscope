// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tellyscope.R

/**
 * How to switch on debugging, per brand, because the menu names differ. [pair] is where the
 * pairing code lives on TVs with Wireless debugging; null where the brand only has the older switch.
 * `**…**` marks the words to find on the TV. [debugDone] is [debugTitle] once it's done.
 */
class TvBrand(
    @StringRes val name: Int,
    @StringRes val unlock: Int,
    @StringRes val debugTitle: Int,
    @StringRes val debugDone: Int,
    @StringRes val debug: Int,
    @StringRes val pair: Int?,
)

private val ANDROID_TV_PAIR = R.string.guide_pair_android_tv

val brands = listOf(
    TvBrand(
        R.string.guide_brand_google_tv,
        unlock = R.string.guide_unlock_google_tv,
        debugTitle = R.string.guide_debug_title_wireless,
        debugDone = R.string.guide_debug_done_wireless,
        debug = R.string.guide_debug_google_tv,
        pair = R.string.guide_pair_google_tv,
    ),
    TvBrand(
        R.string.guide_brand_android_tv,
        unlock = R.string.guide_unlock_build,
        debugTitle = R.string.guide_debug_title_wireless,
        debugDone = R.string.guide_debug_done_wireless,
        debug = R.string.guide_debug_android_tv,
        pair = ANDROID_TV_PAIR,
    ),
    TvBrand(
        R.string.guide_brand_fire_tv,
        unlock = R.string.guide_unlock_fire_tv,
        debugTitle = R.string.guide_debug_title_adb,
        debugDone = R.string.guide_debug_done_adb,
        debug = R.string.guide_debug_fire_tv,
        pair = null,
    ),
    TvBrand(
        R.string.guide_brand_xiaomi,
        unlock = R.string.guide_unlock_build,
        debugTitle = R.string.guide_debug_title_wireless,
        debugDone = R.string.guide_debug_done_wireless,
        debug = R.string.guide_debug_xiaomi,
        pair = ANDROID_TV_PAIR,
    ),
    TvBrand(
        R.string.guide_brand_xgimi,
        unlock = R.string.guide_unlock_build,
        debugTitle = R.string.guide_debug_title_wireless,
        debugDone = R.string.guide_debug_done_wireless,
        debug = R.string.guide_debug_xgimi,
        pair = ANDROID_TV_PAIR,
    ),
    TvBrand(
        R.string.guide_brand_other,
        unlock = R.string.guide_unlock_other,
        debugTitle = R.string.guide_debug_title_wireless,
        debugDone = R.string.guide_debug_done_wireless,
        debug = R.string.guide_debug_other,
        pair = R.string.guide_pair_other,
    ),
)

/** The brand chips: every brand stays visible, so nobody has to guess what a menu hides. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrandChips(brand: TvBrand, onPick: (TvBrand) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.guide_pick_tv), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            brands.forEach { b ->
                FilterChip(
                    selected = b == brand,
                    onClick = { onPick(b) },
                    label = { Text(stringResource(b.name)) },
                    leadingIcon = if (b == brand) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                )
            }
        }
    }
}

/**
 * One step open at a time: "Done, next" ticks it off and opens the next, "Back" reopens the one
 * before. The last step holds [findTvs], which is the live scan and whatever it found.
 */
@Composable
fun GuideSteps(brand: TvBrand, step: Int, onStep: (Int) -> Unit, findTvs: @Composable () -> Unit) {
    val steps = listOf(
        Triple(stringResource(R.string.guide_unlock_title), stringResource(brand.unlock), stringResource(R.string.guide_unlock_done)),
        Triple(stringResource(brand.debugTitle), stringResource(brand.debug), stringResource(brand.debugDone)),
        Triple(stringResource(R.string.guide_find_title), "", ""),
    )
    Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        steps.forEachIndexed { i, (title, body, done) ->
            val last = i == steps.lastIndex
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    StepDot(i + 1, done = i < step, current = i == step)
                    if (!last) Box(Modifier.padding(vertical = 4.dp).width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
                }
                Column(
                    Modifier.padding(start = 16.dp, top = 4.dp, bottom = if (last) 8.dp else 24.dp).weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (i > step) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
                    when {
                        i < step -> Text(done, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        i == step && last -> findTvs()
                        i == step -> {
                            Text(marked(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onStep(i + 1) }) { Text(stringResource(R.string.guide_done_next)) }
                                if (i > 0) TextButton(onClick = { onStep(i - 1) }) { Text(stringResource(R.string.guide_back)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDot(n: Int, done: Boolean, current: Boolean) {
    val c = MaterialTheme.colorScheme
    val filled = done || current
    Box(
        Modifier.size(32.dp)
            .background(if (filled) c.primary else c.surface, CircleShape)
            .border(2.dp, if (filled) c.primary else c.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.guide_done), Modifier.size(18.dp), tint = c.onPrimary)
        else Text("$n", style = MaterialTheme.typography.labelLarge, color = if (current) c.onPrimary else c.outline)
    }
}

/** "Can't find your TV?": the fixes that used to sit under the guide as small print. */
@Composable
fun HelpDialog(brand: TvBrand, onAddByIp: () -> Unit, onDismiss: () -> Unit) {
    val tips = buildList {
        add(R.string.guide_tip_same_wifi)
        add(R.string.guide_tip_debugging_off)
        if (brand.name == R.string.guide_brand_fire_tv) add(R.string.guide_tip_vega)
        add(R.string.guide_tip_by_ip)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guide_help_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tips.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { Button(onClick = { onDismiss(); onAddByIp() }) { Text(stringResource(R.string.guide_add_by_ip)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.guide_close)) } },

    )
}

/** Turns `**words**` into medium-weight spans: the menu names to look for on the TV. */
@Composable
fun marked(text: String): AnnotatedString {
    val strong = SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    return buildAnnotatedString {
        text.split("**").forEachIndexed { i, part -> if (i % 2 == 1) withStyle(strong) { append(part) } else append(part) }
    }
}
