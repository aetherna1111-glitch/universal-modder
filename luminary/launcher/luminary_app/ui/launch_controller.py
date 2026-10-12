"""The Play pipeline, run in background phases with progress and cancellation.

1. prepare (worker): account game token + authlib-injector + Yggdrasil metadata; modpack manifest + plan
2. ask (GUI): "update available" dialog unless updates are automatic
3. run (worker): apply modpack update, install/verify with PortableMC (--dry), start the JVM
4. monitor (worker): wait for the game; report crashes with the log and crash report
"""
from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from pathlib import Path

from PySide6.QtCore import QObject, Signal

from .. import launch, modpack
from ..api import ApiError
from ..launch import LaunchError, LaunchRequest
from ..logs import tail
from .state import AppState

log = logging.getLogger("luminary.play")


@dataclass
class Prepared:
    request: LaunchRequest
    pack_cfg: modpack.PackConfig | None
    plan: modpack.Plan | None
    cache: modpack.HashCache | None
    pack_warning: str | None


@dataclass
class GameResult:
    exit_code: int
    log_path: Path
    crash_report: Path | None
    started_at: float
    log_tail: str


class LaunchController(QObject):
    progress = Signal(str, object)      # text, fraction (0..1) or None
    phase = Signal(str)                 # idle | preparing | syncing | installing | starting | running
    failed = Signal(str, object)        # message, log path or None
    notice = Signal(str)                # non-fatal information (e.g. modpack server unreachable)
    ask_update = Signal(object)         # modpack.Plan -> UI calls continue_with(update: bool)
    game_started = Signal()
    game_finished = Signal(object)      # GameResult
    _monitor_done = Signal(object)

    def __init__(self, state: AppState, parent: QObject | None = None):
        super().__init__(parent)
        self._monitor_done.connect(self._on_game_exit)
        self.state = state
        self._handle = None
        self._prepared: Prepared | None = None
        self.game: launch.GameProcess | None = None
        self.busy = False

    # ------------------------------------------------------------------ helpers
    def java_path(self) -> Path:
        custom = self.state.settings.java_path
        return Path(custom) if custom else self.state.paths.base / self.state.profile.java_relpath()

    def _set_phase(self, phase: str) -> None:
        self.phase.emit(phase)
        self.state.set_game_state({"idle": "idle", "running": "running"}.get(phase, "busy"))

    def _fail(self, message: str, log_path: Path | None = None) -> None:
        self.busy = False
        self._set_phase("idle")
        self.state.sounds.play("error")
        self.failed.emit(message, log_path)

    def cancel(self) -> None:
        if self._handle is not None:
            self._handle.cancel()
        self.busy = False
        self._prepared = None
        self._set_phase("idle")
        self.progress.emit("Запуск отменён", None)

    # ------------------------------------------------------------------ phase 1
    def start(self, nickname: str | None) -> None:
        if self.busy or (self.game is not None and self.game.running()):
            return
        st = self.state
        java = self.java_path()
        if not java.is_file():
            self._fail(f"Java не найдена: {java}. Проверь папку jre или путь к Java в настройках.")
            return
        if not st.paths.portablemc.is_file():
            self._fail(f"Не найден установщик игры {st.paths.portablemc.name} в папке лаунчера.")
            return
        use_account = st.signed_in
        if not use_account and (st.catalog.accounts_required or not nickname):
            self._fail("Войди в аккаунт Luminary, чтобы играть." if st.catalog.accounts_required
                       else "Введи ник.")
            return
        self.busy = True
        self._set_phase("preparing")
        self.progress.emit("Подготовка…", None)
        profile = st.profile
        paths = st.paths
        api = st.api() if use_account else None
        settings = st.settings
        catalog = st.catalog

        def work(ctx):
            req = LaunchRequest(
                portablemc=[str(paths.portablemc)], main_dir=paths.mc_dir, version=profile.portablemc_version(),
                java=java, ram_mb=settings.ram_mb, username=nickname or "", uuid="",
                server=(profile.server.host, profile.server.port) if profile.server else None,
                extra_jvm_args=settings.jvm_arg_list())
            if use_account:
                ctx.progress(("Вход в аккаунт…", None))
                tok = api.game_token()
                req.username, req.uuid, req.access_token = tok["name"], _dashed(tok["uuid"]), tok["access_token"]
                if catalog.authlib is None:
                    raise LaunchError("В profiles.json не настроен authlib-injector.")
                ctx.progress(("Проверка authlib-injector…", None))
                req.authlib_jar = launch.ensure_file(catalog.authlib.url, catalog.authlib.sha256,
                                                     paths.runtime_dir / catalog.authlib.filename)
                req.yggdrasil_url = tok["yggdrasil_url"]
                req.yggdrasil_prefetched = api.yggdrasil_metadata_raw(tok["yggdrasil_url"])
            else:
                req.uuid = launch.java_offline_uuid(req.username)
            pack_cfg = plan = cache = None
            warning = None
            cfg_path = paths.base / profile.modpack_config if profile.modpack_config else None
            if cfg_path is not None:
                try:
                    pack_cfg = modpack.load_config(cfg_path)
                except (modpack.PackError, ValueError, OSError) as exc:
                    raise LaunchError(f"config_updater.json: {exc}") from None
            if pack_cfg is not None:
                ctx.progress(("Проверка сборки…", None))
                try:
                    manifest = modpack.fetch_manifest(pack_cfg)
                    cache = modpack.HashCache(paths.mc_dir / modpack.HASH_CACHE)
                    paths.mc_dir.mkdir(parents=True, exist_ok=True)
                    plan = modpack.make_plan(manifest, paths.mc_dir, pack_cfg.folders, cache,
                                             lambda i, n, msg: ctx.progress((msg, i / n if n else None)), ctx.cancel)
                    cache.save()
                except modpack.PackError as exc:
                    if pack_cfg.block_launch_on_error:
                        raise LaunchError(f"Не удалось проверить сборку: {exc}") from None
                    warning = f"Сборка не проверена: {exc}. Игра запустится с текущими файлами."
                    log.warning(warning)
            return Prepared(req, pack_cfg, plan, cache, warning)

        def done(prep: Prepared):
            if not self.busy:
                return
            self._prepared = prep
            if prep.pack_warning:
                self.notice.emit(prep.pack_warning)
            plan = prep.plan
            if plan is not None and not plan.empty:
                auto = self.state.settings.pack_updates == "auto" or (prep.pack_cfg and prep.pack_cfg.auto_update)
                if auto:
                    self.continue_with(True)
                else:
                    self.ask_update.emit(plan)
                return
            self.continue_with(False)

        def failed(exc):
            if isinstance(exc, ApiError):
                msg = self.state.handle_api_error(exc)
                self._fail(f"Не удалось войти в игру через аккаунт: {msg}")
            elif isinstance(exc, LaunchError):
                self._fail(str(exc), exc.log_path)
            elif isinstance(exc, modpack.Cancelled):
                self.cancel()
            else:
                log.exception("prepare failed", exc_info=exc)
                self._fail(f"Неожиданная ошибка при подготовке: {exc}")

        self._handle = st.runner.run(work, done, failed, lambda p: self.progress.emit(*p), name="prepare")

    # ------------------------------------------------------------------ phase 3
    def continue_with(self, update: bool) -> None:
        prep = self._prepared
        if prep is None or not self.busy:
            return
        st = self.state
        paths = st.paths
        log_dir = paths.log_dir

        def work(ctx):
            if update and prep.plan is not None and prep.pack_cfg is not None:
                ctx.progress(("phase", "syncing"))
                modpack.apply_plan(prep.plan, prep.pack_cfg, paths.mc_dir, prep.cache,
                                   lambda d, t, msg: ctx.progress((f"Обновление сборки: {msg}", d / t if t else None)),
                                   ctx.cancel)
                ctx.progress(("sound", "loaded"))
            ctx.progress(("phase", "installing"))
            install_log = launch.rotate_logs(log_dir, "install", keep=5)
            result = launch.run_install(prep.request, install_log,
                                        lambda p: ctx.progress((p.stage + (f" · {p.detail}" if p.detail else ""),
                                                                p.fraction)), ctx.cancel)
            if result.warnings:
                log.warning("installer warnings: %s", result.warnings)
            ctx.progress(("phase", "starting"))
            cmd = launch.game_command(prep.request, result.args)
            game_log = launch.rotate_logs(log_dir, "game")
            return launch.GameProcess(cmd, paths.mc_dir, game_log)

        def progress(p):
            a, b = p
            if a == "phase":
                self._set_phase(b)
            elif a == "sound":
                st.sounds.play(b)
            else:
                self.progress.emit(a, b)

        def done(game: launch.GameProcess):
            self.game = game
            self.busy = False
            self._prepared = None
            self._set_phase("running")
            self.progress.emit("Игра запущена", None)
            st.sounds.play("launch")
            self.game_started.emit()
            self._monitor(game)

        def failed(exc):
            if isinstance(exc, (modpack.Cancelled,)):
                self.cancel()
            elif isinstance(exc, LaunchError):
                self._fail(str(exc), exc.log_path)
            elif isinstance(exc, modpack.PackError):
                self._fail(f"Обновление сборки не удалось: {exc}. Файлы сборки не изменены.")
            else:
                log.exception("launch failed", exc_info=exc)
                self._fail(f"Неожиданная ошибка запуска: {exc}")

        self._handle = st.runner.run(work, done, failed, progress, name="launch")

    # ------------------------------------------------------------------ phase 4
    def _monitor(self, game: launch.GameProcess) -> None:
        """Wait for the game on a daemon thread (not the Qt pool), so quitting the launcher never blocks
        on a running game and never kills it."""
        mc_dir = self.state.paths.mc_dir

        def wait():
            try:
                code = game.wait()
                report = launch.find_crash_report(mc_dir, game.started_at) if code != 0 else None
                result = GameResult(code, game.log_path, report, game.started_at,
                                    tail(game.log_path, 30) if code else "")
            except Exception:
                log.exception("game monitor failed")
                result = GameResult(-1, game.log_path, None, game.started_at, "")
            self._monitor_done.emit(result)  # queued to the GUI thread

        threading.Thread(target=wait, name="game-monitor", daemon=True).start()

    def _on_game_exit(self, result: GameResult) -> None:
        log.info("game exited with code %s", result.exit_code)
        self.game = None
        self._set_phase("idle")
        self.game_finished.emit(result)


def _dashed(uuid_hex: str) -> str:
    import uuid
    return str(uuid.UUID(uuid_hex))
