import hashlib
import json
import threading
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from luminary_app import modpack
from luminary_app.modpack import PackConfig, PackError, safe_relpath

FOLDERS = ("mods", "config")


class RangeHandler(SimpleHTTPRequestHandler):
    """Static files with single-range support; can be told to cut a transfer short."""
    fail_after: dict = {}
    requests: list = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        RangeHandler.requests.append((self.path, self.headers.get("Range")))
        path = Path(self.translate_path(self.path))
        if not path.is_file():
            self.send_error(404)
            return
        data = path.read_bytes()
        start = 0
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            start = int(rng[6:].split("-")[0])
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{len(data) - 1}/{len(data)}")
        else:
            self.send_response(200)
        body = data[start:]
        cut = RangeHandler.fail_after.pop(self.path, None)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body if cut is None else body[:cut])


@pytest.fixture
def server(tmp_path):
    root = tmp_path / "srv"
    root.mkdir()
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), partial(RangeHandler, directory=str(root)))
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    RangeHandler.requests.clear()
    RangeHandler.fail_after.clear()
    yield root, f"http://127.0.0.1:{httpd.server_address[1]}/pack/"
    httpd.shutdown()


def publish(root: Path, files: dict[str, bytes], version="v1", folders=None):
    pack = root / "pack"
    if pack.exists():
        import shutil
        shutil.rmtree(pack)
    entries = []
    for rel, data in files.items():
        p = pack / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        entries.append({"path": rel, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
    manifest = {"version": version, "files": entries}
    if folders is not None:
        manifest["folders"] = folders
    (pack / "manifest.json").write_text(json.dumps(manifest))


def sync(cfg, mc):
    m = modpack.fetch_manifest(cfg)
    cache = modpack.HashCache(mc / modpack.HASH_CACHE)
    plan = modpack.make_plan(m, mc, cfg.folders, cache)
    return plan, modpack.apply_plan(plan, cfg, mc, cache, retries=2)


@pytest.mark.parametrize("bad", [
    "../evil.jar", "mods/../../evil.jar", "/etc/passwd", "C:/Windows/x.dll", "mods\\..\\x", "saves/world.dat",
    "mods", "mods//x.jar", "mods/./x.jar", "mods/CON.jar", "mods/x.jar ", "", "mods/\x00.jar",
])
def test_unsafe_manifest_paths_rejected(bad):
    with pytest.raises(PackError):
        safe_relpath(bad, FOLDERS)


def test_safe_paths_kept():
    assert safe_relpath("mods/Some Mod 1.2.jar", FOLDERS) == "mods/Some Mod 1.2.jar"
    assert safe_relpath("config/sub/a.toml", FOLDERS) == "config/sub/a.toml"


def test_manifest_with_traversal_is_refused_entirely(server, tmp_path):
    root, url = server
    (root / "pack").mkdir()
    (root / "pack" / "manifest.json").write_text(json.dumps({"files": [
        {"path": "mods/ok.jar", "sha256": "0" * 64, "size": 1},
        {"path": "mods/../../../evil.bat", "sha256": "0" * 64, "size": 1}]}))
    with pytest.raises(PackError):
        modpack.fetch_manifest(PackConfig(url, sync_folders=FOLDERS))


def test_config_requires_https(tmp_path):
    p = tmp_path / "c.json"
    p.write_text(json.dumps({"server_url": "http://example.org/pack/", "sync_folders": ["mods"]}))
    with pytest.raises(PackError):
        modpack.load_config(p)
    p.write_text(json.dumps({"server_url": "https://example.org/pack/", "sync_folders": ["../x"]}))
    with pytest.raises(PackError):
        modpack.load_config(p)


def test_shipped_config_loads():
    from conftest import ROOT
    cfg = modpack.load_config(ROOT / "config_updater.json")
    assert cfg.manifest_url.endswith("/manifest.json") and "mods" in cfg.sync_folders


def test_full_sync_update_and_backup(server, tmp_path):
    root, url = server
    mc = tmp_path / "mc"
    (mc / "mods").mkdir(parents=True)
    (mc / "mods" / "user-extra.jar").write_bytes(b"mine")
    publish(root, {"mods/a mod.jar": b"A" * 1000, "config/a.toml": b"x=1"})
    cfg = PackConfig(url, sync_folders=FOLDERS)
    plan, (down, deleted) = sync(cfg, mc)
    assert (down, deleted) == (2, 1)
    assert (mc / "mods" / "a mod.jar").read_bytes() == b"A" * 1000
    backups = list((mc / ".backup").rglob("user-extra.jar"))
    assert len(backups) == 1 and backups[0].read_bytes() == b"mine"
    assert modpack.local_version(mc) == "v1"
    assert not (mc / modpack.STAGING).exists()

    # Second run: nothing to do, and hashes come from the cache (no file re-read needed).
    m = modpack.fetch_manifest(cfg)
    cache = modpack.HashCache(mc / modpack.HASH_CACHE)
    plan = modpack.make_plan(m, mc, FOLDERS, cache)
    assert plan.empty and plan.up_to_date == 2 and not cache.dirty

    # A locally modified mod is restored from the server (mods is a mirror folder)...
    (mc / "mods" / "a mod.jar").write_bytes(b"B" * 1000)
    # ...but a config the player (or a mod) changed is left alone while the server's copy is unchanged.
    (mc / "config" / "a.toml").write_bytes(b"x=2")
    plan, (down, _) = sync(cfg, mc)
    assert down == 1 and plan.kept_local == ["config/a.toml"]
    assert (mc / "mods" / "a mod.jar").read_bytes() == b"A" * 1000
    assert (mc / "config" / "a.toml").read_bytes() == b"x=2"

    # When the server changes that config, the new one is installed and the player's edit is backed up.
    publish(root, {"mods/a mod.jar": b"A" * 1000, "config/a.toml": b"x=3"}, version="v2")
    plan, (down, _) = sync(cfg, mc)
    assert down == 1 and plan.backup_first == {"config/a.toml"}
    assert (mc / "config" / "a.toml").read_bytes() == b"x=3"
    assert [b.read_bytes() for b in (mc / ".backup").rglob("a.toml")] == [b"x=2"]


def test_manifest_folders_decide_what_syncs(server, tmp_path):
    root, url = server
    mc = tmp_path / "mc"
    (mc / "resourcepacks").mkdir(parents=True)
    (mc / "resourcepacks" / "player-own.zip").write_bytes(b"mine")
    (mc / "config").mkdir()
    (mc / "config" / "player.toml").write_bytes(b"p")  # config is not synced by this manifest
    files = {"mods/a.jar": b"A", "resourcepacks/pack.zip": b"RP1", "shaderpacks/s.zip": b"S"}
    folders = {"mods": "mirror", "resourcepacks": "update", "shaderpacks": "update"}
    publish(root, files, folders=folders)
    cfg = PackConfig(url, sync_folders=FOLDERS)  # the launcher's own list knows neither folder
    plan, (down, deleted) = sync(cfg, mc)
    assert (down, deleted) == (3, 0)
    assert (mc / "resourcepacks" / "player-own.zip").read_bytes() == b"mine"
    assert (mc / "shaderpacks" / "s.zip").read_bytes() == b"S"
    assert (mc / "config" / "player.toml").exists()
    assert modpack.local_manifest(mc, cfg.folders).folders == folders

    # The owner drops shaderpacks and one resource pack: only the files the launcher installed go.
    publish(root, {"mods/a.jar": b"A"}, version="v2", folders={"mods": "mirror", "resourcepacks": "update"})
    plan, (down, deleted) = sync(cfg, mc)
    assert (down, deleted) == (0, 2)
    assert not (mc / "shaderpacks" / "s.zip").exists() and not (mc / "resourcepacks" / "pack.zip").exists()
    assert (mc / "resourcepacks" / "player-own.zip").read_bytes() == b"mine"
    assert sorted(p.name for p in (mc / ".backup").rglob("*.zip")) == ["pack.zip", "s.zip"]


@pytest.mark.parametrize("folders", [
    {"saves": "update"}, {"versions": "mirror"}, {"Libraries": "update"}, {".bobby": "update"},
    {"../x": "update"}, {"a/b": "update"}, {"con": "update"}, {"mods": "wipe"}, {}, [], "mods",
    {"mods": "mirror", "MODS": "update"}, {"mods": "mirror", ".luminary_staging": "update"},
])
def test_bad_manifest_folders_are_rejected(folders):
    with pytest.raises(PackError):
        modpack.parse_manifest({"files": [], "folders": folders}, FOLDERS)


def test_manifest_without_folders_uses_launcher_list():
    m = modpack.parse_manifest({"files": [{"path": "config/a", "sha256": "0" * 64, "size": 0}]}, FOLDERS)
    assert m.folders == {"mods": "mirror", "config": "update"}
    with pytest.raises(PackError):
        modpack.parse_manifest({"files": [{"path": "resourcepacks/a", "sha256": "0" * 64, "size": 0}]}, FOLDERS)


def test_config_folder_modes(tmp_path):
    p = tmp_path / "c.json"
    p.write_text(json.dumps({"server_url": "https://x/", "sync_folders": ["mods", "config", "kubejs"],
                             "folder_modes": {"kubejs": "mirror"}}))
    assert modpack.load_config(p).folders == {"mods": "mirror", "config": "update", "kubejs": "mirror"}
    p.write_text(json.dumps({"server_url": "https://x/", "sync_folders": ["saves"]}))
    with pytest.raises(PackError):
        modpack.load_config(p)


def test_hash_mismatch_never_reaches_the_pack(server, tmp_path):
    root, url = server
    mc = tmp_path / "mc"
    publish(root, {"mods/a.jar": b"good"})
    (root / "pack" / "mods" / "a.jar").write_bytes(b"evil")  # server file no longer matches manifest
    with pytest.raises(PackError):
        sync(PackConfig(url, sync_folders=FOLDERS), mc)
    assert not (mc / "mods" / "a.jar").exists()


def test_interrupted_download_leaves_pack_untouched_and_resumes(server, tmp_path):
    root, url = server
    mc = tmp_path / "mc"
    publish(root, {"mods/a.jar": b"old"}, version="v1")
    cfg = PackConfig(url, sync_folders=FOLDERS)
    sync(cfg, mc)
    big = bytes(range(256)) * 8192  # 2 MiB: several 256 KiB chunks
    publish(root, {"mods/a.jar": b"new", "mods/big.jar": big}, version="v2")

    cancel = threading.Event()
    m = modpack.fetch_manifest(cfg)
    cache = modpack.HashCache(mc / modpack.HASH_CACHE)
    plan = modpack.make_plan(m, mc, FOLDERS, cache)
    seen = []

    def progress(done, total, msg):
        seen.append(msg)
        if done > 300_000:
            cancel.set()

    with pytest.raises(modpack.Cancelled):
        modpack.apply_plan(plan, cfg, mc, cache, progress=progress, cancel=cancel)
    # Nothing swapped in: old file intact, new file absent, version unchanged.
    assert (mc / "mods" / "a.jar").read_bytes() == b"old"
    assert not (mc / "mods" / "big.jar").exists()
    assert modpack.local_version(mc) == "v1"
    partial_file = mc / modpack.STAGING / "mods" / "big.jar.part"
    assert partial_file.exists() and 0 < partial_file.stat().st_size < len(big)

    RangeHandler.requests.clear()
    sync(cfg, mc)
    assert (mc / "mods" / "big.jar").read_bytes() == big
    assert any(r == "/pack/mods/big.jar" and rng and rng != "bytes=0-" for r, rng in RangeHandler.requests)
    assert modpack.local_version(mc) == "v2"


def test_truncated_transfer_is_retried(server, tmp_path):
    root, url = server
    mc = tmp_path / "mc"
    publish(root, {"mods/a.jar": b"Z" * 5000})
    RangeHandler.fail_after["/pack/mods/a.jar"] = 100
    sync(PackConfig(url, sync_folders=FOLDERS), mc)
    assert (mc / "mods" / "a.jar").read_bytes() == b"Z" * 5000


def test_missing_manifest_is_a_clear_error(server):
    root, url = server
    with pytest.raises(PackError, match="manifest.json"):
        modpack.fetch_manifest(PackConfig(url, sync_folders=FOLDERS))


def test_zero_byte_files_sync(server, tmp_path):
    """Regression: empty files (common in config folders) used to abort every update."""
    root, url = server
    mc = tmp_path / "mc"
    publish(root, {"config/empty.cfg": b"", "mods/a.jar": b"A"})
    sync(PackConfig(url, sync_folders=FOLDERS), mc)
    assert (mc / "config" / "empty.cfg").is_file() and (mc / "config" / "empty.cfg").stat().st_size == 0


def _entries(files):
    return [{"path": r, "sha256": hashlib.sha256(d).hexdigest(), "size": len(d)} for r, d in files.items()]


def test_extra_manifests_merge_into_one_pack(server, tmp_path):
    """manifest.json keeps only folders every launcher knows; 2.1.3+ also read manifest-extra.json."""
    root, url = server
    publish(root, {"mods/a.jar": b"A", "config/c.toml": b"c", "resourcepacks/rp.zip": b"RP"}, version="v1",
            folders={"mods": "mirror", "config": "update"})
    pack = root / "pack"
    base = json.loads((pack / "manifest.json").read_text())
    base["files"] = [e for e in base["files"] if not e["path"].startswith("resourcepacks/")]
    base["extra_manifests"] = ["manifest-extra.json"]
    (pack / "manifest.json").write_text(json.dumps(base))
    (pack / "manifest-extra.json").write_text(json.dumps({"version": "v1", "folders": {"resourcepacks": "update"},
                                                          "files": _entries({"resourcepacks/rp.zip": b"RP"})}))
    mc = tmp_path / "mc"
    cfg = PackConfig(url, sync_folders=("mods", "config", "defaultconfigs", "patchouli_books", "fancymenu_data"))
    m = modpack.fetch_manifest(cfg)
    assert m.folders == {"mods": "mirror", "config": "update", "resourcepacks": "update"}
    assert sorted(f.path for f in m.files) == ["config/c.toml", "mods/a.jar", "resourcepacks/rp.zip"]
    plan, (down, _) = sync(cfg, mc)
    assert down == 3 and (mc / "resourcepacks" / "rp.zip").read_bytes() == b"RP"
    # the saved local manifest is the merged pack, so the next sync knows what it installed
    assert modpack.local_manifest(mc, cfg.folders).folders == m.folders
    assert sync(cfg, mc)[0].empty
    # a launcher that predates extra manifests reads manifest.json alone and accepts it (only classic folders)
    assert modpack.parse_manifest(base, cfg.sync_folders).files


@pytest.mark.parametrize("extras", [["../x.json"], ["a/b.json"], ["x.txt"], ["manifest.json"],
                                    ["a.json", "A.json"], "extra.json", [f"e{i}.json" for i in range(9)]])
def test_bad_extra_manifest_names_are_rejected(extras):
    with pytest.raises(PackError):
        modpack.extra_names({"extra_manifests": extras})


def test_extra_manifest_clashes_and_missing_files_fail(server, tmp_path):
    root, url = server
    publish(root, {"mods/a.jar": b"A"}, folders={"mods": "mirror"})
    pack = root / "pack"
    base = json.loads((pack / "manifest.json").read_text())
    base["extra_manifests"] = ["manifest-extra.json"]
    (pack / "manifest.json").write_text(json.dumps(base))
    cfg = PackConfig(url, sync_folders=FOLDERS)
    with pytest.raises(PackError, match="manifest-extra.json"):   # listed but not uploaded
        modpack.fetch_manifest(cfg)
    (pack / "manifest-extra.json").write_text(json.dumps({"folders": {"mods": "mirror"},
                                                          "files": _entries({"mods/a.jar": b"A"})}))
    with pytest.raises(PackError, match="уже указан"):           # same file in both
        modpack.fetch_manifest(cfg)
    (pack / "manifest-extra.json").write_text(json.dumps({"folders": {"mods": "update"}, "files": []}))
    with pytest.raises(PackError, match="другой режим"):         # same folder, different mode
        modpack.fetch_manifest(cfg)


def test_plan_is_blocking_only_when_mods_change(tmp_path):
    folders = {"mods": "mirror", "config": "update"}
    m = modpack.parse_manifest({"folders": folders, "files": _entries({"mods/a.jar": b"A", "config/c": b"c"})},
                               FOLDERS)
    cache = modpack.HashCache(tmp_path / "cache.json")
    mc = tmp_path / "mc"
    assert modpack.make_plan(m, mc, folders, cache, previous=None).blocking      # nothing installed yet
    (mc / "mods").mkdir(parents=True)
    (mc / "mods" / "a.jar").write_bytes(b"A")
    assert not modpack.make_plan(m, mc, folders, cache, previous=None).blocking  # only config differs
    (mc / "mods" / "extra.jar").write_bytes(b"X")
    assert modpack.make_plan(m, mc, folders, cache, previous=None).blocking      # a mod would be removed
