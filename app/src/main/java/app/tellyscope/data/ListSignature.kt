// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import org.json.JSONArray
import org.json.JSONException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The public keys the signatures in `data/index.json` may verify against, base64 SPKI DER of ECDSA P-256 keys, as in
 * `tools/data-keys/`: the one `data/` is signed with, then one kept offline in case it's lost. Built in, never
 * fetched: a `data/` index signed by one of them is the one Tellyscope published.
 */
val DATA_KEYS = listOf(
    // tools/data-keys/primary.pub
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE925XkP+cWPVg1oBCHH7Pf2wVX5JEVXn7d4Wr9bRluIDogbmatLa6IgfxrrcgimZHgYew7SLnfPuWXs4om9bSFQ==",
    // tools/data-keys/backup.pub, kept offline for a rotation or a leak
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXaezXF+/UiXq/fGZOL8NYllUzxPyMbBO46pueSEjOIY+KNX19fSDZ0yEqKK+C4eC3YLwan7KlRQSLdziOrFC7g==",
)

/**
 * Whether [index], the bytes of `index.json`, carries a signature by one of [keys]. Line 2 is the signatures,
 * `  "sigs": ["p256:<base64>", …],`, each base64 of a DER ECDSA signature over SHA-256 of the file with that one
 * line cut out: from just after the first newline up to and including the second. The JSON is never parsed and
 * written back, so the bytes checked are the bytes `tools/data.py sign` signed. No `"sigs"` on line 2 is unsigned.
 * Tags other than `p256` are skipped, so a later algorithm can sign alongside; any `p256` entry that verifies
 * against any key passes. A key or signature that can't be read never passes.
 */
fun verifyIndex(index: ByteArray, keys: List<String>): Boolean {
    val first = index.indexOf('\n'.code.toByte()).takeIf { it >= 0 } ?: return false
    val second = (first + 1 until index.size).firstOrNull { index[it] == '\n'.code.toByte() } ?: return false
    val line = SIGS.matchEntire(index.copyOfRange(first + 1, second).decodeToString()) ?: return false
    val ders = try {
        JSONArray(line.groupValues[1]).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: JSONException) {
        return false
    }.mapNotNull { entry ->
        entry.removePrefix("p256:").takeIf { it != entry }?.let { b64 ->
            try {
                Base64.getDecoder().decode(b64)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
    val signed = index.copyOfRange(0, first + 1) + index.copyOfRange(second + 1, index.size)
    val publicKeys = keys.mapNotNull(::publicKey)
    return ders.any { der ->
        publicKeys.any { key ->
            try {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(signed)
                    verify(der)
                }
            } catch (_: GeneralSecurityException) {
                false
            }
        }
    }
}

/** Line 2 of a signed `index.json`: `  "sigs": [...],`, the list in group 1. */
private val SIGS = Regex("""^ *"sigs": *(\[.*]),$""")

private fun publicKey(b64: String): PublicKey? = try {
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))
} catch (_: GeneralSecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
