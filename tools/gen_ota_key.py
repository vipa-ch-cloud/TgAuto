#!/usr/bin/env python3
"""Generates an Ed25519 key pair for signing community OTA packages (.tgadyn).

  gen_ota_key.py --out ~/keys/my_ota.pem          writes the PRIVATE key (mode 600) outside the repo, prints the PUBLIC key
  gen_ota_key.py --out KEY.pem --print-private    additionally prints the private key (explicit opt-in, avoid)

The public key (hex) and its sha256 fingerprint are what you share with users: they add it in
Settings > Updates > Trusted community keys. The private key is never printed unless --print-private is given,
and the tool refuses to write it inside a git repository.
"""
import argparse
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ota_common as c


def inside_git_repo(path):
    d = os.path.dirname(os.path.abspath(path)) or "."
    try:
        return subprocess.run(["git", "-C", d, "rev-parse", "--is-inside-work-tree"], capture_output=True, text=True).stdout.strip() == "true"
    except OSError:
        return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="where to write the private key PEM (must not exist, must be outside any git repo)")
    ap.add_argument("--print-private", action="store_true", help="also print the private key PEM to stdout (dangerous)")
    ap.add_argument("--allow-in-repo", action="store_true", help=argparse.SUPPRESS)
    a = ap.parse_args()
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    if os.path.exists(a.out):
        sys.exit(f"{a.out} exists; refusing to overwrite")
    if inside_git_repo(a.out) and not a.allow_in_repo:
        sys.exit("refusing to write a private key inside a git repository; choose a path outside it")
    key = Ed25519PrivateKey.generate()
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    fd = os.open(a.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    pub = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    print(f"private key written to {a.out} (mode 600); keep it secret and back it up")
    print(f"public key (hex):  {pub.hex()}")
    print(f"fingerprint sha256: {c.key_fingerprint(pub)}")
    if a.print_private:
        print(pem.decode())


if __name__ == "__main__":
    main()
