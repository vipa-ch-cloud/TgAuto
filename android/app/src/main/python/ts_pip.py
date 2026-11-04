"""Tiny runtime package installer for TgAuto (Chaquopy has no pip at runtime).

Resolves requirements through the PyPI JSON API (pure-Python ``py3-none-any``
wheels) and, for packages with native code, Chaquopy's Android wheel index.
Wheels are verified by sha256 and unpacked into a private site-packages dir that
is put at the front of sys.path before each script starts.

Every public function returns JSON (for the Kotlin side). Progress goes to the
``log`` callback, which defaults to print().
"""
import hashlib
import importlib
import importlib.metadata
import io
import json
import os
import pathlib
import re
import shutil
import sys
import zipfile

PYPI = "https://pypi.org/pypi/{name}/json"
CHAQUO = "https://chaquo.com/pypi-13.1/{name}/"
TIMEOUT = 60

# import name -> PyPI project name, for "ModuleNotFoundError: No module named X"
IMPORT_TO_PYPI = {
    "yaml": "PyYAML", "PIL": "Pillow", "bs4": "beautifulsoup4", "cv2": "opencv-python",
    "sklearn": "scikit-learn", "dateutil": "python-dateutil", "dotenv": "python-dotenv",
    "Crypto": "pycryptodome", "Cryptodome": "pycryptodomex", "jwt": "PyJWT", "magic": "python-magic",
    "socks": "PySocks", "python_socks": "python-socks", "serial": "pyserial", "usb": "pyusb",
    "OpenSSL": "pyOpenSSL", "attr": "attrs", "google.protobuf": "protobuf", "lxml": "lxml",
    "docx": "python-docx", "pptx": "python-pptx", "telegram": "python-telegram-bot",
    "pyrogram": "pyrogram", "aiogram": "aiogram", "fitz": "PyMuPDF", "Levenshtein": "python-Levenshtein",
    "dns": "dnspython", "slugify": "python-slugify", "multipart": "python-multipart",
    "markdown": "Markdown", "jose": "python-jose", "nacl": "PyNaCl", "zmq": "pyzmq",
    "websocket": "websocket-client", "tgcrypto": "TgCrypto", "cryptg": "cryptg",
    "anthropic": "anthropic", "openai": "openai", "requests": "requests", "aiohttp": "aiohttp",
}

_REQ = re.compile(r"^\s*([A-Za-z0-9][A-Za-z0-9._-]*)\s*(\[[^\]]*\])?\s*\(?([^;()]*)\)?\s*(?:;(.*))?$")


def _norm(name):
    return re.sub(r"[-_.]+", "-", name).lower()


# ------------------------------------------------------------------ versions

def _vkey(version):
    """Sortable key for PEP 440-ish versions; pre-releases sort before releases."""
    v = version.lower().split("+")[0]
    main = re.match(r"^v?(\d+(?:\.\d+)*)(.*)$", v)
    if not main:
        return ((0,), 0, v)
    nums = tuple(int(x) for x in main.group(1).split("."))
    while len(nums) > 1 and nums[-1] == 0:
        nums = nums[:-1]
    rest = main.group(2)
    pre = 0 if re.search(r"(a|b|c|rc|alpha|beta|pre|dev)\d*", rest) else 1
    return (nums, pre, rest)


def _is_pre(version):
    return _vkey(version)[1] == 0


def _satisfies(version, spec):
    spec = (spec or "").strip()
    if not spec:
        return True
    key = _vkey(version)
    for part in spec.split(","):
        part = part.strip()
        m = re.match(r"^(~=|===|==|!=|<=|>=|<|>)\s*(.+)$", part)
        if not m:
            continue
        op, target = m.group(1), m.group(2).strip()
        if op in ("==", "===", "!=") and target.endswith(".*"):
            prefix = _vkey(target[:-2])[0]
            match = key[0][:len(prefix)] == prefix
            if (op == "!=") == match:
                return False
            continue
        t = _vkey(target)
        ok = {"==": key[0] == t[0] and key[1:] == t[1:] or key[0] == t[0] and t[2] == "",
              "===": version == target, "!=": key != t,
              "<=": key <= t, ">=": key >= t, "<": key < t, ">": key > t}.get(op)
        if op == "~=":
            nums = _vkey(target)[0]
            ok = key >= t and key[0][:max(1, len(nums) - 1)] == nums[:max(1, len(nums) - 1)]
        if not ok:
            return False
    return True


