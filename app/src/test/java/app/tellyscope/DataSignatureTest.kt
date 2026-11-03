// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.DATA_BASE
import app.tellyscope.data.DATA_KEYS
import app.tellyscope.data.DeviceLists
import app.tellyscope.data.LISTS_BASE
import app.tellyscope.data.SharedLists
import app.tellyscope.data.verifyIndex
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * `data/index.json`'s signatures as the app checks them: line 2 holds them, each ECDSA P-256 over the exact bytes of
 * the file with that line cut out, against built-in keys, never an older version than the newest signed one seen, and every file matching the index. Keys are made
 * here, as `tools/data.py sign` would use them. org.json is Android's, so this runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataSignatureTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun reseed() = SharedLists.reseed()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val primary = keyPair()
    private val backup = keyPair()
    private val keys = listOf(primary, backup).map { Base64.getEncoder().encodeToString(it.public.encoded) }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A `p256:` entry for line 2: base64 of the DER signature [key] makes over [unsigned]. */
    private fun p256(unsigned: ByteArray, key: KeyPair = primary): String =
        "p256:" + Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(unsigned); sign() })

    /** As `tools/data.py sign` writes it: [entries] on line 2, right after the `{`. */
    private fun withSigs(unsigned: ByteArray, vararg entries: String): ByteArray {
        val text = unsigned.decodeToString()
        val sigs = entries.joinToString(", ") { "\"$it\"" }
        return text.replaceFirst("{\n", "{\n  \"sigs\": [$sigs],\n").toByteArray()
    }

    private val launchers = """{"schema":1,"launchers":[{"package":"com.good.home","name":"Good","about":"x"}]}""".toByteArray()

    /** As `tools/data.py index` writes it, unsigned. */
    private fun index(version: Long, file: ByteArray = launchers) = """
        |{
        |  "schema": 1,
        |  "version": $version,
        |  "lists": {
        |    "launchers": {"file": "launchers.json", "sha256": "${sha256(file)}"}
        |  }
        |}
        |""".trimMargin().toByteArray()

    /** `data/` serving [files] at [DATA_BASE]; change them between calls to change what it serves. */
    private class Served(var files: Map<String, ByteArray>) {
        val fetch: suspend (String) -> ByteArray = { url -> files[url.removePrefix(DATA_BASE)] ?: throw IOException("404 $url") }
    }

    private fun signed(version: Long = 2026110301, key: KeyPair = primary): Map<String, ByteArray> =
        serving(index(version).let { withSigs(it, p256(it, key)) })

    private fun serving(index: ByteArray) = mapOf("index.json" to index, "launchers.json" to launchers)

    private fun lists(dir: File, served: Served) = DeviceLists(dir, DATA_BASE, keys, served.fetch)

    @Test fun signedByThePrimaryOrTheBackupKeyPasses() = runTest {
        assertTrue(lists(tmp.newFolder(), Served(signed())).verified())
        assertTrue(lists(tmp.newFolder(), Served(signed(key = backup))).verified())
    }

    @Test fun anotherKeyANoSignatureOrAnEditedIndexFails() = runTest {
        assertFalse(lists(tmp.newFolder(), Served(signed(key = keyPair()))).verified())
        assertFalse(lists(tmp.newFolder(), Served(serving(index(2026110301)))).verified())
        val edited = signed().let { it + ("index.json" to (it.getValue("index.json").decodeToString() + " ").toByteArray()) }
        assertFalse(lists(tmp.newFolder(), Served(edited)).verified())
        assertFalse(verifyIndex(withSigs(index(1), "p256:not base64!"), keys))
        assertFalse(verifyIndex(withSigs(index(1)), keys))
    }

    /** Same JSON, other bytes: an editor's reformat means signing again, never a pass. */
    @Test fun aReformattedFileFails() = runTest {
        val text = signed().getValue("index.json").decodeToString()
        val spaced = text.replace("\"version\": ", "\"version\":  ")
        assertFalse(lists(tmp.newFolder(), Served(serving(spaced.toByteArray()))).verified())
        // Re-serialised, "sigs" isn't on its own line 2 any more: unsigned.
        val reserialised = JSONObject(text).toString(2).toByteArray()
        assertFalse(lists(tmp.newFolder(), Served(serving(reserialised))).verified())
    }

    /** A tag the app doesn't know is skipped, so a later algorithm can sign alongside `p256`, but never stands in for it. */
    @Test fun anUnknownTagIsSkipped() = runTest {
        val index = index(2026110301)
        val sig = p256(index).removePrefix("p256:")
        assertTrue(lists(tmp.newFolder(), Served(serving(withSigs(index, "ed25519:AAAA", "p256:$sig")))).verified())
        assertFalse(lists(tmp.newFolder(), Served(serving(withSigs(index, "ed25519:$sig")))).verified())
        assertFalse(lists(tmp.newFolder(), Served(serving(withSigs(index, sig)))).verified())
    }

    /** Signed twice, as while a key is rotated: one entry verifying is enough, whichever it is. */
    @Test fun aSecondValidSignaturePasses() = runTest {
        val index = index(2026110301)
        assertTrue(lists(tmp.newFolder(), Served(serving(withSigs(index, p256(index), p256(index, backup))))).verified())
        assertTrue(lists(tmp.newFolder(), Served(serving(withSigs(index, p256(index, keyPair()), p256(index, backup))))).verified())
        assertFalse(lists(tmp.newFolder(), Served(serving(withSigs(index, p256(index, keyPair()), p256(index, keyPair()))))).verified())
    }

    /** Signed, but not the bytes it says: the index names one launchers.json and another one is served. */
    @Test fun aFileThatDoesNotMatchTheIndexFails() = runTest {
        val other = """{"schema":1,"launchers":[{"package":"com.evil.home","name":"Evil","about":"x"}]}""".toByteArray()
        assertFalse(lists(tmp.newFolder(), Served(signed() + ("launchers.json" to other))).verified())
    }

    @Test fun anOlderVersionFailsEvenSigned() = runTest {
        val dir = tmp.newFolder()
        assertTrue(lists(dir, Served(signed(version = 2026110302))).verified())
        // A new process, as after a restart: the older index is fetched and signed, but the newer one was seen.
        val old = lists(dir, Served(signed(version = 2026110301)))
        assertEquals(2026110301L, old.index()?.version)
        assertFalse(old.verified())
        assertTrue(lists(dir, Served(signed(version = 2026110302))).verified())
    }

    @Test fun offlineTheCacheIsCheckedAgain() = runTest {
        val dir = tmp.newFolder()
        assertTrue(lists(dir, Served(signed())).verified())
        val offline = Served(emptyMap())
        assertTrue(lists(dir, offline).cachedVerified())
        assertTrue(lists(dir, offline).verified())

        File(dir, "launchers.json").writeText("""{"schema":1,"launchers":[]}""")
        assertFalse(lists(dir, offline).cachedVerified())
        File(dir, "index.json").appendText(" ")
        assertFalse(lists(dir, offline).cachedVerified())
    }

    /** The list is still used when it fails, so the card can show it, but [SharedLists.unverified] says it failed. */
    @Test fun sharedListsSaysWhenDataFailsItsCheck() = runTest {
        val served = Served(serving(index(2026110301)))
        val none = DeviceLists(tmp.newFolder()) { throw IOException("offline $it") }
        SharedLists.load(lists(tmp.newFolder(), served), none, fresh = true)
        assertEquals(listOf("com.good.home"), SharedLists.launchers.map { it.pkg })
        assertEquals(DATA_BASE, SharedLists.unverified)

        SharedLists.load(lists(tmp.newFolder(), Served(signed())), none, fresh = true)
        assertNull(SharedLists.unverified)
    }

    /**
     * The app and the Data check trust the same keys: [DATA_KEYS] is every key in the `.pub` files in
     * `tools/data-keys/`, read as `tools/data.py` reads them (`#` lines are comments, a file with no key is a
     * placeholder). Skipped until `tools/data.py` is in the repo. Not written as a glob: Kotlin nests block
     * comments, so a slash-star inside this one would leave it unclosed.
     */
    @Test fun theBuiltInKeysAreToolsDataKeys() {
        assumeTrue("tools/data.py isn't in the repo yet", File("../tools/data.py").exists())
        val files = File("../tools/data-keys").listFiles { f -> f.name.endsWith(".pub") }.orEmpty().sortedBy { it.name }
        val keys = files.mapNotNull { f ->
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString("").ifEmpty { null }
        }
        assertTrue("tools/data-keys/ has no key", keys.isNotEmpty())
        assertEquals(keys.toSet(), DATA_KEYS.toSet())
    }

    /** The list source the user can change is never signed, so it never needs to be. */
    @Test fun theDebloatListSourceHasNoKeys() = runTest {
        val index = """{"schema":1,"devices":[]}""".toByteArray()
        val lists = DeviceLists(tmp.newFolder()) { url -> if (url == LISTS_BASE + "index.json") index else throw IOException("404 $url") }
        assertFalse(lists.index()!!.signed)
    }
}
