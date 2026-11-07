#!/usr/bin/env python3
"""Build the embedded Termux userland (aarch64) for TgAuto.

Downloads official Termux .deb packages, and splits them into
  * native ELF files -> <out>/jniLibs/arm64-v8a/lib*.so  (exec'd/dlopen'd from nativeLibraryDir)
  * everything else  -> <out>/assets/userland-arm64.zip  (extracted to filesDir/usr on first run)
plus manifest.txt inside the zip: "rel/path<TAB>jni-name" symlink list (symlinks in filesDir/usr).
ELF files get NEEDED/soname renamed with patchelf and rpath removed.

usage: build_userland.py <out_dir> [--cache DIR] [--no-node]
Requires: patchelf, ar, tar, xz, network (cached afterwards).
"""
import hashlib, os, re, shutil, subprocess, sys, time, urllib.request, zipfile
from pathlib import Path

REPO = "https://packages.termux.dev/apt/termux-main/"
PREFIX = "data/data/com.termux/files/usr"
ROOTS = ["bash", "busybox", "curl", "git", "python", "python-pip", "nodejs-lts", "ca-certificates", "less"]
EXCLUDE = {"termux-tools", "termux-exec", "termux-core", "termux-am", "termux-am-socket", "termux-keyring",
           "dpkg", "apt", "termux-licenses"}
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libstdc++.so", "libEGL.so",
               "libGLESv2.so", "libvulkan.so", "libnativewindow.so", "libjnigraphics.so", "libOpenSLES.so",
               "libmediandk.so", "libcamera2ndk.so", "libaaudio.so", "libbinder_ndk.so", "libsync.so"}
# size trimming of data files
DROP_PARTS = ("__pycache__", "/test/", "/tests/", "/idle_test/", "/turtledemo/", "/idlelib/", "/tkinter/",
              "/ensurepip/_bundled/", "/share/man/", "/share/doc/", "/share/info/", "/include/", "/share/bash-completion/",
              "/lib/pkgconfig/", "/share/locale/", "/share/aclocal/", "/lib/cmake/", "/share/gitweb/", "/share/git-gui/",
              "/share/gitk/", "/share/perl5/", "/lib/node_modules/npm/docs/", "/lib/node_modules/npm/man/",
              "/lib/node_modules/corepack/")
DROP_SUFFIX = (".a", ".la", ".h", ".pyc")


def fetch(url, dest):
    if dest.exists() and dest.stat().st_size:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    print("get", url, file=sys.stderr)
    for attempt in range(6):
        try:
            urllib.request.urlretrieve(url, dest)
            return
        except Exception as e:
            print("retry", attempt, e, file=sys.stderr)
            time.sleep(3 * (attempt + 1))
    sys.exit("download failed: " + url)


def parse_packages(text):
    pk = {}
    for blk in text.split("\n\n"):
        d = {}
        for l in blk.split("\n"):
            if ": " in l and not l.startswith(" "):
                k, v = l.split(": ", 1)
                d[k] = v
        if "Package" in d:
            pk[d["Package"]] = d
    return pk


def closure(pk, roots):
    seen = []
    def add(n):
        if n in seen or n in EXCLUDE or n not in pk:
            return
        seen.append(n)
        for dep in pk[n].get("Depends", "").split(","):
            dep = dep.strip().split("|")[0].strip().split(" ")[0]
            if dep:
                add(dep)
    for r in roots:
        add(r)
    return seen


