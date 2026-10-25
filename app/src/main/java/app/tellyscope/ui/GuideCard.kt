// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * How to switch on debugging, per brand, because the menu names differ. [pair] is where the
 * pairing code lives on TVs with Wireless debugging; null where the brand only has the older switch.
 * `**…**` marks the words to find on the TV.
 */
class TvBrand(val name: String, val unlock: String, val debugTitle: String, val debug: String, val pair: String?)

private const val ANDROID_TV_PAIR = "**Device Preferences → Developer options → Wireless debugging → Pair device with pairing code**"

val brands = listOf(
    TvBrand(
        "Google TV",
        unlock = "On the TV: **Settings → System → About**. Press OK on **Android TV OS build** 7 times, until it says you're a developer.",
        debugTitle = "Turn on Wireless debugging",
        debug = "Back to **System → Developer options**. Turn on **Wireless debugging**. Older TVs call it **USB debugging**.",
        pair = "**System → Developer options → Wireless debugging → Pair device with pairing code**",
    ),
    TvBrand(
        "Android TV",
        unlock = "On the TV: **Settings → Device Preferences → About**. Press OK on **Build** 7 times.",
        debugTitle = "Turn on Wireless debugging",
        debug = "Back to **Device Preferences → Developer options**. Turn on **Wireless debugging**. Older TVs have **USB debugging** and **Network debugging** instead: turn on both.",
        pair = ANDROID_TV_PAIR,
    ),
    TvBrand(
        "Fire TV",
        unlock = "On the TV: **Settings → My Fire TV → About**. Press OK on the first row, your Fire TV's name, 7 times.",
        debugTitle = "Turn on ADB debugging",
        debug = "Back to **My Fire TV → Developer options**. Turn on **ADB debugging**.",
        pair = null,
    ),
    TvBrand(
        "Xiaomi",
        unlock = "On the TV: **Settings → Device Preferences → About**. Press OK on **Build** 7 times.",
        debugTitle = "Turn on Wireless debugging",
        debug = "Back to **Device Preferences → Developer options**. Turn on **Wireless debugging**. Older TVs call it **USB debugging**.",
        pair = ANDROID_TV_PAIR,
    ),
    TvBrand(
        "XGIMI",
        unlock = "On the TV: **Settings → Device Preferences → About**. Press OK on **Build** 7 times.",
        debugTitle = "Turn on Wireless debugging",
        debug = "Back to **Device Preferences → Developer options**. Turn on **Wireless debugging**. Older projectors have **USB debugging**, then **Network debugging** if it's listed: turn on both.",
        pair = ANDROID_TV_PAIR,
    ),
    TvBrand(
        "Other",
        unlock = "On the TV, open **Settings**. Has **System**? It's Google TV: go to **System → About** and press OK on **Android TV OS build** 7 times. Has **Device Preferences**? It's Android TV: go to **Device Preferences → About** and press OK on **Build** 7 times.",
        debugTitle = "Turn on Wireless debugging",
        debug = "Back one level, open **Developer options**. Turn on **Wireless debugging**. Older TVs have **USB debugging**, and some **Network debugging** too: turn on what's there.",
        pair = "**Developer options → Wireless debugging → Pair device with pairing code**",
    ),
)

/** The brand chips: every brand stays visible, so nobody has to guess what a menu hides. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrandChips(brand: TvBrand, onPick: (TvBrand) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pick your TV", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            brands.forEach { b ->
                FilterChip(
                    selected = b == brand,
                    onClick = { onPick(b) },
                    label = { Text(b.name) },
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
        Triple("Unlock developer options", brand.unlock, "Developer options unlocked"),
        Triple(brand.debugTitle, brand.debug, brand.debugTitle.removePrefix("Turn on ") + " on"),
        Triple("Find your TV", "", ""),
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
                                Button(onClick = { onStep(i + 1) }) { Text("Done, next") }
                                if (i > 0) TextButton(onClick = { onStep(i - 1) }) { Text("Back") }
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
        if (done) Icon(Icons.Default.Check, contentDescription = "Done", Modifier.size(18.dp), tint = c.onPrimary)
        else Text("$n", style = MaterialTheme.typography.labelLarge, color = if (current) c.onPrimary else c.outline)
    }
}

/** "Can't find your TV?": the fixes that used to sit under the guide as small print. */
@Composable
fun HelpDialog(brand: TvBrand, onAddByIp: () -> Unit, onDismiss: () -> Unit) {
    val tips = buildList {
        add("Keep the phone and the TV on the same Wi-Fi. Guest networks often keep devices apart.")
        add("Debugging switched itself off? Some TVs reset it after a restart. Turn it back on and this screen finds the TV again.")
        if (brand.name == "Fire TV") add("No Developer options after 7 presses? Newer Fire TVs run Vega OS, which isn't Android, and Tellyscope can't reach them.")
        add("Still nothing? Add the TV by its IP address, from the TV's network settings.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Can't find your TV?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tips.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onAddByIp() }) { Text("Add by IP address") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
