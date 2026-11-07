#!/usr/bin/env python3
"""Builds a .tgascript package (zip with manifest.json, optional signature) from a script folder.

  make_script_pkg.py SCRIPT_DIR [--key OWNER.pem] [-o out.tgascript]

SCRIPT_DIR must contain tgascript.json (id, name, version, author, description_en/ru, tags,
entry, permissions, changelog_en/ru, optional requires_base) and the script files.
Unsigned packages are valid for local install (the app warns); --key signs as official.
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

SKIP_DIRS = {"__pycache__", ".git", "data", "config"}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("script_dir")
    ap.add_argument("--key")
    ap.add_argument("-o", "--output")
    a = ap.parse_args()
    meta_path = os.path.join(a.script_dir, "tgascript.json")
    m = json.load(open(meta_path, encoding="utf-8"))
    files = []
    for root, dirs, names in os.walk(a.script_dir):
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS and not d.startswith("."))
        for n in sorted(names):
            rel = os.path.relpath(os.path.join(root, n), a.script_dir).replace(os.sep, "/")
            if rel == "tgascript.json" or n.endswith((".pyc", ".session", ".session-journal")) or n.startswith("."):
                continue
            if n.startswith("test_") and n.endswith(".py"):
                continue
            if not c.safe_path(rel, c.SCRIPT_FORMAT):
                sys.exit(f"file type/path not allowed in a script package: {rel}")
            blob = open(os.path.join(root, n), "rb").read()
            files.append({"path": rel, "sha256": c.sha256_hex(blob), "size": len(blob)})
    m.update(format=c.SCRIPT_FORMAT, files=sorted(files, key=lambda f: f["path"]))
    m.setdefault("channel", "any")
    m["created"] = m.get("created") or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    c.validate_manifest(m)
    mb = c.canonical_manifest(m)
    out = a.output or f"{m['id']}-{m['version']}.tgascript"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(zipfile.ZipInfo(c.MANIFEST, (1980, 1, 1, 0, 0, 0)), mb, zipfile.ZIP_DEFLATED)
        if a.key:
            from cryptography.hazmat.primitives import serialization
            key = serialization.load_pem_private_key(open(a.key, "rb").read(), None)
            z.writestr(zipfile.ZipInfo(c.SIGNATURE, (1980, 1, 1, 0, 0, 0)),
                       base64.b64encode(key.sign(c.MAGIC_SCRIPT + mb)) + b"\n", zipfile.ZIP_DEFLATED)
        for f in m["files"]:
            zi = zipfile.ZipInfo(f["path"], (1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, open(os.path.join(a.script_dir, f["path"]), "rb").read())
    print(f"{out}  {os.path.getsize(out)} bytes, {len(files)} files, {'signed' if a.key else 'UNSIGNED'}")


if __name__ == "__main__":
    main()
