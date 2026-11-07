#!/usr/bin/env python3
"""Generates the catalog index.json (+ index.json.sig) for the TgAutoS repository.

  make_store_index.py PACKAGES_DIR --base-url URL --meta store-meta.json --key OWNER.pem [-o OUT_DIR]

PACKAGES_DIR holds *.tgascript files. store-meta.json (owner-maintained, reviewed) maps
script id -> {"verified": bool, "official": bool, "url": optional override}. Only the owner
key can sign the index, so the verified/official flags are trustworthy only with a valid
index.json.sig; the index also pins the sha256 of every package file.
"""
import argparse
import base64
import datetime
import glob
import hashlib
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ota_common as c


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("packages")
    ap.add_argument("--base-url", required=True, help="URL prefix the .tgascript files are served from")
    ap.add_argument("--meta")
    ap.add_argument("--key", required=True)
    ap.add_argument("-o", "--out", default=".")
    a = ap.parse_args()
    from cryptography.hazmat.primitives import serialization
    key = serialization.load_pem_private_key(open(a.key, "rb").read(), None)
    meta = json.load(open(a.meta, encoding="utf-8")) if a.meta else {}
    pub = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    best = {}
    for p in sorted(glob.glob(os.path.join(a.packages, "*.tgascript"))):
        m, _, signed = c.verify_package(p, pub, "999", 10**9, "stable", kind=c.SCRIPT_FORMAT)
        cur = best.get(m["id"])
        if not cur or c.vcmp(m["version"], cur[0]["version"]) > 0:
            best[m["id"]] = (m, p, signed)
    entries = []
    for sid, (m, p, signed) in sorted(best.items()):
        mt = meta.get(sid, {})
        blob = open(p, "rb").read()
        entries.append({
            "id": sid, "name": m["name"], "author": m.get("author", ""), "version": m["version"],
            "description_en": m.get("description_en", ""), "description_ru": m.get("description_ru", ""),
            "changelog_en": m.get("changelog_en", ""), "changelog_ru": m.get("changelog_ru", ""),
            "tags": m.get("tags", []), "permissions": m.get("permissions", {}),
            "sha256": hashlib.sha256(blob).hexdigest(), "size": len(blob),
            "url": mt.get("url") or a.base_url.rstrip("/") + "/" + os.path.basename(p),
            "verified": bool(mt.get("verified") or mt.get("official")), "official": bool(mt.get("official")),
            "signed": signed,
        })
    index = {"format": c.INDEX_FORMAT, "updated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "scripts": entries}
    data = json.dumps(index, sort_keys=True, indent=1, ensure_ascii=False).encode("utf-8") + b"\n"
    os.makedirs(a.out, exist_ok=True)
    open(os.path.join(a.out, "index.json"), "wb").write(data)
    open(os.path.join(a.out, "index.json.sig"), "wb").write(base64.b64encode(key.sign(c.MAGIC_INDEX + data)) + b"\n")
    print(f"index.json: {len(entries)} scripts, signed")


if __name__ == "__main__":
    main()