def is_elf(p: Path):
    try:
        with open(p, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False


def main():
    out = Path(sys.argv[1]).resolve()
    cache = Path(os.path.expanduser("~/.cache/tgauto-userland"))
    roots = list(ROOTS)
    if "--cache" in sys.argv:
        cache = Path(sys.argv[sys.argv.index("--cache") + 1])
    if "--no-node" in sys.argv:
        roots.remove("nodejs-lts")
    stamp = out / ".stamp"
    want = ",".join(roots)
    if stamp.exists() and stamp.read_text() == want:
        print("userland up to date"); return
    pkgfile = cache / "Packages-aarch64"
    fetch(REPO + "dists/stable/main/binary-aarch64/Packages", pkgfile)
    pk = parse_packages(pkgfile.read_text())
    names = closure(pk, roots)
    stage = cache / "stage"
    shutil.rmtree(stage, ignore_errors=True)
    stage.mkdir(parents=True)
    pkgs = []
    for n in names:
        d = pk[n]
        deb = cache / "debs" / Path(d["Filename"]).name
        fetch(REPO + d["Filename"], deb)
        if hashlib.sha256(deb.read_bytes()).hexdigest() != d["SHA256"]:
            deb.unlink(); sys.exit("sha256 mismatch " + n)
        tmp = cache / "x"
        shutil.rmtree(tmp, ignore_errors=True); tmp.mkdir()
        subprocess.run(f"ar p '{deb}' data.tar.xz | tar -xJ -C '{tmp}'", shell=True, check=True)
        src = tmp / PREFIX
        if src.exists():
            subprocess.run(["cp", "-a", "--remove-destination", f"{src}/.", str(stage)], check=True)
        pkgs.append((n, d["Version"], d.get("License", ""), d.get("Homepage", "")))
    shutil.rmtree(cache / "x", ignore_errors=True)

    # --- classify ---------------------------------------------------------------------------------
    elfs = {}      # rel path -> Path (real file, not symlink)
    for p in sorted(stage.rglob("*")):
        rel = p.relative_to(stage).as_posix()
        if p.is_symlink() or p.is_dir():
            continue
        if any(x in "/" + rel for x in DROP_PARTS) or rel.endswith(DROP_SUFFIX):
            continue
        if is_elf(p):
            elfs[rel] = p
    used = set()
    jni = {}       # rel -> jni name
    def mk(rel):
        base = rel.replace("/", "_")
        base = re.sub(r"^lib", "", base)
        base = re.sub(r"[^A-Za-z0-9_.+-]", "_", base)
        base = base.replace(".so.", "_so_").replace(".so", "")
        base = base.replace(".", "_")
        name = "lib" + base + ".so"
        i = 2
        while name in used:
            name = f"lib{base}{i}.so"; i += 1
        used.add(name)
        return name
    by_hash = {}   # identical files (git hardlinks) share one jni file
    for rel, p in elfs.items():
        h = hashlib.sha1(p.read_bytes()).hexdigest()
        if h in by_hash:
            jni[rel] = jni[by_hash[h]]
        else:
            by_hash[h] = rel
            jni[rel] = mk(rel)

    def resolve_link(rel):
        """follow symlinks inside stage; return rel of a real ELF or None"""
        p = stage / rel
        for _ in range(10):
            if not p.is_symlink():
                break
            t = os.readlink(p)
            p = (p.parent / t) if not t.startswith("/") else stage / os.path.relpath(t, "/" + PREFIX)
            p = Path(os.path.normpath(p))
        try:
            r = p.relative_to(stage).as_posix()
        except ValueError:
            return None
        return r if r in elfs else None

    libdirs = ["lib", "lib/node_modules", ""]
    def find_needed(name):
        for d in ("lib", "bin", "libexec"):
            r = resolve_link(f"{d}/{name}") if (stage / d / name).exists() or (stage / d / name).is_symlink() else None
            if r:
                return jni[r]
        # python lib-dynload etc.: any basename match
        for rel in elfs:
            if rel.endswith("/" + name) or rel == name:
                return jni[rel]
        return None

    jl = out / "jniLibs" / "arm64-v8a"
    shutil.rmtree(out, ignore_errors=True)
    jl.mkdir(parents=True)
    done = set()
    for rel, p in elfs.items():
        dst = jl / jni[rel]
        if jni[rel] in done:
            continue
        done.add(jni[rel])
        shutil.copy2(p, dst)
        os.chmod(dst, 0o755)
        subprocess.run(["patchelf", "--remove-rpath", str(dst)], check=False, stderr=subprocess.DEVNULL)
        needed = subprocess.run(["patchelf", "--print-needed", str(dst)], capture_output=True, text=True).stdout.split()
        args = []
        for n in needed:
            if n in SYSTEM_LIBS:
                continue
            t = find_needed(n)
            if t:
                args += ["--replace-needed", n, t]
            else:
                print("WARN unresolved NEEDED", n, "in", rel, file=sys.stderr)
        if args:
            subprocess.run(["patchelf", *args, str(dst)], check=True)
        if rel.endswith(".so") or ".so." in rel:
            subprocess.run(["patchelf", "--set-soname", jni[rel], str(dst)], check=False, stderr=subprocess.DEVNULL)
        subprocess.run(["strip", "--strip-unneeded", str(dst)], check=False, stderr=subprocess.DEVNULL)

    # --- links + data zip -------------------------------------------------------------------------
    links = []   # (rel, jniname)
    for rel in elfs:
        links.append((rel, jni[rel]))
    zpath = out / "assets" / "userland-arm64.zip"
    zpath.parent.mkdir(parents=True)
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(stage.rglob("*")):
            rel = p.relative_to(stage).as_posix()
            if p.is_dir() or any(x in "/" + rel for x in DROP_PARTS) or rel.endswith(DROP_SUFFIX):
                continue
            if p.is_symlink():
                r = resolve_link(rel)
                if r:
                    links.append((rel, jni[r]))
                else:
                    # symlink to data: store target text, runtime recreates as relative symlink
                    t = os.readlink(p)
                    if not t.startswith("/"):
                        z.writestr("symlinks/" + rel, t)
                continue
            if rel in elfs:
                continue
            z.write(p, "files/" + rel)
        z.writestr("manifest.txt", "\n".join(f"{a}\t{b}" for a, b in sorted(set(links))) + "\n")
        z.writestr("packages.txt", "\n".join(f"{n}\t{v}\t{l}\t{h}" for n, v, l, h in pkgs) + "\n")
    (out / "packages.txt").write_text("\n".join(f"{n} {v} {l} {h}" for n, v, l, h in pkgs) + "\n")
    stamp.write_text(want)
    tot = sum(f.stat().st_size for f in jl.iterdir())
    print(f"done: {len(elfs)} ELF files {tot//1048576} MB, zip {zpath.stat().st_size//1048576} MB, {len(pkgs)} packages")


if __name__ == "__main__":
    main()
