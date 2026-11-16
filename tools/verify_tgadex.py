#!/usr/bin/env python3
"""Verifies a .tgadex file the way the app does and prints its manifest.

  verify_tgadex.py FILE.tgadex [--pub HEX_OR_FILE] [--trust HEX ...] [--base-name 1.9.3] [--flavor stable]
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tgadex_common as c
from verify_ota import APP_PUBLIC_KEYS_HEX, verify_official


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--pub", default=None, help="override the official key (default: both built-in official keys)")
    ap.add_argument("--trust", action="append", default=[], help="community public key (hex), may repeat")
    ap.add_argument("--base-name", default="1.9.3")
    ap.add_argument("--flavor", default="stable", choices=("stable", "nightly"))
    a = ap.parse_args()
    pub = (open(a.pub).read().strip() if os.path.isfile(a.pub) else a.pub) if a.pub else None
    try:
        m, data, signer = verify_official(lambda k: c.verify_package(
            a.file, k, a.base_name, a.flavor,
            extra_keys=[bytes.fromhex(x) for x in a.trust if bytes.fromhex(x) not in map(bytes.fromhex, APP_PUBLIC_KEYS_HEX)],
            want_signer=True), pub)
    except c.OtaError as e:
        print(f"REJECTED: {e}")
        sys.exit(1)
    print(f"OK  {m['name']} ({m['id']}) {m['version']} build {m['build']}, entry {m['entry']}, "
          f"api {m['apiVersion']}, minApp {m['minAppVersion']}, channel {m.get('channel', 'any')}, {len(data)} dex")
    if signer is not None:
        print(f"COMMUNITY-signed (key sha256 {c.key_fingerprint(signer)}): NOT official, manual install with warning only")
    else:
        print("signed by the official key")
    print("changelog_en:", m.get("changelog_en", ""))


if __name__ == "__main__":
    main()
