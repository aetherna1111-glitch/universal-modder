"""Client for the Luminary accounts service (stdlib only, TLS verification always on)."""
from __future__ import annotations

import json
import logging
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

from . import __version__

log = logging.getLogger("luminary.api")
USER_AGENT = f"LuminaryLauncher/{__version__}"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Never follow redirects: they could carry the Authorization header to another host or to http."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


@dataclass
class ApiError(Exception):
    status: int
    code: str
    message: str
    retry_after: int | None = None

    def __str__(self) -> str:
        return self.message

    @property
    def unauthorized(self) -> bool:
        return self.status == 401

    @property
    def offline(self) -> bool:
        return self.code in {"network", "timeout", "tls"}


def _same_origin(url: str, base: str) -> bool:
    a, b = urllib.parse.urlparse(url), urllib.parse.urlparse(base)
    return (a.scheme, a.hostname, a.port) == (b.scheme, b.hostname, b.port)


class LuminaryApi:
    def __init__(self, base_url: str, token: str | None = None, timeout: float = 15):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    # ------------------------------------------------------------ transport
    def _request(self, method: str, path: str, body: dict | None = None, data: bytes | None = None,
                 content_type: str | None = None, auth: bool = True, max_bytes: int = 2_000_000):
        if not self.base_url:
            raise ApiError(0, "not_configured", "Сервер аккаунтов не настроен")
        url = self.base_url + path
        headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        if auth:
            if not self.token:
                raise ApiError(401, "unauthorized", "Войди в аккаунт")
            headers["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with _OPENER.open(req, timeout=self.timeout) as resp:
                raw = resp.read(max_bytes + 1)
                if len(raw) > max_bytes:
                    raise ApiError(resp.status, "too_large", "Ответ сервера слишком большой")
                if resp.status == 204 or not raw:
                    return None
                return json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raise self._http_error(exc) from None
        except urllib.error.URLError as exc:
            reason = exc.reason
            if isinstance(reason, ssl.SSLError):
                log.warning("TLS error talking to %s: %s", self.base_url, reason)
                raise ApiError(0, "tls", "Не удалось проверить сертификат сервера аккаунтов. "
                                         "Проверь дату и время на компьютере.") from None
            if isinstance(reason, (socket.timeout, TimeoutError)):
                raise ApiError(0, "timeout", "Сервер аккаунтов не отвечает") from None
            log.info("network error %s %s: %s", method, path, reason)
            raise ApiError(0, "network", "Нет связи с сервером аккаунтов") from None
        except (socket.timeout, TimeoutError):
            raise ApiError(0, "timeout", "Сервер аккаунтов не отвечает") from None
        except ValueError:
            raise ApiError(0, "bad_response", "Сервер аккаунтов вернул неожиданный ответ") from None

    @staticmethod
    def _http_error(exc: urllib.error.HTTPError) -> ApiError:
        message, code = None, "http_error"
        try:
            payload = json.loads(exc.read(64_000).decode("utf-8"))
            message = payload.get("message") or payload.get("errorMessage")
            code = payload.get("error") or code
        except Exception:
            pass
        retry = None
        if exc.code == 429:
            try:
                retry = int(exc.headers.get("Retry-After", "0"))
            except ValueError:
                retry = None
        defaults = {401: "Сессия истекла, войди снова", 403: "Доступ запрещён", 404: "Не найдено",
                    429: "Слишком много попыток, подожди немного", 500: "Ошибка на сервере аккаунтов",
                    502: "Сервер аккаунтов недоступен", 503: "Сервер аккаунтов недоступен"}
        return ApiError(exc.code, code, message or defaults.get(exc.code, f"Ошибка сервера ({exc.code})"), retry)

    # ------------------------------------------------------------ account
    def info(self):
        return self._request("GET", "/api/v1/info", auth=False)

    def register(self, username: str, password: str):
        return self._request("POST", "/api/v1/auth/register", {"username": username, "password": password}, auth=False)

    def login(self, username: str, password: str):
        return self._request("POST", "/api/v1/auth/login", {"username": username, "password": password}, auth=False)

    def refresh(self):
        return self._request("POST", "/api/v1/auth/refresh")

    def logout(self):
        return self._request("POST", "/api/v1/auth/logout")

    def logout_all(self):
        return self._request("POST", "/api/v1/auth/logout-all")

    def change_password(self, old: str, new: str):
        return self._request("POST", "/api/v1/auth/password", {"old_password": old, "new_password": new})

    def me(self):
        return self._request("GET", "/api/v1/me")

    def game_token(self):
        return self._request("POST", "/api/v1/game/token")

    # ------------------------------------------------------------ skins
    def upload_skin(self, png: bytes, model: str = "auto"):
        q = urllib.parse.urlencode({"model": model})
        return self._request("PUT", f"/api/v1/me/skin?{q}", data=png, content_type="image/png")

    def set_skin_model(self, model: str):
        return self._request("PATCH", "/api/v1/me/skin", {"model": model})

    def delete_skin(self):
        return self._request("DELETE", "/api/v1/me/skin")

    def fetch_texture(self, url: str, max_bytes: int = 256 * 1024) -> bytes:
        """Download a skin PNG, only from the accounts server itself."""
        if not _same_origin(url, self.base_url):
            raise ApiError(0, "bad_url", "Скин размещён на неизвестном сервере")
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with _OPENER.open(req, timeout=self.timeout) as resp:
                data = resp.read(max_bytes + 1)
        except (urllib.error.URLError, socket.timeout, TimeoutError):
            raise ApiError(0, "network", "Не удалось загрузить скин") from None
        if len(data) > max_bytes or not data.startswith(b"\x89PNG"):
            raise ApiError(0, "bad_texture", "Сервер вернул некорректный скин")
        return data

    # ------------------------------------------------------------ social
    def presence(self, state: str):
        return self._request("POST", "/api/v1/presence", {"state": state})

    def search_users(self, query: str):
        return self._request("GET", "/api/v1/users/search?" + urllib.parse.urlencode({"q": query}))

    def user(self, name: str):
        return self._request("GET", "/api/v1/users/" + urllib.parse.quote(name, safe=""))

    def friends(self):
        return self._request("GET", "/api/v1/friends")

    def send_friend_request(self, name: str):
        return self._request("POST", "/api/v1/friends/requests", {"username": name})

    def accept_request(self, request_id: int):
        return self._request("POST", f"/api/v1/friends/requests/{int(request_id)}/accept")

    def decline_request(self, request_id: int):
        return self._request("POST", f"/api/v1/friends/requests/{int(request_id)}/decline")

    def cancel_request(self, request_id: int):
        return self._request("DELETE", f"/api/v1/friends/requests/{int(request_id)}")

    def remove_friend(self, name: str):
        return self._request("DELETE", "/api/v1/friends/" + urllib.parse.quote(name, safe=""))

    def news(self, limit: int = 5):
        return self._request("GET", f"/api/v1/news?limit={int(limit)}", auth=False)

    # ------------------------------------------------------------ yggdrasil
    def yggdrasil_metadata_raw(self, yggdrasil_url: str) -> bytes:
        """Raw metadata JSON, passed to authlib-injector as prefetched data (saves a request at startup)."""
        if not _same_origin(yggdrasil_url, self.base_url):
            raise ApiError(0, "bad_url", "Неверный адрес Yggdrasil API")
        req = urllib.request.Request(yggdrasil_url.rstrip("/") + "/", headers={"User-Agent": USER_AGENT})
        try:
            with _OPENER.open(req, timeout=self.timeout) as resp:
                data = resp.read(200_000)
            json.loads(data)
            return data
        except (urllib.error.URLError, socket.timeout, TimeoutError, ValueError):
            raise ApiError(0, "network", "Не удалось получить настройки авторизации") from None
