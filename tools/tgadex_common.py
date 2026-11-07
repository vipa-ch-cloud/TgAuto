"""Shared rules for the .tgadex dynamic code/UI package (no secrets here).

A .tgadex is a zip: manifest.json, signature.ed25519 and classes.dex (classes2.dex ...).
The signature is Ed25519 over MAGIC ("TGADEX1\\n") + the exact manifest bytes, so a signature made for
.tgadyn / .tgascript / the store index never validates here and vice versa. The manifest lists sha256 + size
of every dex. The app (DexCore.kt) applies the same rules; keep both in sync.
"""
import base64
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ota_common as oc
from ota_common import OtaError, canonical_manifest, key_fingerprint, sha256_hex, vcmp  # noqa: F401

FORMAT = "tgadex/1"
MAGIC = b"TGADEX1\n"
MANIFEST = "manifest.json"
SIGNATURE = "signature.ed25519"
API_VERSION = 1          # plugin API implemented by this app (com.v1pach.telescript.plugin)
MIN_API_VERSION = 1
MAX_DEX_FILES = 8
MAX_FILE = 8 * 1024 * 1024
MAX_TOTAL = 16 * 1024 * 1024
MAX_PACKAGE = 12 * 1024 * 1024
MAX_MANIFEST = 64 * 1024
CHANNELS = ("stable", "nightly", "any")
DEX_RE = re.compile(r"classes(?:[2-9])?\.dex")
CLASS_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_$]*)+")


def safe_path(p):
    return isinstance(p, str) and DEX_RE.fullmatch(p) is not None


def validate_manifest(m):
    if not isinstance(m, dict) or m.get("format") != FORMAT:
        raise OtaError("bad_format")
    for key, typ in (("name", str), ("version", str), ("id", str), ("minAppVersion", str), ("entry", str),
                     ("apiVersion", int), ("build", int), ("files", list)):
        if not isinstance(m.get(key), typ) or isinstance(m.get(key), bool):
            raise OtaError("bad_manifest", key)
    if not re.fullmatch(r"[a-z0-9_]{1,40}", m["id"]):
        raise OtaError("bad_manifest", "id")
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._+-]{0,39}", m["version"]):
        raise OtaError("bad_manifest", "version")
    if not re.fullmatch(r"\d+(\.\d+){0,3}", m["minAppVersion"]):
        raise OtaError("bad_manifest", "minAppVersion")
    if not 1 <= len(m["name"]) <= 80:
        raise OtaError("bad_manifest", "name")
    if not CLASS_RE.fullmatch(m["entry"]) or len(m["entry"]) > 200:
        raise OtaError("bad_manifest", "entry")
    if m.get("channel", "any") not in CHANNELS:
        raise OtaError("bad_manifest", "channel")
    files = m["files"]
    if not 1 <= len(files) <= MAX_DEX_FILES:
        raise OtaError("too_many")
    seen = set()
    for f in files:
        if not isinstance(f, dict) or not safe_path(f.get("path")):
            raise OtaError("bad_path", str(f.get("path") if isinstance(f, dict) else f)[:80])
        if f["path"] in seen:
            raise OtaError("bad_manifest", "duplicate " + f["path"])
        seen.add(f["path"])
        if not re.fullmatch(r"[0-9a-f]{64}", str(f.get("sha256", ""))):
            raise OtaError("bad_manifest", "sha256 " + f["path"])
        if not isinstance(f.get("size"), int) or isinstance(f.get("size"), bool) or not 0 < f["size"] <= MAX_FILE:
            raise OtaError("too_big", f["path"])
    if "classes.dex" not in seen:
        raise OtaError("bad_manifest", "classes.dex")
    if sum(f["size"] for f in files) > MAX_TOTAL:
        raise OtaError("too_big", "total")


def check_compat(m, base_name, flavor):
    """Raises 'wrong_base' (app too old / API unsupported) or 'wrong_channel'."""
    ch = m.get("channel", "any")
    if ch != "any" and ch != flavor:
        raise OtaError("wrong_channel", ch)
    if vcmp(base_name, m["minAppVersion"]) < 0:
        raise OtaError("wrong_base", "minAppVersion " + m["minAppVersion"])
    if not MIN_API_VERSION <= m["apiVersion"] <= API_VERSION:
        raise OtaError("wrong_api", str(m["apiVersion"]))


def verify_package(path, public_key_raw, base_name, flavor, extra_keys=(), want_signer=False):
    """Returns (manifest, {path: bytes}) or with want_signer (manifest, data, signer) where signer is None for
    the official key, else the matching community raw key. Raises OtaError."""
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

    if os.path.getsize(path) > MAX_PACKAGE:
        raise OtaError("too_big", "package")
    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile as e:
        raise OtaError("not_zip", str(e))
    with zf:
        infos = zf.infolist()
        if len(infos) > MAX_DEX_FILES + 2:
            raise OtaError("too_many")
        names = [i.filename for i in infos]
        if len(set(names)) != len(names):
            raise OtaError("bad_manifest", "duplicate zip entry")
        total = 0
        for i in infos:
            if i.is_dir():
                continue
            if i.file_size > MAX_FILE or (i.compress_size and i.file_size / i.compress_size > 200 and i.file_size > 64 * 1024 * 8):
                raise OtaError("zip_bomb", i.filename)
            total += i.file_size
        if total > MAX_TOTAL:
            raise OtaError("zip_bomb", "total")
        if MANIFEST not in names:
            raise OtaError("bad_manifest", "manifest")
        if zf.getinfo(MANIFEST).file_size > MAX_MANIFEST:
            raise OtaError("too_big", "manifest")
        mb = zf.read(MANIFEST)
        try:
            import json
            manifest = json.loads(mb.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise OtaError("bad_manifest", "json")
        if not isinstance(manifest, dict) or manifest.get("format") != FORMAT:
            raise OtaError("bad_format")
        if SIGNATURE not in names:
            raise OtaError("unsigned")

        def _ok(key, sig, msg):
            try:
                Ed25519PublicKey.from_public_bytes(key).verify(sig, msg)
                return True
            except (InvalidSignature, ValueError):
                return False
        try:
            sig = base64.b64decode(zf.read(SIGNATURE)[:512].strip(), validate=True)
        except ValueError:
            raise OtaError("bad_signature")
        msg = MAGIC + mb
        signer = None
        if not _ok(public_key_raw, sig, msg):
            signer = next((k for k in extra_keys if k != public_key_raw and _ok(k, sig, msg)), None)
            if signer is None:
                raise OtaError("bad_signature")
        validate_manifest(manifest)
        check_compat(manifest, base_name, flavor)
        listed = {f["path"]: f for f in manifest["files"]}
        extra = [n for n in names if n not in listed and n not in (MANIFEST, SIGNATURE)]
        if extra:
            raise OtaError("unlisted_file", extra[0][:80])
        data = {}
        for p, f in listed.items():
            if p not in names:
                raise OtaError("missing_file", p)
            blob = zf.read(p)
            if len(blob) != f["size"] or sha256_hex(blob) != f["sha256"]:
                raise OtaError("hash_mismatch", p)
            if blob[:4] != b"dex\n":
                raise OtaError("bad_dex", p)
            data[p] = blob
    return (manifest, data, signer) if want_signer else (manifest, data)
