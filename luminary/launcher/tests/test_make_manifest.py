"""make_manifest.py: produces only manifests the launcher accepts, and verifies local and published packs."""
import json
import shutil
import threading
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

import make_manifest as mm
from luminary_app import modpack

FOLDERS = ("mods", "config")


class _Quiet(SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


@pytest.fixture
def mc(tmp_path):
    d = tmp_path / ".minecraft"
    (d / "mods").mkdir(parents=True)
    (d / "config" / "sub").mkdir(parents=True)
    (d / "mods" / "Sodium 0.5.3.jar").write_bytes(b"S" * 3000)
    (d / "mods" / "fabric-api.jar").write_bytes(b"F" * 5000)
    (d / "config" / "sub" / "a.toml").write_text("x = 1\n")
    (d / "config" / "empty.cfg").write_bytes(b"")
    (d / "saves").mkdir()
    (d / "saves" / "world.dat").write_bytes(b"not synced")
    return d


@pytest.fixture
def server(tmp_path):
    root = tmp_path / "srv"
    root.mkdir()
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), partial(_Quiet, directory=str(root)))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield root, f"http://127.0.0.1:{httpd.server_address[1]}/pack/"
    httpd.shutdown()


def publish(mc: Path, root: Path, manifest: dict) -> None:
    pack = root / "pack"
    for f in manifest["files"]:
        dst = pack / f["path"]
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(mc / f["path"], dst)
    (pack / "manifest.json").write_text(json.dumps(manifest))


def test_build_is_accepted_by_the_launcher_and_syncs_a_fresh_install(mc, server, tmp_path):
    manifest, problems, skipped = mm.build_manifest(mc, FOLDERS, "v-test")
    assert problems == []
    paths = [f["path"] for f in manifest["files"]]
    assert paths == sorted(paths) or set(paths) == {"mods/Sodium 0.5.3.jar", "mods/fabric-api.jar",
                                                  "config/sub/a.toml", "config/empty.cfg"}
    assert not any(p.startswith("saves/") for p in paths)
    root, url = server
    publish(mc, root, manifest)
    cfg = modpack.PackConfig(url, sync_folders=FOLDERS)
    fresh = tmp_path / "player" / ".minecraft"
    fresh.mkdir(parents=True)
    remote = modpack.fetch_manifest(cfg)
    cache = modpack.HashCache(fresh / modpack.HASH_CACHE)
    plan = modpack.make_plan(remote, fresh, FOLDERS, cache)
    modpack.apply_plan(plan, cfg, fresh, cache)
    for p in paths:
        assert (fresh / p).read_bytes() == (mc / p).read_bytes()
    assert modpack.local_version(fresh) == "v-test"
    assert mm.check_remote(cfg) == ([], 4)


@pytest.mark.parametrize("bad", ["CON.jar", "trailing.jar ", "colon:name.jar"])
def test_names_the_launcher_would_reject_block_the_build(mc, bad):
    (mc / "mods" / bad).write_bytes(b"x")
    manifest, problems, _ = mm.build_manifest(mc, FOLDERS)
    assert len(problems) == 1 and bad.strip() in problems[0]


def test_case_only_duplicates_block_the_build(mc):
    (mc / "mods" / "FABRIC-API.jar").write_bytes(b"dup")
    _, problems, _ = mm.build_manifest(mc, FOLDERS)
    assert len(problems) == 1 and "регистр" in problems[0]


def test_symlinks_block_and_temp_files_are_skipped(mc, tmp_path):
    (mc / "mods" / "half.jar.part").write_bytes(b"partial")
    (mc / "mods" / "Thumbs.db").write_bytes(b"junk")
    outside = tmp_path / "secret.txt"
    outside.write_text("outside the pack")
    (mc / "config" / "link.txt").symlink_to(outside)
    manifest, problems, skipped = mm.build_manifest(mc, FOLDERS)
    assert any("link.txt" in p for p in problems)
    assert "mods/half.jar.part" in skipped and "mods/Thumbs.db" in skipped


def test_cli_refuses_to_write_an_invalid_manifest(mc, tmp_path):
    cfg = tmp_path / "config_updater.json"
    cfg.write_text(json.dumps({"server_url": "https://example.org/pack/", "sync_folders": list(FOLDERS)}))
    out = tmp_path / "manifest.json"
    assert mm.main(["--config", str(cfg), "--mc-dir", str(mc), "--out", str(out)]) == 0
    assert json.loads(out.read_text())["files"]
    assert mm.main(["--config", str(cfg), "--mc-dir", str(mc), "--out", str(out), "--check"]) == 0
    (mc / "mods" / "CON.jar").write_bytes(b"x")
    before = out.read_text()
    assert mm.main(["--config", str(cfg), "--mc-dir", str(mc), "--out", str(out)]) == 1
    assert out.read_text() == before  # previous manifest untouched


def test_check_local_finds_changed_missing_and_unlisted_files(mc):
    manifest, _, _ = mm.build_manifest(mc, FOLDERS)
    assert mm.check_local(manifest, mc, FOLDERS) == []
    (mc / "config" / "sub" / "a.toml").write_text("x = 2\n")
    (mc / "mods" / "fabric-api.jar").unlink()
    (mc / "mods" / "new.jar").write_bytes(b"N")
    errors = mm.check_local(manifest, mc, FOLDERS)
    assert any("хеш" in e and "a.toml" in e for e in errors)
    assert any("нет файла" in e and "fabric-api.jar" in e for e in errors)
    assert any("new.jar" in e for e in errors)


def test_check_remote_detects_corrupt_and_missing_files(mc, server):
    root, url = server
    manifest, _, _ = mm.build_manifest(mc, FOLDERS)
    publish(mc, root, manifest)
    (root / "pack" / "mods" / "fabric-api.jar").write_bytes(b"F" * 4999 + b"X")   # same size, wrong hash
    (root / "pack" / "config" / "sub" / "a.toml").unlink()
    errors, n = mm.check_remote(modpack.PackConfig(url, sync_folders=FOLDERS))
    assert n == 4
    assert any("fabric-api.jar" in e and "хеш" in e for e in errors)
    assert any("a.toml" in e and "404" in e for e in errors)
    assert not any("empty.cfg" in e for e in errors)  # empty files are never fetched (servarium refuses them)


def test_check_remote_reports_unpublished_manifest(server):
    root, url = server
    (root / "pack").mkdir()
    with pytest.raises(modpack.PackError, match="manifest.json"):
        mm.check_remote(modpack.PackConfig(url, sync_folders=FOLDERS))
