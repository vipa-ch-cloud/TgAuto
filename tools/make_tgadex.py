#!/usr/bin/env python3
"""Builds and signs a .tgadex plugin package.

  make_tgadex.py --manifest manifest.template.json --key KEY.pem [-o out.tgadex] classes.dex [classes2.dex ...]

The template carries id, name, version, build, minAppVersion, apiVersion, entry (plugin class),
channel, changelog_*; files/format/created are filled in here. The first dex must be named
classes.dex (extra ones classes2.dex ...; the file name inside the package is taken from the position).
The private key stays outside the repo. Only the official key is applied automatically by the app.
"""
import argparse
import base64
import datetime
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tgadex_common as c


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dex", nargs="+", help="dex files in order: classes.dex, classes2.dex, ...")
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("-o", "--output")
    a = ap.parse_args()
    from cryptography.hazmat.primitives import serialization
    key = serialization.load_pem_private_key(open(a.key, "rb").read(), None)
    m = json.load(open(a.manifest, encoding="utf-8"))
    names = ["classes.dex"] + [f"classes{i}.dex" for i in range(2, 10)]
    if len(a.dex) > c.MAX_DEX_FILES:
        sys.exit("too many dex files")
    blobs = {}
    for name, path in zip(names, a.dex):
        blobs[name] = open(path, "rb").read()
    m["files"] = [{"path": n, "sha256": c.sha256_hex(b), "size": len(b)} for n, b in blobs.items()]
    m["format"] = c.FORMAT
    m.setdefault("channel", "any")
    m["created"] = m.get("created") or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    c.validate_manifest(m)
    mb = c.canonical_manifest(m)
    sig = base64.b64encode(key.sign(c.MAGIC + mb)) + b"\n"
    out = a.output or f"{m['id']}-{m['version']}.tgadex"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in [(c.MANIFEST, mb), (c.SIGNATURE, sig)] + list(blobs.items()):
            zi = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, data)
    pub = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    print(f"signer public key {pub.hex()}  fingerprint sha256 {c.key_fingerprint(pub)}")
    print(f"{out}  {os.path.getsize(out)} bytes, {len(blobs)} dex, sha256 of manifest {c.sha256_hex(mb)[:12]}")


if __name__ == "__main__":
    main()
