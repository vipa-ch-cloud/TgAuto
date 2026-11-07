"""Unit tests for the .tgadyn format: python3 tools/test_ota.py (needs `cryptography`)."""
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
import ota_common as c
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def raw_pub(k):
    return k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def build(path, key, files, **over):
    m = {"format": c.FORMAT, "name": "t", "version": "1.0", "build": 1, "channel": "any",
         "requires_base": {"version_name": ["1.9.0", "1.9.99"], "version_code": [20, None]}}
    m.update(over)
    m["files"] = [{"path": p, "sha256": c.sha256_hex(b), "size": len(b)} for p, b in files.items()]
    mb = c.canonical_manifest(m)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", mb)
        z.writestr("signature.ed25519", base64.b64encode(key.sign(c.MAGIC + mb)))
        for p, b in files.items():
            z.writestr(p, b)
    return mb


class OtaTest(unittest.TestCase):
    def setUp(self):
        self.key = Ed25519PrivateKey.generate()
        self.pub = raw_pub(self.key)
        self.dir = tempfile.mkdtemp()
        self.f = os.path.join(self.dir, "a.tgadyn")

    def verify(self, **kw):
        a = dict(base_name="1.9.0", base_code=20, flavor="stable")
        a.update(kw)
        return c.verify_package(self.f, self.pub, a["base_name"], a["base_code"], a["flavor"])

    def code(self, **kw):
        with self.assertRaises(c.OtaError) as cm:
            self.verify(**kw)
        return cm.exception.code

    def test_ok(self):
        build(self.f, self.key, {"python/x.py": b"A = 1\n"})
        m, data, signed = self.verify()
        self.assertEqual(data["python/x.py"], b"A = 1\n")

    def test_wrong_key(self):
        build(self.f, Ed25519PrivateKey.generate(), {"python/x.py": b"1"})
        self.assertEqual(self.code(), "bad_signature")

    def test_tampered_payload(self):
        build(self.f, self.key, {"python/x.py": b"A = 1\n"})
        self._rewrite({"python/x.py": b"A = 2\n"})
        self.assertEqual(self.code(), "hash_mismatch")

    def test_tampered_manifest(self):
        build(self.f, self.key, {"python/x.py": b"1"})
        with zipfile.ZipFile(self.f) as z:
            items = {n: z.read(n) for n in z.namelist()}
        items["manifest.json"] = items["manifest.json"].replace(b'"1.0"', b'"9.9"')
        self._write(items)
        self.assertEqual(self.code(), "bad_signature")

    def test_unsigned(self):
        build(self.f, self.key, {"python/x.py": b"1"})
        with zipfile.ZipFile(self.f) as z:
            items = {n: z.read(n) for n in z.namelist() if n != "signature.ed25519"}
        self._write(items)
        self.assertEqual(self.code(), "unsigned")

    def test_wrong_base(self):
        build(self.f, self.key, {"python/x.py": b"1"})
        self.assertEqual(self.code(base_name="1.8.1"), "wrong_base")
        self.assertEqual(self.code(base_name="2.0.0"), "wrong_base")
        self.assertEqual(self.code(base_code=19), "wrong_base")

    def test_channel(self):
        build(self.f, self.key, {"python/x.py": b"1"}, channel="nightly")
        self.assertEqual(self.code(flavor="stable"), "wrong_channel")
        self.verify(flavor="nightly")

    def test_traversal_and_bad_paths(self):
        for p in ("python/../x.py", "/etc/x.py", "python/.hidden.py", "python\\x.py", "other/x.py", "python/x.exe", "res/other.json"):
            build(self.f, self.key, {p: b"1"})
            self.assertEqual(self.code(), "bad_path", p)

    def test_unlisted_and_missing(self):
        build(self.f, self.key, {"python/x.py": b"1"})
        with zipfile.ZipFile(self.f) as z:
            items = {n: z.read(n) for n in z.namelist()}
        items["python/evil.py"] = b"import os"
        self._write(items)
        self.assertEqual(self.code(), "unlisted_file")
        del items["python/evil.py"], items["python/x.py"]
        self._write(items)
        self.assertEqual(self.code(), "missing_file")

    def test_zip_bomb(self):
        build(self.f, self.key, {"python/x.py": b"0" * (6 * 1024 * 1024)})
        self.assertEqual(self.code(), "zip_bomb")

    def test_not_zip(self):
        open(self.f, "wb").write(b"hello")
        self.assertEqual(self.code(), "not_zip")

    def test_version_compare(self):
        self.assertEqual(c.vcmp("1.9.0", "1.9"), 0)
        self.assertEqual(c.vcmp("1.10.0", "1.9.9"), 1)
        self.assertEqual(c.vcmp("0.0.1-test", "0.0.2"), -1)

    def test_community_key(self):
        other = Ed25519PrivateKey.generate()
        build(self.f, other, {"python/x.py": b"1"})
        self.assertEqual(self.code(), "bad_signature")  # not trusted by default
        r = c.verify_package(self.f, self.pub, "1.9.0", 20, "stable", extra_keys=[raw_pub(other)], want_signer=True)
        self.assertEqual(r[3], raw_pub(other))
        r = c.verify_package(self.f, self.pub, "1.9.0", 20, "stable", extra_keys=[raw_pub(Ed25519PrivateKey.generate())], want_signer=True) \
            if False else None
        build(self.f, self.key, {"python/x.py": b"1"})
        r = c.verify_package(self.f, self.pub, "1.9.0", 20, "stable", extra_keys=[raw_pub(other)], want_signer=True)
        self.assertIsNone(r[3])  # official signer wins

    def test_gen_key_tool(self):
        out = os.path.join(self.dir, "g.pem")
        r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "gen_ota_key.py"), "--out", out], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("PRIVATE", r.stdout)
        self.assertEqual(oct(os.stat(out).st_mode & 0o777), "0o600")
        self.assertEqual(subprocess.run([sys.executable, "-I", os.path.join(HERE, "gen_ota_key.py"), "--out", out], capture_output=True).returncode, 1)

    def test_tools_roundtrip(self):
        pem = os.path.join(self.dir, "k.pem")
        open(pem, "wb").write(self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        out = os.path.join(self.dir, "s.tgadyn")
        sample = os.path.join(HERE, "ota_sample")
        subprocess.run([sys.executable, "-I", os.path.join(HERE, "make_ota.py"), os.path.join(sample, "payload"),
                        "--template", os.path.join(sample, "manifest.template.json"), "--key", pem, "-o", out], check=True, capture_output=True)
        pub = os.path.join(self.dir, "pub.hex")
        open(pub, "w").write(self.pub.hex())
        r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "verify_ota.py"), out, "--pub", pub], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "verify_ota.py"), out, "--pub", pub, "--base-name", "1.8.1"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)

    def _script(self, key=None, **over):
        files = {"demo.py": b"print(1)\n", "sub/x.json": b"{}"}
        m = {"format": c.SCRIPT_FORMAT, "id": "demo", "name": "Demo", "version": "1.0", "build": 1, "entry": "demo.py",
             "permissions": {"network": []}, "tags": ["x"]}
        m.update(over)
        m["files"] = [{"path": p, "sha256": c.sha256_hex(b), "size": len(b)} for p, b in files.items()]
        mb = c.canonical_manifest(m)
        with zipfile.ZipFile(self.f, "w") as z:
            z.writestr("manifest.json", mb)
            if key:
                z.writestr("signature.ed25519", base64.b64encode(key.sign(c.MAGIC_SCRIPT + mb)))
            for p, b in files.items():
                z.writestr(p, b)

    def test_script_unsigned_ok(self):
        self._script()
        m, d, signed = self.verify()
        self.assertFalse(signed)

    def test_script_signed(self):
        self._script(self.key)
        self.assertTrue(self.verify()[2])
        self._script(Ed25519PrivateKey.generate())
        self.assertEqual(self.code(), "bad_signature")

    def test_script_bad(self):
        self._script(entry="missing.py")
        self.assertEqual(self.code(), "bad_manifest")
        self._script(id="../x")
        self.assertEqual(self.code(), "bad_manifest")

    def test_script_kind_mismatch(self):
        self._script()
        with self.assertRaises(c.OtaError):
            c.verify_package(self.f, self.pub, "1.9.0", 20, "stable", kind=c.FORMAT)

    def test_index_sig(self):
        data = b'{"a":1}'
        sig = base64.b64encode(self.key.sign(c.MAGIC_INDEX + data))
        self.assertTrue(c.verify_index(data, sig, self.pub))
        self.assertFalse(c.verify_index(data + b" ", sig, self.pub))

    def test_store_tools(self):
        pem = os.path.join(self.dir, "k.pem")
        with open(pem, "wb") as fh:
            fh.write(self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        src = os.path.join(self.dir, "demo")
        os.makedirs(src)
        with open(os.path.join(src, "demo.py"), "w") as fh:
            fh.write("print(1)\n")
        with open(os.path.join(src, "tgascript.json"), "w") as fh:
            json.dump({"id": "demo", "name": "Demo", "version": "1.2", "build": 1, "entry": "demo.py", "tags": ["t"], "permissions": {},
                       "title_ru": "Демо", "title_en": "Demo app", "icon": "bell", "color": "#112233",
                       "repo": "https://github.com/a/b", "published": "2026-11-07", "homepage": "https://a.b"}, fh)
        pk = os.path.join(self.dir, "pk")
        os.makedirs(pk)
        py = [sys.executable, "-I"]
        subprocess.run(py + [os.path.join(HERE, "make_script_pkg.py"), src, "--key", pem, "-o", os.path.join(pk, "demo-1.2.tgascript")], check=True, capture_output=True)
        subprocess.run(py + [os.path.join(HERE, "make_store_index.py"), pk, "--base-url", "https://x/y", "--key", pem, "-o", pk], check=True, capture_output=True)
        idx = open(os.path.join(pk, "index.json"), "rb").read()
        self.assertTrue(c.verify_index(idx, open(os.path.join(pk, "index.json.sig"), "rb").read(), self.pub))
        e = json.loads(idx)["scripts"][0]
        self.assertEqual(e["url"], "https://x/y/demo-1.2.tgascript")
        self.assertEqual((e["title_ru"], e["icon"], e["color"], e["repo"], e["published"], e["homepage"]),
                         ("Демо", "bell", "#112233", "https://github.com/a/b", "2026-11-07", "https://a.b"))

    def _write(self, items):
        with zipfile.ZipFile(self.f, "w", zipfile.ZIP_DEFLATED) as z:
            for n, b in items.items():
                z.writestr(n, b)

    def _rewrite(self, repl):
        with zipfile.ZipFile(self.f) as z:
            items = {n: z.read(n) for n in z.namelist()}
        items.update(repl)
        self._write(items)


if __name__ == "__main__":
    unittest.main()
