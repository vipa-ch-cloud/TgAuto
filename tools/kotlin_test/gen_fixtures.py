"""Writes signed/tampered fixtures for OtaCoreTest.kt into DIR and the test public key into DIR/pub.hex."""
import base64, os, sys, zipfile
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import ota_common as c
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

d = sys.argv[1]
os.makedirs(d, exist_ok=True)
key = Ed25519PrivateKey.generate()
open(f"{d}/pub.hex", "w").write(key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex())


def build(name, files, fmt=c.FORMAT, signer=key, sign=True, extra_zip=None, tamper=None, **over):
    m = {"format": fmt, "name": "t", "version": "1.0", "build": 1, "channel": "any",
         "requires_base": {"version_name": ["1.9.0", "1.9.99"], "version_code": [20, None]}}
    if fmt == c.SCRIPT_FORMAT:
        m.update(id="demo", entry="demo.py")
    m.update(over)
    m["files"] = [{"path": p, "sha256": c.sha256_hex(b), "size": len(b)} for p, b in files.items()]
    mb = c.canonical_manifest(m)
    magic = c.MAGIC if fmt == c.FORMAT else c.MAGIC_SCRIPT
    with zipfile.ZipFile(f"{d}/{name}", "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", mb)
        if sign:
            z.writestr("signature.ed25519", base64.b64encode(signer.sign(magic + mb)))
        for p, b in files.items():
            z.writestr(p, (tamper or {}).get(p, b))
        for p, b in (extra_zip or {}).items():
            z.writestr(p, b)


ok = {"python/x.py": b"A = 1\n", "res/strings.json": b'{"en":{"a":"b"}}'}
build("ok.tgadyn", ok)
build("tampered.tgadyn", ok, tamper={"python/x.py": b"A = 2\n"})
build("wrongkey.tgadyn", ok, signer=Ed25519PrivateKey.generate())
build("unsigned.tgadyn", ok, sign=False)
build("wrongbase.tgadyn", ok, requires_base={"version_name": ["2.0.0", None]})
build("nightly.tgadyn", ok, channel="nightly")
build("traversal.tgadyn", {"python/../evil.py": b"1"})
build("unlisted.tgadyn", ok, extra_zip={"python/evil.py": b"1"})
build("bomb.tgadyn", {"python/x.py": b"0" * (6 * 1024 * 1024)})
build("ok.tgascript", {"demo.py": b"print(1)\n"}, fmt=c.SCRIPT_FORMAT, sign=False)
build("signed.tgascript", {"demo.py": b"print(1)\n"}, fmt=c.SCRIPT_FORMAT)
build("badsig.tgascript", {"demo.py": b"print(1)\n"}, fmt=c.SCRIPT_FORMAT, signer=Ed25519PrivateKey.generate())
open(f"{d}/notzip.tgadyn", "wb").write(b"hello")
idx = b'{"format":"tgastore/1","scripts":[]}'
open(f"{d}/index.json", "wb").write(idx)
open(f"{d}/index.json.sig", "wb").write(base64.b64encode(key.sign(c.MAGIC_INDEX + idx)))