# ------------------------------------------------------------------ markers

def _env():
    impl = sys.implementation.name
    return {
        "python_version": "%d.%d" % sys.version_info[:2],
        "python_full_version": "%d.%d.%d" % sys.version_info[:3],
        "sys_platform": sys.platform, "platform_system": "Linux" if sys.platform != "win32" else "Windows",
        "os_name": os.name, "implementation_name": impl, "platform_python_implementation":
            "CPython" if impl == "cpython" else impl, "platform_machine": os.uname().machine,
        "extra": "",
    }


def _marker_ok(marker, extras=()):
    """Simple PEP 508 marker evaluation: and/or of ``var op 'value'`` atoms, no parentheses nesting."""
    marker = (marker or "").strip()
    if not marker:
        return True
    env = _env()
    marker = marker.replace("(", " ").replace(")", " ")

    def atom(text):
        m = re.match(r"""^\s*(\w+|'[^']*'|"[^"]*")\s*(==|!=|<=|>=|<|>|~=|not in|in)\s*(\w+|'[^']*'|"[^"]*")\s*$""", text)
        if not m:
            return True
        left, op, right = m.groups()

        def val(token):
            if token[0] in "'\"":
                return token[1:-1], False
            return env.get(token, token), token == "extra"
        lv, l_extra = val(left)
        rv, r_extra = val(right)
        if l_extra or r_extra:  # extra == 'x': only when that extra was requested
            wanted = rv if l_extra else lv
            hit = _norm(wanted) in {_norm(e) for e in extras}
            return hit if op == "==" else not hit
        if op == "in":
            return lv in rv
        if op == "not in":
            return lv not in rv
        if re.match(r"^[\d.]+$", lv) and re.match(r"^[\d.*]+$", rv):
            return _satisfies(lv, op + rv)
        return {"==": lv == rv, "!=": lv != rv, "<": lv < rv, ">": lv > rv,
                "<=": lv <= rv, ">=": lv >= rv}.get(op, True)

    return any(all(atom(a) for a in re.split(r"\s+and\s+", branch))
               for branch in re.split(r"\s+or\s+", marker))


def parse_requirement(line):
    """'pkg[extra]>=1.0; marker' -> (name, extras, spec, marker) or None."""
    line = line.split(" #")[0].strip()
    if not line or line.startswith(("#", "-")) or "://" in line:
        return None
    m = _REQ.match(line)
    if not m:
        return None
    extras = tuple(e.strip() for e in (m.group(2) or "")[1:-1].split(",") if e.strip())
    return m.group(1), extras, (m.group(3) or "").strip(), (m.group(4) or "").strip()


# ------------------------------------------------------------------ network

def _get(url, as_json=False):
    try:
        import httpx  # bundled in the app; certifi CA bundle works on Android
        with httpx.Client(timeout=TIMEOUT, follow_redirects=True,
                          headers={"User-Agent": "TgAuto/1.0"}) as client:
            response = client.get(url)
            if response.status_code == 404:
                return None
            response.raise_for_status()
            return response.json() if as_json else response.content
    except ImportError:
        import urllib.error
        import urllib.request
        try:
            with urllib.request.urlopen(urllib.request.Request(
                    url, headers={"User-Agent": "TgAuto/1.0"}), timeout=TIMEOUT) as r:
                data = r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise
        return json.loads(data) if as_json else data


# ------------------------------------------------------------------ installed state

