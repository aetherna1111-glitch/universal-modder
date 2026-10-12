"""FastAPI application factory.

Run locally:   LUMINARY_DATA_DIR=./data uvicorn luminary_auth.app:create_app --factory --port 8080
"""
from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response

from . import __version__, accounts, config, db
from .security import RateLimited, ValidationError, prune_rate_events
from .signing import Signer
from .skins import SkinError, TextureStore, is_valid_hash
from .launcher_api import router as launcher_router
from .yggdrasil import YggdrasilError, error_response, router as yggdrasil_router

log = logging.getLogger("luminary_auth")


def _is_yggdrasil(request: Request) -> bool:
    return request.url.path.startswith("/yggdrasil")


def _error(request: Request, status: int, code: str, message: str, headers: dict | None = None) -> JSONResponse:
    if _is_yggdrasil(request):
        ygg = {400: "IllegalArgumentException", 404: "NotFoundException", 429: "ForbiddenOperationException"}
        return JSONResponse(status_code=status, headers=headers,
                            content={"error": ygg.get(status, "ForbiddenOperationException"), "errorMessage": message})
    return JSONResponse(status_code=status, headers=headers, content={"error": code, "message": message})


def create_app(settings: config.Settings | None = None) -> FastAPI:
    settings = settings or config.from_env()
    settings.validate()
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.textures_dir.mkdir(parents=True, exist_ok=True)

    conn = db.connect(settings.db_path)
    try:
        version = db.migrate(conn)
        prune_rate_events(conn)
    finally:
        conn.close()

    app = FastAPI(title="Luminary accounts", version=__version__, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings
    app.state.signer = Signer.load_or_create(settings.key_path)
    app.state.textures = TextureStore(settings.textures_dir)
    log.info("luminary-auth %s ready, schema v%s, public url %s", __version__, version, settings.public_url)

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        response.headers.setdefault("X-Frame-Options", "DENY")
        # authlib-injector discovers the API root from this header (API Location Indication).
        response.headers.setdefault("X-Authlib-Injector-API-Location", "/yggdrasil/")
        if not request.url.path.startswith("/textures/"):
            response.headers.setdefault("Cache-Control", "no-store")
        return response

    @app.exception_handler(YggdrasilError)
    async def _ygg(request: Request, exc: YggdrasilError):
        return error_response(exc)

    @app.exception_handler(accounts.AuthError)
    async def _auth(request: Request, exc: accounts.AuthError):
        return _error(request, 401, "unauthorized", str(exc))

    @app.exception_handler(accounts.Forbidden)
    async def _forbidden(request: Request, exc: accounts.Forbidden):
        return _error(request, 403, "forbidden", str(exc))

    @app.exception_handler(accounts.NotFound)
    async def _not_found(request: Request, exc: accounts.NotFound):
        return _error(request, 404, "not_found", str(exc))

    @app.exception_handler(accounts.Conflict)
    async def _conflict(request: Request, exc: accounts.Conflict):
        return _error(request, 409, "conflict", str(exc))

    @app.exception_handler(ValidationError)
    async def _invalid(request: Request, exc: ValidationError):
        return _error(request, 400, "invalid", str(exc))

    @app.exception_handler(SkinError)
    async def _skin(request: Request, exc: SkinError):
        return _error(request, 400, "invalid_skin", str(exc))

    @app.exception_handler(RateLimited)
    async def _limited(request: Request, exc: RateLimited):
        return _error(request, 429, "rate_limited",
                      f"Слишком много попыток, попробуй через {exc.retry_after} с",
                      headers={"Retry-After": str(exc.retry_after)})

    @app.exception_handler(RequestValidationError)
    async def _bad_request(request: Request, exc: RequestValidationError):
        # Never echo the submitted body (it may hold a password).
        fields = sorted({".".join(str(p) for p in e.get("loc", [])[1:]) for e in exc.errors()})
        return _error(request, 400, "bad_request", "Неверный запрос: " + ", ".join(f for f in fields if f))

    @app.exception_handler(Exception)
    async def _crash(request: Request, exc: Exception):
        log.exception("unhandled error on %s %s", request.method, request.url.path)
        return _error(request, 500, "server_error", "Внутренняя ошибка сервера")

    @app.get("/textures/{sha256}")
    def texture(sha256: str):
        if not is_valid_hash(sha256):
            return Response(status_code=404)
        path = app.state.textures.path_for(sha256)
        if not path.is_file():
            return Response(status_code=404)
        return FileResponse(path, media_type="image/png",
                            headers={"Cache-Control": "public, max-age=31536000, immutable",
                                     "Content-Security-Policy": "default-src 'none'"})

    app.include_router(launcher_router)
    app.include_router(yggdrasil_router)
    return app
