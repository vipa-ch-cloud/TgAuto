#!/usr/bin/env python3
"""Sign remote hotfix flags for TgAuto (see docs/OTA-COVERAGE.md).

  make_flags.py flags.json --key KEY.pem [-o DIR]     writes DIR/flags.json and DIR/flags.json.sig
  make_flags.py --verify flags.json flags.json.sig --pub HEX

Attach both files to the release marked "latest". Signature: Ed25519 over b"TGAFLAGS1\\n" + exact bytes of flags.json,
base64 in flags.json.sig. The key is read from a file and never printed. "seq" must grow with every new flags file.
"""
import argparse, base64, json, os, shutil, sys

MAGIC = b"TGAFLAGS1\n"
KNOWN = {"seq", "ota_pause", "dex_pause", "apk_pause", "store_pause", "ota_kill", "dex_kill",
         "revoked_ota", "revoked_dex", "revoked_apk", "message_en", "message_ru"}


def check(data):
    j = json.loads(data)
    if not isinstance(j, dict) or not isinstance(j.get("seq"), int) or j["seq"] < 1:
        sys.exit("flags.json needs an integer seq >= 1")
    bad = set(j) - KNOWN
    if bad:
        sys.exit("unknown fields: " + ", ".join(sorted(bad)))
    if len(data) > 64 * 1024:
        sys.exit("flags.json too large")


def main():
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    from cryptography.hazmat.primitives import serialization
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--key")
    ap.add_argument("-o", "--out", default=".")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--pub", help="public key hex (verify mode)")
    a = ap.parse_args()
    if a.verify:
        data = open(a.files[0], "rb").read(); sig = base64.b64decode(open(a.files[1]).read().strip())
        Ed25519PublicKey.from_public_bytes(bytes.fromhex(a.pub)).verify(sig, MAGIC + data)
        check(data); print("OK"); return
    if not a.key:
        sys.exit("--key required")
    data = open(a.files[0], "rb").read(); check(data)
    priv = serialization.load_pem_private_key(open(os.path.expanduser(a.key), "rb").read(), None)
    sig = base64.b64encode(priv.sign(MAGIC + data)).decode()
    os.makedirs(a.out, exist_ok=True)
    if os.path.abspath(a.files[0]) != os.path.abspath(os.path.join(a.out, "flags.json")):
        shutil.copyfile(a.files[0], os.path.join(a.out, "flags.json"))
    open(os.path.join(a.out, "flags.json.sig"), "w").write(sig + "\n")
    print("written flags.json and flags.json.sig in", a.out)


main()
