"""Telegram login for the TeleScript app (phone code primary, QR secondary).

All functions are synchronous (called from Kotlin on an IO thread) and return JSON.
They run coroutines on one dedicated asyncio loop thread that owns the client.
"""
import asyncio
import json
import os
import threading

from telethon import TelegramClient, errors

_loop = None
_loop_lock = threading.Lock()
_state = {"client": None, "phone": None, "hash": None, "qr": None, "key": None}


def _ensure_loop():
    global _loop
    with _loop_lock:
        if _loop is None:
            _loop = asyncio.new_event_loop()
            threading.Thread(target=_loop.run_forever, name="ts-login", daemon=True).start()
    return _loop


def _run(coro, timeout=60):
    return asyncio.run_coroutine_threadsafe(coro, _ensure_loop()).result(timeout)


def _ok(**kw):
    return json.dumps({"status": "ok", **kw}, ensure_ascii=False)


def _err(message, status="error"):
    return json.dumps({"status": status, "message": str(message)}, ensure_ascii=False)


def _proxy_kwargs(proxy):
    if not proxy or proxy.get("type") in (None, "", "none"):
        return {}
    if proxy["type"] == "socks5":
        return {"proxy": ("socks5", proxy["host"], int(proxy["port"]))}
    from telethon.network.connection import ConnectionTcpMTProxyRandomizedIntermediate
    return {"connection": ConnectionTcpMTProxyRandomizedIntermediate,
            "proxy": (proxy["host"], int(proxy["port"]), proxy.get("secret", ""))}


async def _client(session, api_id, api_hash, proxy_json):
    key = (session, int(api_id), api_hash, proxy_json)
    client = _state["client"]
    if client is not None and _state["key"] != key:
        await client.disconnect()
        client = None
    if client is None:
        os.makedirs(os.path.dirname(session), exist_ok=True)
        client = TelegramClient(session, int(api_id), api_hash,
                                device_model="TeleScript", app_version="1.0",
                                **_proxy_kwargs(json.loads(proxy_json or "{}")))
        _state.update(client=client, key=key)
    if not client.is_connected():
        await asyncio.wait_for(client.connect(), 30)
    return client


def _describe(me):
    name = " ".join(x for x in (me.first_name, me.last_name) if x) or "без имени"
    return {"id": me.id, "name": name, "username": me.username or "",
            "phone": ("+" + me.phone) if me.phone else ""}


def _wrap(fn):
    def inner(*args):
        try:
            return fn(*args)
        except errors.FloodWaitError as e:
            return _err(f"Слишком много попыток, подождите {e.seconds} с")
        except errors.PhoneNumberInvalidError:
            return _err("Неверный номер телефона")
        except (errors.PhoneCodeInvalidError, errors.PhoneCodeExpiredError):
            return _err("Неверный или просроченный код")
        except errors.PasswordHashInvalidError:
            return _err("Неверный пароль 2FA")
        except errors.ApiIdInvalidError:
            return _err("Неверные api_id / api_hash")
        except (asyncio.TimeoutError, TimeoutError):
            return _err("Нет соединения с Telegram (таймаут). Проверьте прокси")
        except Exception as e:  # noqa: BLE001
            return _err(f"{type(e).__name__}: {e}")
    return inner


@_wrap
def account_info(session, api_id, api_hash, proxy_json):
    async def go():
        client = await _client(session, api_id, api_hash, proxy_json)
        if not await client.is_user_authorized():
            return _ok(authorized=False)
        return _ok(authorized=True, **_describe(await client.get_me()))
    return _run(go())


@_wrap
def send_code(session, api_id, api_hash, proxy_json, phone):
    async def go():
        client = await _client(session, api_id, api_hash, proxy_json)
        sent = await client.send_code_request(phone)
        _state.update(phone=phone, hash=sent.phone_code_hash)
        return _ok(step="code")
    return _run(go())


@_wrap
def sign_in_code(code):
    async def go():
        client = _state["client"]
        if client is None or not _state["phone"]:
            return _err("Сначала запросите код")
        try:
            me = await client.sign_in(_state["phone"], code.strip(),
                                      phone_code_hash=_state["hash"])
        except errors.SessionPasswordNeededError:
            return _ok(step="password")
        return _ok(step="done", **_describe(me))
    return _run(go())


@_wrap
def sign_in_password(password):
    async def go():
        client = _state["client"]
        if client is None:
            return _err("Нет активного входа")
        me = await client.sign_in(password=password)
        return _ok(step="done", **_describe(me))
    return _run(go())


@_wrap
def qr_start(session, api_id, api_hash, proxy_json):
    async def go():
        client = await _client(session, api_id, api_hash, proxy_json)
        qr = await client.qr_login()
        _state["qr"] = qr
        return _ok(url=qr.url)
    return _run(go())


@_wrap
def qr_wait(seconds):
    async def go():
        qr = _state["qr"]
        if qr is None:
            return _err("QR не создан")
        try:
            me = await qr.wait(timeout=float(seconds))
        except (asyncio.TimeoutError, TimeoutError):
            await qr.recreate()
            return _ok(step="timeout", url=qr.url)
        except errors.SessionPasswordNeededError:
            return _ok(step="password")
        return _ok(step="done", **_describe(me))
    return _run(go(), timeout=float(seconds) + 30)


@_wrap
def release():
    """Disconnects the login client so the session file is free for scripts."""
    async def go():
        client = _state["client"]
        if client is not None:
            await client.disconnect()
        _state.update(client=None, key=None, qr=None)
        return _ok()
    return _run(go())


@_wrap
def logout(session, api_id, api_hash, proxy_json):
    async def go():
        try:
            client = await _client(session, api_id, api_hash, proxy_json)
            await client.log_out()
        finally:
            _state.update(client=None, key=None, qr=None, phone=None, hash=None)
            for suffix in ("", ".session", ".session-journal"):
                try:
                    os.remove(session + suffix)
                except OSError:
                    pass
        return _ok()
    return _run(go())
