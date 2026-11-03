#!/usr/bin/env python3
"""Index and sign data/, the lists the app downloads from this repo. Python 3.9+, standard library only.

  tools/data.py index                         rebuild data/index.json from data/
  tools/data.py sign --key <private key.pem>  write data/index.json.sig, signed with that key
  tools/data.py check [--unsigned]            fail if data/index.json is stale or data/index.json.sig
                                              doesn't verify against tools/data-keys/ (--unsigned skips that)

Signing and checking signatures need the openssl command. How and when to sign: CONTRIBUTING.md.
"""
import base64
import datetime
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCHEMA = 1
REPO = Path(__file__).resolve().parent.parent
DATA = REPO / "data"
KEYS = REPO / "tools" / "data-keys"
LISTS = ("launchers", "labels", "streaming")
# Every P-256 public key in SubjectPublicKeyInfo DER starts with these bytes, then 64 bytes of point.
P256_SPKI = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200" "04")


def dump(obj):
    return json.dumps(obj, indent=2, ensure_ascii=False) + "\n"


def build_index():
    def file(path):
        return {"file": path, "sha256": hashlib.sha256((DATA / path).read_bytes()).hexdigest()}

    index = {"lists": {name: file(f"{name}.json") for name in LISTS}}
    return {"schema": SCHEMA, "version": version(index), **index}


def version(index):
    """The old version when nothing else changed, else YYYYMMDDNN for today (UTC), always above the
    old one. The app refuses an index older than the one it has."""
    path = DATA / "index.json"
    old = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
    old.pop("schema", None)
    previous = old.pop("version", 0)
    if old == index and previous:
        return previous
    return max(previous + 1, int(datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")) * 100 + 1)


def public_keys():
    """tools/data-keys/*.pub: one base64 SubjectPublicKeyInfo per file, '#' lines are comments.
    Returns (keys, errors)."""
    keys, errors = [], []
    for f in sorted(KEYS.glob("*.pub")):
        lines = [l.strip() for l in f.read_text(encoding="utf-8").splitlines()]
        lines = [l for l in lines if l and not l.startswith("#")]
        if not lines:
            continue  # a placeholder
        try:
            der = base64.b64decode("".join(lines), validate=True)
        except ValueError:
            der = b""
        if len(der) != len(P256_SPKI) + 64 or not der.startswith(P256_SPKI):
            errors.append(f"tools/data-keys/{f.name}: not a base64 P-256 public key")
        else:
            keys.append(der)
    if not keys and not errors:
        errors.append("tools/data-keys/: no public key to check data/index.json.sig against")
    return keys, errors


def openssl(*args):
    return subprocess.run(["openssl", *args], capture_output=True)


def verifies(data, signature, key):
    """True if signature (DER ECDSA) is over data with key (SPKI DER), SHA-256."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "data").write_bytes(data)
        (tmp / "sig").write_bytes(signature)
        (tmp / "key.der").write_bytes(key)
        return openssl("dgst", "-sha256", "-keyform", "DER", "-verify", str(tmp / "key.der"),
                       "-signature", str(tmp / "sig"), str(tmp / "data")).returncode == 0


def check_signature():
    keys, errors = public_keys()
    sig = DATA / "index.json.sig"
    if errors:
        return errors
    if not sig.is_file():
        return ["data/index.json.sig is missing: run tools/data.py sign --key <private key>"]
    try:
        signature = base64.b64decode(sig.read_text(encoding="utf-8").strip(), validate=True)
    except ValueError:
        return ["data/index.json.sig: not base64"]
    if not any(verifies((DATA / "index.json").read_bytes(), signature, k) for k in keys):
        return ["data/index.json.sig doesn't verify against tools/data-keys/: data/index.json changed "
                "since it was signed, run tools/data.py sign --key <private key>"]
    return []


def sign(key):
    """Sign data/index.json's exact bytes with key and write data/index.json.sig, if a key in
    tools/data-keys/ verifies it."""
    data = (DATA / "index.json").read_bytes()
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "data").write_bytes(data)
        run = openssl("dgst", "-sha256", "-sign", str(key), str(Path(tmp) / "data"))
    if run.returncode != 0:
        print(run.stderr.decode(errors="replace"), file=sys.stderr)
        return 1
    keys, errors = public_keys()
    if errors or not any(verifies(data, run.stdout, k) for k in keys):
        print(*errors, "the signature doesn't verify against any key in tools/data-keys/: wrong key?",
              sep="\n", file=sys.stderr)
        return 1
    (DATA / "index.json.sig").write_text(base64.b64encode(run.stdout).decode() + "\n", encoding="utf-8")
    return 0


def check(signed=True):
    index = DATA / "index.json"
    if not index.is_file() or index.read_text(encoding="utf-8") != dump(build_index()):
        errors = ["data/index.json is stale: run tools/data.py index"]
    else:
        errors = check_signature() if signed else []
    for e in errors:
        print(e, file=sys.stderr)
    return 1 if errors else 0


def main(argv):
    if argv[:1] == ["check"] and argv[1:] in ([], ["--unsigned"]):
        return check(signed=not argv[1:])
    if argv[:2] == ["sign", "--key"] and len(argv) == 3:
        return sign(argv[2])
    if argv == ["index"]:
        (DATA / "index.json").write_text(dump(build_index()), encoding="utf-8")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