def _installed_version(name, target):
    """Version of an installed distribution (app-bundled or user-installed), else None."""
    want = _norm(name)
    paths = [target] + [p for p in sys.path if p != target]
    for dist in importlib.metadata.distributions(path=paths):
        try:
            if _norm(dist.metadata["Name"] or "") == want:
                return dist.version
        except Exception:
            continue
    return None


def _python_tag():
    return "cp%d%d" % sys.version_info[:2]


def _android_abi_tag(abi):
    return (abi or "arm64-v8a").replace("-", "_")


# ------------------------------------------------------------------ resolving

def _pick_pypi(name, spec, log):
    data = _get(PYPI.format(name=name), as_json=True)
    if data is None:
        return None, "нет такого пакета на PyPI"
    real_name = data.get("info", {}).get("name", name)
    candidates = []
    for version, files in data.get("releases", {}).items():
        if not _satisfies(version, spec):
            continue
        for f in files:
            fn = f.get("filename", "")
            if f.get("yanked") or f.get("packagetype") != "bdist_wheel":
                continue
            tags = fn[:-4].split("-")[-3:]
            pys, abi, plat = tags
            if plat == "any" and abi == "none" and any(p.startswith("py3") or p == "py2.py3" for p in pys.split(".")):
                req_py = f.get("requires_python") or ""
                if req_py and not _satisfies("%d.%d.%d" % sys.version_info[:3], req_py):
                    continue
                candidates.append((version, f))
                break
    if not candidates:
        has_versions = any(_satisfies(v, spec) for v in data.get("releases", {}))
        return None, ("нет чисто-Python колеса (py3-none-any)" if has_versions
                      else f"нет версии, подходящей под «{spec}»")
    stable = [c for c in candidates if not _is_pre(c[0])] or candidates
    version, f = max(stable, key=lambda c: _vkey(c[0]))
    return {"name": real_name, "version": version, "url": f["url"], "filename": f["filename"],
            "sha256": f.get("digests", {}).get("sha256", "")}, None


def _pick_chaquo(name, spec, abi):
    page = _get(CHAQUO.format(name=_norm(name)))
    if page is None:
        return None
    html = page.decode("utf-8", "replace")
    py, plat = _python_tag(), _android_abi_tag(abi)
    best = None
    for href in re.findall(r'href="([^"]+\.whl[^"]*)"', html):
        url, _, frag = href.partition("#")
        fn = url.rsplit("/", 1)[-1]
        parts = fn[:-4].split("-")
        if len(parts) < 5:
            continue
        version = parts[1]
        pys, abi_tag, platform = parts[-3:]
        if not platform.endswith(plat) or not platform.startswith("android"):
            continue
        tags = pys.split(".")
        if py not in tags and "abi3" not in abi_tag and not ("py3" in tags and abi_tag == "none"):
            continue
        if not _satisfies(version, spec):
            continue
        if best is None or _vkey(version) > _vkey(best["version"]):
            sha = frag[len("sha256="):] if frag.startswith("sha256=") else ""
            if not url.startswith("http"):
                url = CHAQUO.format(name=_norm(name)) + url
            best = {"name": parts[0], "version": version, "url": url, "filename": fn, "sha256": sha}
    return best


def _wheel_requires(data, extras):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        meta_name = next((n for n in z.namelist() if n.endswith(".dist-info/METADATA")), None)
        if meta_name is None:
            return []
        text = z.read(meta_name).decode("utf-8", "replace")
    out = []
    for line in text.splitlines():
        if not line.strip():
            break  # headers end at the first blank line
        if line.startswith("Requires-Dist:"):
            req = parse_requirement(line[len("Requires-Dist:"):].strip())
            if req and _marker_ok(req[3], extras):
                out.append(req)
    return out


