import json

import pytest

from luminary_app import profiles, settings, system


def test_migrates_legacy_files_without_deleting_them(paths):
    paths.legacy_nick.write_text("Sh1p0vn1chek", encoding="utf-8")
    paths.legacy_ram.write_text("6G", encoding="utf-8")
    s = settings.load(paths)
    assert s.nickname == "Sh1p0vn1chek" and s.ram_mb == 6144
    assert paths.legacy_nick.exists() and paths.legacy_ram.exists()


def test_invalid_legacy_values_ignored(paths):
    paths.legacy_nick.write_text("bad nick!", encoding="utf-8")
    paths.legacy_ram.write_text("lots", encoding="utf-8")
    s = settings.load(paths)
    assert s.nickname == "" and s.ram_mb == 4096


def test_roundtrip_and_atomic_save(paths):
    s = settings.Settings(nickname="Steve", ram_mb=8192, sounds=False, after_launch="keep")
    settings.save(paths, s)
    assert settings.load(paths) == s
    assert not paths.settings_file.with_suffix(".tmp").exists()


def test_corrupt_and_invalid_settings_fall_back(paths):
    paths.settings_file.write_text("{not json", encoding="utf-8")
    assert settings.load(paths) == settings.Settings()
    assert paths.settings_file.with_suffix(".broken.json").exists()
    paths.settings_file.write_text(json.dumps({"ram_mb": "lots", "after_launch": "explode", "sounds": "yes",
                                               "jvm_args": "-Xmx99G", "nickname": "a b"}), encoding="utf-8")
    s = settings.load(paths)
    assert s.ram_mb == 4096 and s.after_launch == "minimize" and s.sounds is True and s.jvm_args == "" and s.nickname == ""


@pytest.mark.parametrize("text,ok", [
    ("", True), ("-XX:+UseG1GC -Dfoo=bar", True), ("-Xmx8G", False), ("-javaagent:x.jar", False),
    ("notaflag", False), ('-Dpath="unterminated', False), ("-Dauthlibinjector.debug", False),
])
def test_jvm_args_validation(text, ok):
    assert (settings.validate_jvm_args(text) is None) == ok


def test_nickname_validation():
    assert settings.validate_nickname("Steve_1") is None
    for bad in ("ab", "a" * 17, "Стив", "a-b", ""):
        assert settings.validate_nickname(bad)


def test_ram_choices_respect_physical_memory():
    assert system.ram_choices(8192) == [2048, 3072, 4096, 6144]
    assert system.ram_choices(3000) == [2048]
    assert system.ram_choices(None)[-1] == 8192
    assert system.clamp_ram(8192, 8192) == 6144
    assert system.clamp_ram(5000, 16384) == 4096
    assert isinstance(system.total_ram_mb(), int)


def test_profiles_file_matches_launcher_1x_behaviour(paths):
    shipped = profiles.load_catalog(paths.base.parent / "missing.json")
    p = shipped.get(None)
    assert p.portablemc_version() == "fabric:1.20.1:0.19.5"  # the loader the pack was tested on
    assert (p.server.host, p.server.port) == ("135.106.221.145", 27195)
    from conftest import ROOT
    real = profiles.load_catalog(ROOT / "profiles.json")
    assert real.get("luminary") == p
    assert real.authlib.sha256 == "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10"
    assert not real.accounts_enabled  # off until the owner deploys the backend


def test_profile_validation():
    base = json.loads(json.dumps(profiles.FALLBACK))
    base["profiles"].append({"id": "../evil", "minecraft": "1.20.1"})
    base["profiles"].append({"id": "x", "minecraft": "1.20.1", "java": {"windows": "C:/Windows/evil.exe"}})
    base["profiles"].append({"id": "q", "minecraft": "1.21.1", "loader": {"type": "quilt", "version": "0.26.0"}})
    c = profiles.parse_catalog(base)
    assert [p.id for p in c.profiles] == ["luminary", "q"] and len(c.warnings) == 2
    assert c.get("q").portablemc_version() == "quilt:1.21.1:0.26.0"
    with pytest.raises(profiles.ProfileError):
        profiles.validate_api_url("http://auth.example.org")
    assert profiles.validate_api_url("https://auth.example.org/") == "https://auth.example.org"
    assert profiles.validate_api_url("http://localhost:8080") == "http://localhost:8080"
