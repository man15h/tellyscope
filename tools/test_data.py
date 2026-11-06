import base64
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import data  # noqa: E402


class Signing(unittest.TestCase):
    """Line 2 of data/index.json is what the app trusts, so check must fail whenever it doesn't cover the rest."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        for d in ("data", "keys"):
            (self.root / d).mkdir()
        for name, value in (("DATA", self.root / "data"), ("KEYS", self.root / "keys")):
            self.addCleanup(setattr, data, name, getattr(data, name))
            setattr(data, name, value)
        for name in ("primary", "other"):
            subprocess.run(["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256",
                            "-out", str(self.root / f"{name}.pem")], check=True, capture_output=True)
        (self.root / "keys" / "primary.pub").write_text("# test\n" + self.public("primary") + "\n")
        (self.root / "keys" / "backup.pub").write_text("# placeholder\n")
        self.index().write_text(data.dump({"schema": 1, "version": 2026110301}))

    def index(self):
        return self.root / "data" / "index.json"

    def public(self, name):
        der = subprocess.run(["openssl", "pkey", "-in", str(self.root / f"{name}.pem"), "-pubout", "-outform", "DER"],
                             check=True, capture_output=True).stdout
        return base64.b64encode(der).decode()

    def sigs(self, *entries):
        """Puts a sigs line with these entries on line 2."""
        unsigned, _ = data.split(self.index().read_bytes())
        line = data.SIGS + data.json.dumps(list(entries)).encode() + b",\n"
        self.index().write_bytes(unsigned[:2] + line + unsigned[2:])

    def entries(self):
        return data.json.loads(self.index().read_text())["sigs"]

    def test_a_signed_index_passes_and_is_still_json(self):
        before = self.index().read_bytes()
        self.assertEqual(data.sign([self.root / "primary.pem"]), 0)
        self.assertEqual(data.check_signature(), [])
        self.assertEqual(data.split(self.index().read_bytes())[0], before)
        self.assertEqual(self.index().read_text().splitlines()[1][:len('  "sigs": ["p256:')], '  "sigs": ["p256:')
        self.assertEqual(data.json.loads(self.index().read_text())["version"], 2026110301)

    def test_an_edit_after_signing_fails(self):
        data.sign([self.root / "primary.pem"])
        self.index().write_text(self.index().read_text().replace("2026110301", "2026110302"))
        self.assertIn("doesn't verify", "\n".join(data.check_signature()))

    def test_a_reformatted_index_fails(self):
        data.sign([self.root / "primary.pem"])
        self.index().write_text(self.index().read_text().replace("\n  ", "\n    "))
        self.assertNotEqual(data.check_signature(), [])

    def test_an_unsigned_index_fails(self):
        self.assertIn("is unsigned", "\n".join(data.check_signature()))

    def test_a_sigs_line_that_is_not_a_list_of_strings_fails(self):
        self.sigs(1)
        self.assertIn("line 2 isn't", "\n".join(data.check_signature()))

    def test_sign_refuses_a_key_not_in_keys(self):
        before = self.index().read_bytes()
        self.assertEqual(data.sign([self.root / "other.pem"]), 1)
        self.assertEqual(self.index().read_bytes(), before)

    def test_signing_again_replaces_the_signatures(self):
        data.sign([self.root / "primary.pem"])
        data.sign([self.root / "primary.pem"])
        self.assertEqual(len(self.entries()), 1)
        self.assertEqual(data.check_signature(), [])

    def test_the_backup_key_verifies_too(self):
        (self.root / "keys" / "backup.pub").write_text(self.public("other") + "\n")
        data.sign([self.root / "other.pem"])
        self.assertEqual(data.check_signature(), [])

    def test_two_keys_sign_side_by_side_and_either_passes(self):
        (self.root / "keys" / "backup.pub").write_text(self.public("other") + "\n")
        self.assertEqual(data.sign([self.root / "primary.pem", self.root / "other.pem"]), 0)
        first, second = self.entries()
        self.sigs("p256:" + base64.b64encode(b"junk").decode(), second)
        self.assertEqual(data.check_signature(), [])
        self.sigs(first)
        self.assertEqual(data.check_signature(), [])

    def test_an_unknown_tag_is_skipped(self):
        data.sign([self.root / "primary.pem"])
        (good,) = self.entries()
        self.sigs("ed25519:" + good[len("p256:"):], good)
        self.assertEqual(data.check_signature(), [])
        self.sigs("ed25519:" + good[len("p256:"):])
        self.assertIn("doesn't verify", "\n".join(data.check_signature()))

    def test_index_leaves_a_signed_file_alone_and_check_sees_through_the_signature(self):
        for name in data.LISTS:
            (self.root / "data" / f"{name}.json").write_text("[]\n")
        data.index()
        data.sign([self.root / "primary.pem"])
        signed = self.index().read_bytes()
        data.index()
        self.assertEqual(self.index().read_bytes(), signed)
        self.assertEqual(data.check(), 0)
        (self.root / "data" / "labels.json").write_text("{}\n")
        self.assertEqual(data.check(signed=False), 1)
        data.index()
        self.assertIsNone(data.split(self.index().read_bytes())[1])

    def test_images_stay_out_of_the_index(self):
        for name in data.LISTS:
            (self.root / "data" / f"{name}.json").write_text("[]\n")
        (self.root / "data" / "images").mkdir()
        (self.root / "data" / "images" / "monet.png").write_bytes(b"png")
        self.assertEqual(list(data.build_index()), ["schema", "version", "lists"])

    def test_a_screenshot_the_app_would_not_show_fails_check(self):
        (self.root / "data" / "images").mkdir()
        (self.root / "data" / "images" / "monet.png").write_bytes(b"png")
        sha = data.hashlib.sha256(b"png").hexdigest()
        shots = {
            "a.good": {"file": "images/monet.png", "sha256": sha},
            "a.name": {"file": "images/Monet-Home.png", "sha256": sha},
            "a.away": {"file": "../monet.png", "sha256": sha},
            "a.old": "monet",
            "a.gone": {"file": "images/at4k.png", "sha256": sha},
            "a.hash": {"file": "images/monet.png", "sha256": "0" * 64},
        }
        launchers = [{"package": p, "name": p, "screenshot": s} for p, s in shots.items()] + [{"package": "a.none", "name": "None"}]
        (self.root / "data" / "launchers.json").write_text(data.json.dumps({"schema": 1, "launchers": launchers}))
        bad = [e.split("'")[0].split(": ")[1] for e in data.bad_images()]
        self.assertEqual(bad, ["a.name", "a.away", "a.old", "a.gone", "a.hash"])

    def test_an_icon_the_app_would_not_show_fails_check(self):
        (self.root / "data" / "images").mkdir()
        (self.root / "data" / "images" / "monet-icon.png").write_bytes(b"png")
        sha = data.hashlib.sha256(b"png").hexdigest()
        icons = {
            "a.good": {"file": "images/monet-icon.png", "sha256": sha},
            "a.url": "https://example.com/monet.png",
            "a.hash": {"file": "images/monet-icon.png", "sha256": "0" * 64},
        }
        launchers = [{"package": p, "name": p, "icon": i} for p, i in icons.items()]
        (self.root / "data" / "launchers.json").write_text(data.json.dumps({"schema": 1, "launchers": launchers}))
        bad = [e.split(": ")[1] for e in data.bad_images()]
        self.assertEqual(bad, ["a.url's icon", "a.hash's icon"])

    def test_a_key_that_is_not_p256_is_caught(self):
        (self.root / "keys" / "backup.pub").write_text("bm90IGEga2V5\n")
        self.assertIn("not a base64 P-256 public key", "\n".join(data.public_keys()[1]))

    def test_version_stays_while_nothing_changes_and_only_goes_up(self):
        (self.root / "data" / "index.json").write_text('{"schema": 1, "version": 2099010105, "lists": {}}\n')
        self.assertEqual(data.version({"lists": {}}), 2099010105)
        self.assertEqual(data.version({"lists": {"a": 1}}), 2099010106)


if __name__ == "__main__":
    unittest.main()
