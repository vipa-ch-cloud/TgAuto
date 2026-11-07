"""Shared constants and checks for the .tgadyn OTA format (no secrets here).

Container: a zip with manifest.json, signature.ed25519 and the payload files.
The signature is Ed25519 over MAGIC + the exact bytes of manifest.json, and the
manifest lists sha256 + size of every payload file. The app (OtaCore.kt) applies
the same rules; keep both in sync.
"""
import base64
import hashlib
import json
import re
import zipfile

FORMAT = "tgadyn/1"
SCRIPT_FORMAT = "tgascript/1"
INDEX_FORMAT = "tgastore/1"
MAGIC = b"TGADYN1\n"
MAGIC_SCRIPT = b"TGASCRIPT1\n"
MAGIC_INDEX = b"TGASTORE1\n"
SCRIPT_EXTS = {".py", ".json", ".md", ".txt", ".yaml", ".yml", ".toml", ".csv", ".ini", ".cfg"}
KINDS = {
    FORMAT: dict(magic=MAGIC, sig_required=True),
    SCRIPT_FORMAT: dict(magic=MAGIC_SCRIPT, sig_required=False),
}
MANIFEST = "manifest.json"
SIGNATURE = "signature.ed25519"
MAX_ENTRIES = 500
MAX_FILE = 8 * 1024 * 1024
MAX_TOTAL = 32 * 1024 * 1024
MAX_PACKAGE = 16 * 1024 * 1024
MAX_MANIFEST = 256 * 1024
MAX_RATIO = 200
CHANNELS = ("stable", "nightly", "any")
# prefix -> allowed extensions
PREFIXES = {
    "python/": {".py", ".json", ".txt"},
    "res/": {".json"},
    "schemas/": {".json"},
    "docs/": {".md", ".txt"},
}
RES_STRINGS = "res/strings.json"


class OtaError(Exception):
    def __init__(self, code, detail=""):
        super().__init__(f"{code}: {detail}" if detail else code)
        self.code = code
        self.detail = detail


def vtuple(v):
    try:
        return tuple(int(p) for p in re.findall(r"\d+", str(v).split("-")[0]))
    except ValueError:
        return ()


def vcmp(a, b):
    ta, tb = vtuple(a), vtuple(b)
    n = max(len(ta), len(tb))
    ta += (0,) * (n - len(ta))
    tb += (0,) * (n - len(tb))
    return (ta > tb) - (ta < tb)


def safe_path(path, fmt=FORMAT):
    """True for plain relative paths (OTA: under an allowed prefix) with an allowed extension."""
    if not isinstance(path, str) or not path or len(path) > 200:
        return False
    if path.startswith("/") or "\\" in path or ":" in path or "\x00" in path:
        return False
    parts = path.split("/")
    if any(p in ("", ".", "..") or p.startswith(".") for p in parts):
        return False
    if fmt == SCRIPT_FORMAT:
        if path in (MANIFEST, SIGNATURE):
            return False
        ext = "." + parts[-1].rsplit(".", 1)[-1].lower() if "." in parts[-1] else ""
        return ext in SCRIPT_EXTS
    for prefix, exts in PREFIXES.items():
        if path.startswith(prefix):
            ext = "." + parts[-1].rsplit(".", 1)[-1].lower() if "." in parts[-1] else ""
            ok = ext in exts
            if prefix == "res/":
                ok = ok and path == RES_STRINGS
            return ok
    return False


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def canonical_manifest(manifest):
    """Canonical bytes: sorted keys, compact, UTF-8, no ASCII escaping."""
    return json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def verify_index(index_bytes, sig_b64, public_key_raw):
    """True when the catalog index carries a valid owner signature."""
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    try:
        sig = base64.b64decode(sig_b64.strip(), validate=True)
        Ed25519PublicKey.from_public_bytes(public_key_raw).verify(sig, MAGIC_INDEX + index_bytes)
        return True
    except (InvalidSignature, ValueError):
        return False


def parse_range(r, what):
    if r is None:
        return None
    if not (isinstance(r, list) and len(r) == 2):
        raise OtaError("bad_manifest", what)
    return r


def check_base(manifest, base_name, base_code, flavor):
    """Raises OtaError('wrong_base' | 'wrong_channel') when the update does not fit this app."""
    ch = manifest.get("channel", "any")
    if ch not in CHANNELS:
        raise OtaError("bad_manifest", "channel")
    if ch != "any" and ch != flavor:
        raise OtaError("wrong_channel", ch)
    rb = manifest.get("requires_base") or {}
    vn = parse_range(rb.get("version_name"), "requires_base.version_name")
    if vn:
        lo, hi = vn
        if (lo and vcmp(base_name, lo) < 0) or (hi and vcmp(base_name, hi) > 0):
            raise OtaError("wrong_base", f"{lo}..{hi}")
    vc = parse_range(rb.get("version_code"), "requires_base.version_code")
    if vc:
        lo, hi = vc
        if (lo is not None and base_code < lo) or (hi is not None and base_code > hi):
            raise OtaError("wrong_base", f"code {lo}..{hi}")


