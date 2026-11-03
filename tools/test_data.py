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
    """data/index.json.sig is what the app trusts, so check must fail whenever it doesn't cover the index."""

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
        (self.root / "data" / "index.json").write_text('{"schema": 1, "version": 2026110301}\n')

    def public(self, name):
        der = subprocess.run(["openssl", "pkey", "-in", str(self.root / f"{name}.pem"), "-pubout", "-outform", "DER"],
                             check=True, capture_output=True).stdout
        return base64.b64encode(der).decode()

    def test_a_signed_index_passes(self):
        self.assertEqual(data.sign(self.root / "primary.pem"), 0)
        self.assertEqual(data.check_signature(), [])

    def test_an_edit_after_signing_fails(self):
        data.sign(self.root / "primary.pem")
        (self.root / "data" / "index.json").write_text('{"schema": 1, "version": 2026110302}\n')
        self.assertIn("doesn't verify", "\n".join(data.check_signature()))

    def test_a_missing_signature_fails(self):
        self.assertIn("index.json.sig is missing", "\n".join(data.check_signature()))

    def test_sign_refuses_a_key_not_in_keys(self):
        self.assertEqual(data.sign(self.root / "other.pem"), 1)
        self.assertFalse((self.root / "data" / "index.json.sig").exists())

    def test_the_backup_key_verifies_too(self):
        (self.root / "keys" / "backup.pub").write_text(self.public("other") + "\n")
        data.sign(self.root / "other.pem")
        self.assertEqual(data.check_signature(), [])

    def test_a_key_that_is_not_p256_is_caught(self):
        (self.root / "keys" / "backup.pub").write_text("bm90IGEga2V5\n")
        self.assertIn("not a base64 P-256 public key", "\n".join(data.public_keys()[1]))

    def test_version_stays_while_nothing_changes_and_only_goes_up(self):
        (self.root / "data" / "index.json").write_text('{"schema": 1, "version": 2099010105, "lists": {}}\n')
        self.assertEqual(data.version({"lists": {}}), 2099010105)
        self.assertEqual(data.version({"lists": {"a": 1}}), 2099010106)


if __name__ == "__main__":
    unittest.main()
