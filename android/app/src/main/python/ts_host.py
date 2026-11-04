"""TgAuto host: runs bundled user scripts inside the app's embedded Python.

Each script runs in its own Java thread (created by ScriptService) via runpy, as
``__main__``. Output from print(), the logging module and tracebacks is routed to
the app's log console (com.v1pach.telescript.LogBus), tagged with the script id.

Stopping a script: first every TelegramClient the script created is disconnected
(so ``run_until_disconnected`` returns cleanly), then all asyncio tasks of its
loops are cancelled, and as a last resort SystemExit is raised in its thread.
"""
import asyncio
import asyncio.base_events
import contextvars
import ctypes
import io
import json
import logging
import os
import runpy
import sys
import threading
import time
import traceback
import weakref

DEBUG, INFO, WARN, ERROR = 3, 4, 5, 6

_current = contextvars.ContextVar("telescript_script", default=None)
_runs = {}  # script id -> {"thread": ident, "loops": set, "clients": WeakSet}
_lock = threading.Lock()
_bus = None
_installed = False


def _emit(source, level, message):
    message = message.rstrip()
    if not message:
        return
    if _bus is not None:
        try:
            _bus.emit(source or "app", int(level), message)
            return
        except Exception:
            pass
    sys.__stderr__.write(f"[{source}] {message}\n")


def _source():
    sid = _current.get()
    if sid:
        return sid
    # Threads not started through asyncio (no copied context): if only one script
    # is running, it is almost certainly the owner.
    with _lock:
        if len(_runs) == 1:
            return next(iter(_runs))
    return "app"


def _guess_level(line, default):
    upper = line[:80].upper()
    if "CRITICAL" in upper or "ERROR" in upper or "TRACEBACK" in upper or "EXCEPTION" in upper:
        return ERROR
    if "WARN" in upper:
        return WARN
    if "DEBUG" in upper:
        return DEBUG
    return default


class _Router(io.TextIOBase):
    """sys.stdout / sys.stderr replacement: line-buffers per thread, forwards to LogBus."""

    def __init__(self, default_level, original):
        self._default = default_level
        self._original = original
        self._buffers = threading.local()

    def writable(self):
        return True

    def write(self, text):
        if not isinstance(text, str):
            text = str(text)
        buf = getattr(self._buffers, "value", "") + text
        *lines, rest = buf.split("\n")
        self._buffers.value = rest
        for line in lines:
            if line.strip():
                _emit(_source(), _guess_level(line, self._default), line)
        return len(text)

    def flush(self):
        rest = getattr(self._buffers, "value", "")
        if rest.strip():
            _emit(_source(), _guess_level(rest, self._default), rest)
        self._buffers.value = ""

    @property
    def encoding(self):
        return "utf-8"

    def isatty(self):
        return False


class _BusHandler(logging.Handler):
    def __init__(self):
        super().__init__(logging.DEBUG)
        self.setFormatter(logging.Formatter("%(name)s: %(message)s"))

    def emit(self, record):
        try:
            level = (ERROR if record.levelno >= logging.ERROR else
                     WARN if record.levelno >= logging.WARNING else
                     INFO if record.levelno >= logging.INFO else DEBUG)
            text = self.format(record)
            if text.startswith("root: "):
                text = text[6:]
            _emit(_source(), level, text)
        except Exception:
            pass


def _patch_asyncio():
    original = asyncio.base_events.BaseEventLoop.run_forever

    def run_forever(self):
        sid = _current.get()
        run = _runs.get(sid) if sid else None
        if run is not None:
            run["loops"].add(self)
        try:
            return original(self)
        finally:
            if run is not None:
                run["loops"].discard(self)

    asyncio.base_events.BaseEventLoop.run_forever = run_forever


def _patch_telethon():
    try:
        import telethon
    except Exception as error:  # pragma: no cover - telethon is bundled
        _emit("app", WARN, f"telethon не загружен: {error}")
        return
    original = telethon.TelegramClient.__init__

    def __init__(self, *args, **kwargs):
        original(self, *args, **kwargs)
        sid = _current.get()
        run = _runs.get(sid) if sid else None
        if run is not None:
            run["clients"].add(self)

    telethon.TelegramClient.__init__ = __init__


def init(bus):
    """Called once from Kotlin with the LogBus object."""
    global _bus, _installed
    _bus = bus
    if _installed:
        return
    _installed = True
    sys.stdout = _Router(INFO, sys.stdout)
    sys.stderr = _Router(WARN, sys.stderr)
    root = logging.getLogger()
    root.handlers[:] = [_BusHandler()]
    root.setLevel(logging.INFO)
    logging.getLogger("telethon").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    _patch_asyncio()
    _patch_telethon()


BUNDLED = "telethon, httpx, python-socks, pyaes, rsa, pyasn1, anyio, certifi, idna"


