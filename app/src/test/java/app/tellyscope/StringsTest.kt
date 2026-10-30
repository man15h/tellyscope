// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [Strings], which the plain JVM tests read, agrees with Android on every string and plural. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StringsTest {
    private val res = RuntimeEnvironment.getApplication().resources

    @Test fun everyStringReadsAsAndroidReadsIt() {
        for (f in R.string::class.java.fields) {
            val name = f.name
            if (name !in Strings.strings) continue // from a library
            assertEquals(name, res.getText(f.getInt(null)).toString(), Strings.strings[name])
        }
    }

    @Test fun everyPluralReadsAsAndroidReadsIt() {
        for (f in R.plurals::class.java.fields) {
            val forms = Strings.plurals[f.name] ?: continue
            for ((quantity, n) in listOf("one" to 1, "other" to 2)) {
                forms[quantity]?.let { assertEquals("${f.name} $quantity", res.getQuantityText(f.getInt(null), n).toString(), it) }
            }
        }
    }
}