def _unpack(data, target):
    os.makedirs(target, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for member in z.infolist():
            name = member.filename
            if name.endswith("/"):
                continue
            parts = name.split("/")
            if ".." in parts or name.startswith("/"):
                continue
            if parts[0].endswith(".data"):
                if len(parts) > 2 and parts[1] in ("purelib", "platlib"):
                    parts = parts[2:]
                else:
                    continue  # scripts/headers/data: not usable here
            dest = os.path.join(target, *parts)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(member) as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
        for info_dir in {n.split("/")[0] for n in z.namelist() if n.split("/")[0].endswith(".dist-info")}:
            with open(os.path.join(target, info_dir, "INSTALLER"), "w") as f:
                f.write("telescript\n")


def install(requirements_json, target, abi="arm64-v8a", log=None):
    """Installs requirements (list of PEP 508 strings) and their dependencies into target."""
    log = log or print
    requests_ = json.loads(requirements_json) if isinstance(requirements_json, str) else requirements_json
    queue = [r for r in (parse_requirement(x) for x in requests_) if r]
    done, installed, failed = set(), [], []
    os.makedirs(target, exist_ok=True)
    while queue:
        name, extras, spec, marker = queue.pop(0)
        key = _norm(name)
        if key in done or not _marker_ok(marker):
            continue
        done.add(key)
        have = _installed_version(name, target)
        if have and _satisfies(have, spec):
            log(f"✓ {name} {have} уже установлен")
            continue
        log(f"… {name}{(' ' + spec) if spec else ''}: поиск колеса")
        try:
            pick, why = (None, "служебный пакет Chaquopy") if key.startswith("chaquopy-") \
                else _pick_pypi(name, spec, log)
            if pick is None:
                native = _pick_chaquo(name, spec, abi)
                if native is None:
                    raise RuntimeError(f"{why}; в репозитории Chaquopy для {abi}/{_python_tag()} тоже нет")
                pick = native
                log(f"  {name}: Android-колесо из репозитория Chaquopy")
            log(f"↓ {pick['filename']}")
            data = _get(pick["url"])
            if data is None:
                raise RuntimeError("файл колеса не найден (404)")
            if pick["sha256"]:
                digest = hashlib.sha256(data).hexdigest()
                if digest != pick["sha256"]:
                    raise RuntimeError("sha256 не совпадает, файл отброшен")
            else:
                log(f"  {name}: индекс не дал sha256, проверка пропущена")
            deps = _wheel_requires(data, extras)
            _unpack(data, target)
            installed.append(f"{pick['name']} {pick['version']}")
            log(f"✓ {pick['name']} {pick['version']} установлен")
            queue.extend(deps)
        except Exception as error:  # noqa: BLE001 - one bad package must not crash the app
            failed.append(name)
            log(f"✗ {name}: {error}")
    importlib.invalidate_caches()
    return json.dumps({"status": "error" if failed else "ok", "installed": installed,
                       "failed": failed}, ensure_ascii=False)


# ------------------------------------------------------------------ script requirements

def script_requirements(script_dir, entry):
    """Requirements from requirements.txt next to the script and '# requires:' header lines."""
    reqs = []
    path = os.path.join(script_dir, "requirements.txt")
    if os.path.isfile(path):
        with open(path, encoding="utf-8", errors="replace") as f:
            reqs += [line.strip() for line in f if parse_requirement(line)]
    if os.path.isfile(entry):
        with open(entry, encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f):
                if i > 60:
                    break
                m = re.match(r"^\s*#\s*requires\s*:\s*(.+)$", line, re.I)
                if m:
                    reqs += [p.strip() for p in m.group(1).split(",") if parse_requirement(p.strip())]
    return reqs


def missing_requirements(script_dir, entry, target):
    """JSON list of declared requirements not satisfied yet (offline check)."""
    out = []
    for line in script_requirements(script_dir, entry):
        name, _, spec, marker = parse_requirement(line)
        if not _marker_ok(marker):
            continue
        have = _installed_version(name, target)
        if not have or not _satisfies(have, spec):
            out.append(line)
    return json.dumps(out)


def pypi_name(module):
    """PyPI project for an import name (top-level part first)."""
    if module in IMPORT_TO_PYPI:
        return IMPORT_TO_PYPI[module]
    top = module.split(".")[0]
    return IMPORT_TO_PYPI.get(top, top.replace("_", "-"))


# ------------------------------------------------------------------ managing

def activate(target):
    if target and target not in sys.path:
        os.makedirs(target, exist_ok=True)
        sys.path.insert(0, target)
    importlib.invalidate_caches()


def list_installed(target):
    out = []
    if os.path.isdir(target):
        for entry in sorted(os.listdir(target)):
            if entry.endswith(".dist-info"):
                try:
                    meta = importlib.metadata.PathDistribution(pathlib.Path(target, entry))
                    out.append({"name": meta.metadata["Name"], "version": meta.version, "dir": entry})
                except Exception:
                    continue
    return json.dumps(sorted(out, key=lambda d: d["name"].lower()), ensure_ascii=False)


def remove(target, name):
    want = _norm(name)
    removed = False
    for entry in os.listdir(target) if os.path.isdir(target) else []:
        if not entry.endswith(".dist-info") or _norm(entry[:-10].rsplit("-", 1)[0]) != want:
            continue
        record = os.path.join(target, entry, "RECORD")
        if os.path.isfile(record):
            with open(record, encoding="utf-8", errors="replace") as f:
                for line in f:
                    rel = line.split(",")[0].strip()
                    if not rel or ".." in rel.split("/"):
                        continue
                    if rel.split("/")[0].endswith(".data"):
                        parts = rel.split("/")
                        if len(parts) > 2 and parts[1] in ("purelib", "platlib"):
                            rel = "/".join(parts[2:])
                    path = os.path.join(target, rel)
                    try:
                        os.remove(path)
                    except OSError:
                        pass
        shutil.rmtree(os.path.join(target, entry), ignore_errors=True)
        removed = True
    # Remove now-empty package folders.
    for root, dirs, files in os.walk(target, topdown=False):
        if root != target and not os.listdir(root):
            os.rmdir(root)
    for mod in [m for m, v in sys.modules.items()
                if (getattr(v, "__file__", "") or "").startswith(os.path.join(target, ""))]:
        sys.modules.pop(mod, None)
    importlib.invalidate_caches()
    return json.dumps({"status": "ok" if removed else "error"})


def clear(target):
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(target, exist_ok=True)
    importlib.invalidate_caches()
    return json.dumps({"status": "ok"})


# ------------------------------------------------------------------ app entry point

def install_for_script(source, script_dir, entry, extra_json, target, abi):
    """Installs a script's declared requirements plus extra packages; progress goes to the app log."""
    import ts_host

    def log(line):
        level = ts_host.ERROR if line.startswith("✗") else ts_host.INFO
        ts_host._emit(source, level, line)

    reqs = script_requirements(script_dir, entry) if entry else []
    for extra in json.loads(extra_json or "[]"):
        if extra and _norm(parse_requirement(extra)[0]) not in {_norm(parse_requirement(r)[0]) for r in reqs}:
            reqs.append(extra)
    if not reqs:
        log("Зависимости не объявлены: нет requirements.txt и строки «# requires: …» в начале скрипта")
        return json.dumps({"status": "ok", "installed": [], "failed": [], "empty": True})
    log("Установка библиотек: " + ", ".join(reqs))
    try:
        result = install(reqs, target, abi, log)
    except Exception as error:  # noqa: BLE001
        log(f"✗ установка прервана: {type(error).__name__}: {error}")
        return json.dumps({"status": "error", "installed": [], "failed": reqs}, ensure_ascii=False)
    data = json.loads(result)
    if data["failed"]:
        log("✗ не установлено: " + ", ".join(data["failed"]))
    else:
        log("✓ все зависимости установлены")
    return result