def validate_manifest(m):
    if not isinstance(m, dict) or m.get("format") not in KINDS:
        raise OtaError("bad_format")
    fmt = m["format"]
    for key, typ in (("name", str), ("version", str), ("build", int), ("files", list)):
        if not isinstance(m.get(key), typ) or isinstance(m.get(key), bool):
            raise OtaError("bad_manifest", key)
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._+-]{0,39}", m["version"]):
        raise OtaError("bad_manifest", "version")
    if not 1 <= len(m["name"]) <= 80:
        raise OtaError("bad_manifest", "name")
    if len(m["files"]) > MAX_ENTRIES:
        raise OtaError("too_many")
    seen = set()
    for f in m["files"]:
        if not isinstance(f, dict) or not safe_path(f.get("path"), fmt):
            raise OtaError("bad_path", str(f.get("path") if isinstance(f, dict) else f)[:80])
        if f["path"] in seen:
            raise OtaError("bad_manifest", "duplicate " + f["path"])
        seen.add(f["path"])
        if not re.fullmatch(r"[0-9a-f]{64}", str(f.get("sha256", ""))):
            raise OtaError("bad_manifest", "sha256 " + f["path"])
        if not isinstance(f.get("size"), int) or f["size"] < 0 or f["size"] > MAX_FILE:
            raise OtaError("too_big", f["path"])
    if sum(f["size"] for f in m["files"]) > MAX_TOTAL:
        raise OtaError("too_big", "total")
    if fmt == SCRIPT_FORMAT:
        if not re.fullmatch(r"[a-z0-9_]{1,40}", str(m.get("id", ""))):
            raise OtaError("bad_manifest", "id")
        if m.get("entry") not in seen:
            raise OtaError("bad_manifest", "entry")
        if not isinstance(m.get("tags", []), list) or not isinstance(m.get("permissions", {}), dict):
            raise OtaError("bad_manifest", "tags/permissions")


def verify_package(path, public_key_raw, base_name, base_code, flavor, kind=None, extra_keys=(), want_signer=False):
    """Full check of a .tgadyn / .tgascript file. Returns (manifest, {path: bytes}, signed). Raises OtaError.

    .tgadyn must be signed; .tgascript may be unsigned (signed=False) but a present signature must be valid.
    extra_keys: raw community public keys tried after the official one (only for .tgadyn). With want_signer=True
    a 4th value is returned: None for the official key, else the matching community raw key."""
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

    import os
    if os.path.getsize(path) > MAX_PACKAGE:
        raise OtaError("too_big", "package")
    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile as e:
        raise OtaError("not_zip", str(e))
    with zf:
        infos = zf.infolist()
        if len(infos) > MAX_ENTRIES + 2:
            raise OtaError("too_many")
        names = [i.filename for i in infos]
        if len(set(names)) != len(names):
            raise OtaError("bad_manifest", "duplicate zip entry")
        total = 0
        for i in infos:
            if i.is_dir():
                continue
            if i.file_size > MAX_FILE or (i.compress_size and i.file_size / i.compress_size > MAX_RATIO and i.file_size > 64 * 1024):
                raise OtaError("zip_bomb", i.filename)
            total += i.file_size
        if total > MAX_TOTAL:
            raise OtaError("zip_bomb", "total")
        if MANIFEST not in names:
            raise OtaError("bad_manifest", "manifest")
        mi = zf.getinfo(MANIFEST)
        if mi.file_size > MAX_MANIFEST:
            raise OtaError("too_big", "manifest")
        manifest_bytes = zf.read(MANIFEST)
        try:
            manifest = json.loads(manifest_bytes.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise OtaError("bad_manifest", "json")
        fmt = manifest.get("format") if isinstance(manifest, dict) else None
        if fmt not in KINDS or (kind and kind != fmt):
            raise OtaError("bad_format")
        signer = None
        signed = SIGNATURE in names
        if not signed and KINDS[fmt]["sig_required"]:
            raise OtaError("unsigned")
        if signed:
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
            msg = KINDS[fmt]["magic"] + manifest_bytes
            if not _ok(public_key_raw, sig, msg):
                signer = next((k for k in extra_keys if k != public_key_raw and fmt == FORMAT and _ok(k, sig, msg)), None)
                if signer is None:
                    raise OtaError("bad_signature")
        validate_manifest(manifest)
        check_base(manifest, base_name, base_code, flavor)
        listed = {f["path"]: f for f in manifest["files"]}
        extra = [n for n in names if n not in listed and n not in (MANIFEST, SIGNATURE) and not n.endswith("/")]
        if extra:
            raise OtaError("unlisted_file", extra[0][:80])
        data = {}
        for path_, f in listed.items():
            if path_ not in names:
                raise OtaError("missing_file", path_)
            blob = zf.read(path_)
            if len(blob) != f["size"] or sha256_hex(blob) != f["sha256"]:
                raise OtaError("hash_mismatch", path_)
            data[path_] = blob
        if RES_STRINGS in data:
            try:
                json.loads(data[RES_STRINGS].decode("utf-8"))
            except ValueError:
                raise OtaError("bad_manifest", RES_STRINGS)
    return (manifest, data, signed, signer) if want_signer else (manifest, data, signed)


def key_fingerprint(raw):
    """sha256 of a raw Ed25519 public key (shown in the app), lowercase hex."""
    return sha256_hex(raw)
