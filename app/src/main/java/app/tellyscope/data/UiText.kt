// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.tellyscope.R

/**
 * Text for the user, picked out of `res/values/strings.xml` only when it's shown, so the
 * ViewModel and data layer can say what to show without holding a [Context]. Data classes, so
 * state compares equal and tests can assert on it without resources.
 */
sealed interface UiText {
    /** Text that's already in its final form: a name or a message from the TV, never a sentence of ours. */
    data class Raw(val text: String) : UiText

    /** A string resource; an argument that is itself [UiText] is resolved first. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A plural resource picked by [count]; [args] fill its format, usually starting with [count]. */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** [parts] one after another, [separator] between them: paragraphs of one message. */
    data class Join(val parts: List<UiText>, val separator: String = "") : UiText

    /** [text] with its first letter made upper case: a reason that also reads mid-sentence, said on its own. */
    data class Capitalized(val text: UiText) : UiText

    fun resolve(res: Resources): String = when (this) {
        is Raw -> text
        is Res -> if (args.isEmpty()) res.getString(id) else res.getString(id, *args.resolved(res))
        is Plural -> res.getQuantityString(id, count, *args.resolved(res))
        is Join -> parts.joinToString(separator) { it.resolve(res) }
        is Capitalized -> text.resolve(res).replaceFirstChar(Char::uppercase)
    }

    fun resolve(context: Context): String = resolve(context.resources)

    companion object {
        operator fun invoke(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

private fun List<Any>.resolved(res: Resources): Array<Any> = map { if (it is UiText) it.resolve(res) else it }.toTypedArray()

/** Resolves in a composable, the way `stringResource` does: again when the configuration changes. */
@Composable
@ReadOnlyComposable
fun UiText.resolve(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current.resources)
}

fun String.asUiText(): UiText = UiText.Raw(this)

/** A failure whose message is for the user; [text] is what the screen shows. */
open class UiTextException(val text: UiText) : IllegalStateException()

/** What went wrong, for the user: our own wording when we threw it, else the message as it came. */
fun Throwable.why(@StringRes fallback: Int): UiText = (this as? UiTextException)?.text ?: message?.asUiText() ?: UiText(fallback)
