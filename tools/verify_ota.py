#!/usr/bin/env python3
"""Verifies a .tgadyn file the way the app does and prints its manifest.

  verify_ota.py FILE.tgadyn [--pub HEX_OR_FILE] [--base-name 1.9.0 --base-code 20 --flavor stable]
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ota_common as c

# Same key as OtaKeys.PUBLIC_KEY_HEX in the app.
APP_PUBLIC_KEY_HEX = "5597bc6a40e0c335f33d8cae795272a9dd053bf40a7fe2e6af2c48a2fa89f63b"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--pub", default=APP_PUBLIC_KEY_HEX)
    ap.add_argument("--base-name", default="1.9.0")
    ap.add_argument("--base-code", type=int, default=20)
    ap.add_argument("--flavor", default="stable", choices=("stable", "nightly"))
    a = ap.parse_args()
    pub = open(a.pub).read().strip() if os.path.isfile(a.pub) else a.pub
    try:
        m, data, signed = c.verify_package(a.file, bytes.fromhex(pub), a.base_name, a.base_code, a.flavor)
    except c.OtaError as e:
        print(f"REJECTED: {e}")
        sys.exit(1)
    print(f"OK  {m['name']} {m['version']} (build {m['build']}, channel {m['channel']}, {len(data)} files)")
    print("requires_base:", m.get("requires_base"))
    print("changelog_en:", m.get("changelog_en", ""))
    print("changelog_ru:", m.get("changelog_ru", ""))


if __name__ == "__main__":
    main()
