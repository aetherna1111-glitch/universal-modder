"""Application state shared by all screens: settings, profile, account session, readiness."""
from __future__ import annotations

import logging
import time
from dataclasses import asdict

from PySide6.QtCore import QObject, QTimer, Signal

from .. import profiles, secure_store, settings as settings_mod, system
from ..api import ApiError, LuminaryApi
from ..paths import Paths
from .tasks import TaskRunner

log = logging.getLogger("luminary.state")

REFRESH_BEFORE = 7 * 24 * 3600  # rotate the launcher session when it has less than a week left


class AppState(QObject):
    settings_changed = Signal(str)
    account_changed = Signal()
    account_status = Signal(str)      # "signed_out" | "checking" | "ok" | "offline"
    game_state_changed = Signal(str)  # "idle" | "busy" | "running"
    toast = Signal(str, str)

    def __init__(self, paths: Paths, catalog: profiles.Catalog, settings: settings_mod.Settings,
                 runner: TaskRunner, sounds):
        super().__init__()
        self.paths = paths
        self.catalog = catalog
        self.settings = settings
        self.runner = runner
        self.sounds = sounds
        self.total_ram = system.total_ram_mb()
        self.session = secure_store.load_session(paths.session_file) if catalog.accounts_enabled else None
        self.me: dict | None = None
        self.status = "signed_out" if not self.session else "checking"
        self.game_state = "idle"
        self.presence_timer = QTimer(self)
        self.presence_timer.setInterval(60_000)
        self.presence_timer.timeout.connect(self.send_presence)
        if self.settings.ram_mb not in system.ram_choices(self.total_ram):
            self.settings.ram_mb = system.clamp_ram(self.settings.ram_mb, self.total_ram)

    # ---------------------------------------------------------------- settings
    @property
    def profile(self) -> profiles.Profile:
        return self.catalog.get(self.settings.profile_id)

    def update(self, key: str, value) -> None:
        if getattr(self.settings, key) == value:
            return
        setattr(self.settings, key, value)
        try:
            settings_mod.save(self.paths, self.settings)
        except OSError as exc:
            log.error("could not save settings: %s", exc)
            self.toast.emit("Не удалось сохранить настройки", "error")
        self.settings_changed.emit(key)

    def save(self) -> None:
        try:
            settings_mod.save(self.paths, self.settings)
        except OSError as exc:
            log.error("could not save settings: %s", exc)

    # ---------------------------------------------------------------- account
    @property
    def accounts_enabled(self) -> bool:
        return self.catalog.accounts_enabled

    @property
    def signed_in(self) -> bool:
        return self.session is not None and self.me is not None

    def api(self) -> LuminaryApi:
        return LuminaryApi(self.catalog.api_url, self.session["access_token"] if self.session else None)

    def apply_login(self, body: dict) -> None:
        self.session = {"access_token": body["access_token"], "expires_at": body.get("expires_at"),
                        "username": body.get("username")}
        try:
            secure_store.save_session(self.paths.session_file, self.session)
        except OSError as exc:
            log.error("could not store session: %s", exc)
        self.me = {k: v for k, v in body.items() if k not in ("access_token",)}
        self._set_status("ok")
        self.presence_timer.start()
        self.send_presence()
        self.account_changed.emit()

    def set_me(self, me: dict) -> None:
        self.me = me
        self.account_changed.emit()

    def sign_out_local(self, reason: str | None = None) -> None:
        self.session = None
        self.me = None
        secure_store.clear_session(self.paths.session_file)
        self.presence_timer.stop()
        self._set_status("signed_out")
        self.account_changed.emit()
        if reason:
            self.toast.emit(reason, "info")

    def logout(self, everywhere: bool = False) -> None:
        api = self.api()

        def work(ctx):
            try:
                api.logout_all() if everywhere else api.logout()
            except ApiError as exc:
                log.info("logout request failed (%s); signing out locally anyway", exc.code)
        self.runner.run(work, name="logout")
        self.sign_out_local()

    def _set_status(self, status: str) -> None:
        self.status = status
        self.account_status.emit(status)

    def restore_session(self) -> None:
        if not self.session:
            self._set_status("signed_out")
            return
        self._set_status("checking")
        api = self.api()
        expires = self.session.get("expires_at") or 0

        def work(ctx):
            if expires and expires - time.time() < REFRESH_BEFORE:
                return ("refreshed", api.refresh())
            return ("me", api.me())

        def done(result):
            kind, body = result
            if kind == "refreshed":
                self.apply_login(body)
            else:
                self.me = body
                self._set_status("ok")
                self.presence_timer.start()
                self.send_presence()
                self.account_changed.emit()

        def failed(exc):
            if isinstance(exc, ApiError) and exc.unauthorized:
                self.sign_out_local("Сессия истекла, войди снова")
            else:
                log.info("account check failed: %s", exc)
                self._set_status("offline")
                self.account_changed.emit()

        self.runner.run(work, done, failed, name="restore-session")

    def handle_api_error(self, exc: Exception) -> str:
        """Common reaction to API errors; returns the message to show."""
        if isinstance(exc, ApiError):
            if exc.unauthorized and self.session:
                self.sign_out_local()
            return exc.message
        log.exception("unexpected error", exc_info=exc)
        return "Неожиданная ошибка, подробности в журнале"

    def send_presence(self) -> None:
        if not self.session or self.status != "ok":
            return
        api = self.api()
        state = "playing" if self.game_state == "running" else "online"
        self.runner.run(lambda ctx: api.presence(state), on_error=lambda e: log.debug("presence failed: %s", e),
                        name="presence")

    def set_game_state(self, state: str) -> None:
        if state != self.game_state:
            self.game_state = state
            self.game_state_changed.emit(state)
            self.send_presence()

    def diagnostics(self) -> str:
        import platform
        import sys
        from .. import __version__
        from ..modpack import local_version
        p = self.profile
        lines = [
            f"Luminary Launcher {__version__}",
            f"OS: {platform.platform()}",
            f"Python: {sys.version.split()[0]}",
            f"RAM: {self.total_ram} MB, выделено {self.settings.ram_mb} MB",
            f"Profile: {p.id} - Minecraft {p.minecraft}, {p.loader_label()}",
            f"Server: {p.server.host}:{p.server.port}" if p.server else "Server: -",
            f"Modpack: {local_version(self.paths.mc_dir) or 'не синхронизирована'}",
            f"Accounts: {self.catalog.api_url or 'не настроены'} ({self.status})",
            f"Folder: {self.paths.base}",
        ]
        settings_dump = {k: v for k, v in asdict(self.settings).items() if k not in ("window",)}
        lines.append(f"Settings: {settings_dump}")
        return "\n".join(lines)
