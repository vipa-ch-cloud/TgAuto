"""Unit tests for the .tgadex format: python3 tools/test_tgadex.py (needs `cryptography`)."""
import base64
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ota_common as oc
import tgadex_common as c
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

DEX = b"dex\n035\x00" + b"\x00" * 100


def raw_pub(k):
    return k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def build(path, key, files=None, magic=c.MAGIC, sign=True, extra=None, tamper=None, **over):
    files = {"classes.dex": DEX} if files is None else files
    m = {"format": c.FORMAT, "id": "demo", "name": "t", "version": "1.0", "build": 1, "channel": "any",
         "minAppVersion": "1.9.3", "apiVersion": 1, "entry": "a.b.Plugin"}
    m.update(over)
    m["files"] = [{"path": p, "sha256": c.sha256_hex(b), "size": len(b)} for p, b in files.items()]
    mb = c.canonical_manifest(m)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", mb)
        if sign:
            z.writestr("signature.ed25519", base64.b64encode(key.sign(magic + mb)))
        for p, b in files.items():
            z.writestr(p, (tamper or {}).get(p, b))
        for p, b in (extra or {}).items():
            z.writestr(p, b)
    return mb


class TgadexTest(unittest.TestCase):
    def setUp(self):
        self.key = Ed25519PrivateKey.generate()
        self.pub = raw_pub(self.key)
        self.dir = tempfile.mkdtemp()
        self.f = os.path.join(self.dir, "a.tgadex")

    def verify(self, base="1.9.3", flavor="stable", **kw):
        return c.verify_package(self.f, self.pub, base, flavor, **kw)

    def code(self, **kw):
        with self.assertRaises(c.OtaError) as cm:
            self.verify(**kw)
        return cm.exception.code

    def test_ok(self):
        build(self.f, self.key)
        m, data = self.verify()
        self.assertEqual(data["classes.dex"], DEX)
        self.assertEqual(m["entry"], "a.b.Plugin")

    def test_multi_dex(self):
        build(self.f, self.key, {"classes.dex": DEX, "classes2.dex": DEX + b"1"})
        self.assertEqual(len(self.verify()[1]), 2)

    def test_wrong_key(self):
        build(self.f, Ed25519PrivateKey.generate())
        self.assertEqual(self.code(), "bad_signature")

    def test_community_key(self):
        comm = Ed25519PrivateKey.generate()
        build(self.f, comm)
        m, d, signer = self.verify(extra_keys=[raw_pub(comm)], want_signer=True)
        self.assertEqual(signer, raw_pub(comm))
        _, _, off = (lambda: (build(self.f, self.key), self.verify(want_signer=True))[1])()
        self.assertIsNone(off)

    def test_unsigned(self):
        build(self.f, self.key, sign=False)
        self.assertEqual(self.code(), "unsigned")

    def test_tampered(self):
        build(self.f, self.key, tamper={"classes.dex": DEX + b"x"})
        self.assertEqual(self.code(), "hash_mismatch")

    def test_unlisted_and_bad_path(self):
        build(self.f, self.key, extra={"evil.dex": DEX})
        self.assertEqual(self.code(), "unlisted_file")
        build(self.f, self.key, {"classes.dex": DEX, "../x.dex": DEX})
        self.assertEqual(self.code(), "bad_path")
        build(self.f, self.key, {"classes.dex": DEX, "python/x.py": b"1"})
        self.assertEqual(self.code(), "bad_path")

    def test_not_dex(self):
        build(self.f, self.key, {"classes.dex": b"PK\x03\x04junk"})
        self.assertEqual(self.code(), "bad_dex")

    def test_compat(self):
        build(self.f, self.key, minAppVersion="1.10.0")
        self.assertEqual(self.code(), "wrong_base")
        build(self.f, self.key, apiVersion=2)
        self.assertEqual(self.code(), "wrong_api")
        build(self.f, self.key, channel="nightly")
        self.assertEqual(self.code(), "wrong_channel")
        self.assertEqual(self.verify(flavor="nightly")[0]["channel"], "nightly")

    def test_bad_manifest(self):
        build(self.f, self.key, entry="not a class")
        self.assertEqual(self.code(), "bad_manifest")
        build(self.f, self.key, id="Bad Id")
        self.assertEqual(self.code(), "bad_manifest")
        build(self.f, self.key, {"classes2.dex": DEX})
        self.assertEqual(self.code(), "bad_manifest")

    # --- domain separation ---
    def test_signature_of_other_domains_rejected(self):
        for magic in (oc.MAGIC, oc.MAGIC_SCRIPT, oc.MAGIC_INDEX, b""):
            build(self.f, self.key, magic=magic)
            self.assertEqual(self.code(), "bad_signature", magic)

    def test_tgadex_not_valid_as_tgadyn(self):
        build(self.f, self.key)
        with self.assertRaises(oc.OtaError) as cm:
            oc.verify_package(self.f, self.pub, "1.9.3", 22, "stable")
        self.assertEqual(cm.exception.code, "bad_format")

    def test_tgadyn_not_valid_as_tgadex(self):
        m = {"format": oc.FORMAT, "name": "t", "version": "1.0", "build": 1, "channel": "any", "files": []}
        mb = oc.canonical_manifest(m)
        with zipfile.ZipFile(self.f, "w") as z:
            z.writestr("manifest.json", mb)
            z.writestr("signature.ed25519", base64.b64encode(self.key.sign(oc.MAGIC + mb)))
        self.assertEqual(self.code(), "bad_format")
        # Same manifest relabelled as tgadex while keeping the tgadyn signature: signature fails.
        m2 = dict(m, format=c.FORMAT)
        mb2 = oc.canonical_manifest(m2)
        with zipfile.ZipFile(self.f, "w") as z:
            z.writestr("manifest.json", mb2)
            z.writestr("signature.ed25519", base64.b64encode(self.key.sign(oc.MAGIC + mb2)))
        self.assertEqual(self.code(), "bad_signature")

    def test_zip_bomb_and_garbage(self):
        with open(self.f, "wb") as fh:
            fh.write(b"hello")
        self.assertEqual(self.code(), "not_zip")

    # --- tools ---
    def test_make_and_verify_cli(self):
        pem = os.path.join(self.dir, "k.pem")
        open(pem, "wb").write(self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                    serialization.NoEncryption()))
        dex = os.path.join(self.dir, "x.dex")
        open(dex, "wb").write(DEX)
        man = os.path.join(self.dir, "m.json")
        json.dump({"id": "demo", "name": "t", "version": "2.0", "build": 2, "minAppVersion": "1.9.3", "apiVersion": 1,
                   "entry": "a.b.Plugin"}, open(man, "w"))
        subprocess.run([sys.executable, os.path.join(HERE, "make_tgadex.py"), "--manifest", man, "--key", pem, "-o", self.f, dex],
                       check=True, capture_output=True)
        r = subprocess.run([sys.executable, os.path.join(HERE, "verify_tgadex.py"), self.f, "--pub", self.pub.hex()],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("official", r.stdout)
        r = subprocess.run([sys.executable, os.path.join(HERE, "verify_tgadex.py"), self.f],
                           capture_output=True, text=True)  # app key: must reject
        self.assertEqual(r.returncode, 1)
        self.assertIn("bad_signature", r.stdout)

    def test_sample_fixture_builds(self):
        dex = os.path.join(HERE, "tgadex_sample", "hello.dex")
        man = json.load(open(os.path.join(HERE, "tgadex_sample", "manifest.template.json")))
        blob = open(dex, "rb").read()
        self.assertEqual(blob[:4], b"dex\n")
        m = dict(man, format=c.FORMAT, files=[{"path": "classes.dex", "sha256": c.sha256_hex(blob), "size": len(blob)}])
        c.validate_manifest(m)


if __name__ == "__main__":
    unittest.main()
