#!/usr/bin/env python3
"""Builds and signs a .tgadyn OTA package.

  make_ota.py PAYLOAD_DIR --template manifest.template.json --key KEY.pem [-o out.tgadyn]

PAYLOAD_DIR holds python/, res/strings.json, schemas/, docs/ (see docs/OTA.md).
The template carries name, version, build, requires_base, channel, changelog_*;
files/created/format are filled in here. The private key stays outside the repo.
"""
import argparse
import base64
import datetime
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ota_common as c


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("payload")
    ap.add_argument("--template", required=True)
    ap.add_argument("--key", required=True, help="Ed25519 private key (PEM), kept outside the repo")
    ap.add_argument("-o", "--output")
    a = ap.parse_args()
    from cryptography.hazmat.primitives import serialization
    key = serialization.load_pem_private_key(open(a.key, "rb").read(), None)

    m = json.load(open(a.template, encoding="utf-8"))
    files = []
    for root, _, names in os.walk(a.payload):
        for n in sorted(names):
            full = os.path.join(root, n)
            rel = os.path.relpath(full, a.payload).replace(os.sep, "/")
            if not c.safe_path(rel):
                sys.exit(f"payload path not allowed: {rel}")
            blob = open(full, "rb").read()
            files.append({"path": rel, "sha256": c.sha256_hex(blob), "size": len(blob)})
    files.sort(key=lambda f: f["path"])
    m.update(format=c.FORMAT, files=files)
    m.setdefault("channel", "any")
    m["created"] = m.get("created") or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    c.validate_manifest(m)
    mb = c.canonical_manifest(m)
    sig = base64.b64encode(key.sign(c.MAGIC + mb)) + b"\n"
    out = a.output or f"tga-{m['version']}-{m['channel']}.tgadyn"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in ((c.MANIFEST, mb), (c.SIGNATURE, sig)):
            z.writestr(zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)), data, zipfile.ZIP_DEFLATED)
        for f in files:
            zi = zipfile.ZipInfo(f["path"], (1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, open(os.path.join(a.payload, f["path"]), "rb").read())
    print(f"{out}  {os.path.getsize(out)} bytes, {len(files)} files, sha256 of manifest {c.sha256_hex(mb)[:12]}")


if __name__ == "__main__":
    main()