def _purge_modules(script_dir):
    """Forget modules imported from the script folder, so edited files are re-read."""
    prefix = os.path.join(script_dir, "")
    for name, module in list(sys.modules.items()):
        path = getattr(module, "__file__", None) or ""
        if path.startswith(prefix):
            sys.modules.pop(name, None)


def run_script(script_id, entry, work_dir, env_json, args_json):
    """Blocking: runs the script until it ends. Returns a JSON status string."""
    env = json.loads(env_json or "{}")
    args = json.loads(args_json or "[]")
    script_dir = os.path.dirname(entry)
    run = {"thread": threading.get_ident(), "loops": set(), "clients": weakref.WeakSet(),
           "stopping": False}
    with _lock:
        if script_id in _runs:
            return json.dumps({"status": "error", "message": "уже запущен"})
        _runs[script_id] = run
    token = _current.set(script_id)
    started = time.time()
    try:
        os.environ.update({k: str(v) for k, v in env.items()})
        site = env.get("TELESCRIPT_SITE_PACKAGES")
        if site:
            import ts_pip
            ts_pip.activate(site)
        if script_dir not in sys.path:
            sys.path.insert(0, script_dir)
        os.makedirs(work_dir, exist_ok=True)
        os.chdir(work_dir)
        sys.argv = [entry, *args]
        _purge_modules(script_dir)
        _emit(script_id, INFO, f"▶ запуск {os.path.basename(entry)} {' '.join(args)}".rstrip())
        runpy.run_path(entry, run_name="__main__")
        result = {"status": "finished"}
    except SystemExit as error:
        code = error.code
        if run["stopping"] or code in (None, 0):
            result = {"status": "finished"}
        elif isinstance(code, int):
            result = {"status": "error", "message": f"код выхода {code}"}
        else:
            result = {"status": "error", "message": str(code)}
    except (KeyboardInterrupt, asyncio.CancelledError):
        result = {"status": "finished"}
    except ModuleNotFoundError as error:
        missing = error.name or str(error)
        import ts_pip
        package = ts_pip.pypi_name(missing)
        _emit(script_id, ERROR, f"Модуль «{missing}» не найден: его нет ни в папке скрипта, "
                                f"ни среди встроенных ({BUNDLED}), ни в установленных библиотеках. "
                                f"Нажмите «Установить зависимости» на карточке скрипта — "
                                f"будет скачан пакет «{package}» с PyPI.")
        result = {"status": "error", "message": f"нет модуля {missing}",
                  "missing": missing, "package": package}
    except BaseException as error:  # noqa: BLE001 - report everything to the UI
        if run["stopping"]:
            result = {"status": "finished"}
        else:
            for line in traceback.format_exc().splitlines():
                _emit(script_id, ERROR, line)
            result = {"status": "error", "message": f"{type(error).__name__}: {error}"}
    finally:
        try:
            sys.stdout.flush()
            sys.stderr.flush()
        except Exception:
            pass
        _purge_modules(script_dir)
        _current.reset(token)
        with _lock:
            _runs.pop(script_id, None)
    if result["status"] == "error":
        _emit(script_id, ERROR, f"■ остановлен с ошибкой: {result['message']}")
    else:
        _emit(script_id, INFO, f"■ остановлен ({int(time.time() - started)} с работы)")
    return json.dumps(result, ensure_ascii=False)


def _cancel_all(loop):
    for task in asyncio.all_tasks(loop):
        task.cancel()


def stop_script(script_id, grace_seconds=8.0):
    """Asks a running script to stop; returns True once its thread has left run_script."""
    with _lock:
        run = _runs.get(script_id)
    if run is None:
        return True
    run["stopping"] = True
    _emit(script_id, INFO, "остановка…")

    loops = [loop for loop in list(run["loops"]) if loop.is_running()]
    for client in list(run["clients"]):
        loop = getattr(client, "loop", None) or (loops[0] if loops else None)
        if loop is not None and loop.is_running():
            try:
                asyncio.run_coroutine_threadsafe(client.disconnect(), loop)
            except Exception:
                pass

    def alive():
        with _lock:
            return script_id in _runs

    deadline = time.time() + grace_seconds
    step = 0
    while alive() and time.time() < deadline:
        time.sleep(0.25)
        step += 1
        if step == 8:  # 2 s: cancel every task of the script's loops
            for loop in list(run["loops"]):
                if loop.is_running():
                    loop.call_soon_threadsafe(_cancel_all, loop)
    if alive():
        # Last resort: raise SystemExit inside the script thread, then wake its loops.
        ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(run["thread"]),
                                                   ctypes.py_object(SystemExit))
        for loop in list(run["loops"]):
            if loop.is_running():
                loop.call_soon_threadsafe(lambda: None)
        end = time.time() + 3
        while alive() and time.time() < end:
            time.sleep(0.2)
    return not alive()


def running():
    with _lock:
        return json.dumps(sorted(_runs))
