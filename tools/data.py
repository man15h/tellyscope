#!/usr/bin/env python3
"""Index and sign data/, the lists the app downloads from this repo. Python 3.9+, standard library only.

  tools/data.py index                          rebuild data/index.json from data/'s lists, unsigned; left
                                               alone while nothing changed
  tools/data.py sign --key <private key.pem>…  sign data/index.json with each key, on its line 2
  tools/data.py check [--unsigned]             fail if data/index.json is stale or its signature doesn't
                                               verify against tools/data-keys/ (--unsigned skips that)

The signature sits on line 2 of data/index.json, `  "sigs": ["p256:<base64>", …],`. What is signed is
the file without that line, cut out as bytes: never re-serialise the JSON to check it.

Signing and checking signatures need the openssl command. How and when to sign: CONTRIBUTING.md.
"""
import base64
import datetime
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SCHEMA = 1
REPO = Path(__file__).resolve().parent.parent
DATA = REPO / "data"
KEYS = REPO / "tools" / "data-keys"
LISTS = ("launchers", "labels", "streaming")
SIGS = b'  "sigs": '
# The icon and screenshot files the app reads (IMAGE_FILE in DeviceLists.kt); it drops any other without a word.
IMAGE_FILE = re.compile(r"images/[a-z0-9][a-z0-9-]{0,39}\.png")
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
    old.pop("sigs", None)
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
        errors.append("tools/data-keys/: no public key to check data/index.json's signature against")
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


def split(raw):
    """(unsigned bytes, sigs line or None): line 2 is the signature line when it starts with "sigs",
    and the unsigned bytes are the file with exactly that line cut out."""
    first = raw.find(b"\n") + 1
    second = raw.find(b"\n", first) + 1
    if first and second and raw[first:second].startswith(SIGS):
        return raw[:first] + raw[second:], raw[first:second]
    return raw, None


def signatures(line):
    """The p256 signatures on a sigs line, as DER; entries with other tags are skipped. None if the
    line isn't `  "sigs": [<strings>],`."""
    body = line[len(SIGS):].rstrip(b"\n")
    try:
        entries = json.loads(body[:-1]) if body.endswith(b",") else None
    except ValueError:
        entries = None
    if not isinstance(entries, list) or not all(isinstance(e, str) for e in entries):
        return None
    found = []
    for tag, _, value in (e.partition(":") for e in entries):
        if tag == "p256":
            try:
                found.append(base64.b64decode(value, validate=True))
            except ValueError:
                pass
    return found


def check_signature():
    keys, errors = public_keys()
    if errors:
        return errors
    unsigned, line = split((DATA / "index.json").read_bytes())
    if line is None:
        return ["data/index.json is unsigned: run tools/data.py sign --key <private key>"]
    sigs = signatures(line)
    if sigs is None:
        return ['data/index.json: line 2 isn\'t `  "sigs": ["p256:<base64>", …],`']
    if not any(verifies(unsigned, sig, k) for sig in sigs for k in keys):
        return ["data/index.json's signature doesn't verify against tools/data-keys/: the file changed "
                "since it was signed, run tools/data.py sign --key <private key>"]
    return []


def sign(keys):
    """Sign data/index.json without its sigs line with each key, and write the signatures on line 2,
    if a key in tools/data-keys/ verifies every one of them."""
    path = DATA / "index.json"
    unsigned, _ = split(path.read_bytes())
    public, errors = public_keys()
    entries = []
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "data").write_bytes(unsigned)
        for key in keys:
            run = openssl("dgst", "-sha256", "-sign", str(key), str(Path(tmp) / "data"))
            if run.returncode != 0:
                print(run.stderr.decode(errors="replace"), file=sys.stderr)
                return 1
            if errors or not any(verifies(unsigned, run.stdout, k) for k in public):
                print(*errors, f"{key}: the signature doesn't verify against any key in tools/data-keys/: "
                      "wrong key?", sep="\n", file=sys.stderr)
                return 1
            entries.append("p256:" + base64.b64encode(run.stdout).decode())
    first = unsigned.find(b"\n") + 1
    path.write_bytes(unsigned[:first] + SIGS + json.dumps(entries).encode() + b",\n" + unsigned[first:])
    return 0


def index():
    """Write data/index.json unsigned, unless it already says the same, so a signature stays put."""
    path = DATA / "index.json"
    text = dump(build_index())
    if not path.is_file() or split(path.read_bytes())[0] != text.encode():
        path.write_text(text, encoding="utf-8")


def bad_images():
    """launchers.json icons and screenshots the app wouldn't show: a file it doesn't read, or one whose sha256 doesn't match."""
    path = DATA / "launchers.json"
    doc = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
    launchers = doc.get("launchers", []) if isinstance(doc, dict) else []
    errors = []
    for l in launchers:
        for key in ("icon", "screenshot"):
            image = l.get(key) if isinstance(l, dict) else None
            if image is None:
                continue
            where = f"data/launchers.json: {l.get('package')}'s {key}"
            name = image.get("file") if isinstance(image, dict) else None
            if not isinstance(name, str) or not IMAGE_FILE.fullmatch(name):
                errors.append(f'{where}: must be {{"file": "images/<name>.png", "sha256": …}}, <name> in lowercase letters, digits and -, at most 40')
            elif not (DATA / name).is_file():
                errors.append(f"{where}: data/{name} isn't there")
            elif image.get("sha256") != hashlib.sha256((DATA / name).read_bytes()).hexdigest():
                errors.append(f"{where}: sha256 doesn't match data/{name}")
    return errors


def check(signed=True):
    path = DATA / "index.json"
    if not path.is_file() or split(path.read_bytes())[0] != dump(build_index()).encode():
        errors = ["data/index.json is stale: run tools/data.py index"]
    else:
        errors = check_signature() if signed else []
    errors = bad_images() + errors
    for e in errors:
        print(e, file=sys.stderr)
    return 1 if errors else 0


def main(argv):
    if argv[:1] == ["check"] and argv[1:] in ([], ["--unsigned"]):
        return check(signed=not argv[1:])
    if argv[:1] == ["sign"] and len(argv) > 1 and argv[1::2] == ["--key"] * (len(argv) // 2) and len(argv) % 2:
        return sign(argv[2::2])
    if argv == ["index"]:
        index()
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
