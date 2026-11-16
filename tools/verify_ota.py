#!/usr/bin/env python3
"""Verifies a .tgadyn file the way the app does and prints its manifest.

  verify_ota.py FILE.tgadyn [--pub HEX_OR_FILE] [--base-name 1.9.0 --base-code 20 --flavor stable]
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ota_common as c

# Same keys as OtaKeys.PUBLIC_KEY_HEX / PUBLIC_KEY_2_HEX in the app: both are official.
APP_PUBLIC_KEY_HEX = "5597bc6a40e0c335f33d8cae795272a9dd053bf40a7fe2e6af2c48a2fa89f63b"
APP_PUBLIC_KEY_2_HEX = "611efe75540e246b15ebc095534e1bf44474d065e9b931b9ee6aa653c22f6015"
APP_PUBLIC_KEYS_HEX = (APP_PUBLIC_KEY_HEX, APP_PUBLIC_KEY_2_HEX)


def verify_official(fn, pub_hex_or_none=None):
    """Runs fn(raw_key) with each official key (or the one given) until a signature matches.
    Returns fn's result; re-raises the last bad_signature error if none matches."""
    keys = [pub_hex_or_none] if pub_hex_or_none else list(APP_PUBLIC_KEYS_HEX)
    last = None
    for k in keys:
        try:
            return fn(bytes.fromhex(k))
        except c.OtaError as e:
            if e.code != "bad_signature":
                raise
            last = e
    raise last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--pub", default=None, help="override the official key (default: both built-in official keys)")
    ap.add_argument("--base-name", default="1.9.0")
    ap.add_argument("--base-code", type=int, default=20)
    ap.add_argument("--trust", action="append", default=[], help="community public key (hex), may repeat")
    ap.add_argument("--flavor", default="stable", choices=("stable", "nightly"))
    a = ap.parse_args()
    pub = (open(a.pub).read().strip() if os.path.isfile(a.pub) else a.pub) if a.pub else None
    try:
        m, data, signed, signer = verify_official(lambda k: c.verify_package(
            a.file, k, a.base_name, a.base_code, a.flavor,
            extra_keys=[bytes.fromhex(x) for x in a.trust if bytes.fromhex(x) not in map(bytes.fromhex, APP_PUBLIC_KEYS_HEX)],
            want_signer=True), pub)
    except c.OtaError as e:
        print(f"REJECTED: {e}")
        sys.exit(1)
    print(f"OK  {m['name']} {m['version']} (build {m['build']}, channel {m['channel']}, {len(data)} files)")
    if signer is not None:
        print(f"COMMUNITY-signed (key sha256 {c.key_fingerprint(signer)}): not official, the app asks for explicit confirmation")
    print("requires_base:", m.get("requires_base"))
    print("changelog_en:", m.get("changelog_en", ""))
    print("changelog_ru:", m.get("changelog_ru", ""))


if __name__ == "__main__":
    main()
