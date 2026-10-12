import io
import json
import zipfile

import pytest

from luminary_app import mods


def jar(path, meta=None, nested=None, raw=None):
    with zipfile.ZipFile(path, "w") as z:
        if raw is not None:
            z.writestr("fabric.mod.json", raw)
        elif meta is not None:
            z.writestr("fabric.mod.json", json.dumps(meta))
        for name, inner_meta in (nested or {}).items():
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w") as iz:
                iz.writestr("fabric.mod.json", json.dumps(inner_meta))
            z.writestr(name, buf.getvalue())
        z.writestr("dummy.class", b"\xca\xfe")


@pytest.mark.parametrize("pred,ver,expected", [
    ("*", "1.20.1", True), ("1.20.1", "1.20.1", True), ("1.20.1", "1.20.2", False),
    (">=1.20", "1.20.1", True), (">=1.20 <1.21", "1.21", False), ("~1.20", "1.20.4", True),
    ("~1.20.1", "1.21", False), ("^1.19", "1.20.1", True), ("1.20.x", "1.20.1", True), ("1.19.x", "1.20.1", False),
    ([">=1.21", "1.20.1"], "1.20.1", True), (">=1.20-alpha.1", "1.20.1", True), ("~1.20.1-beta", "1.20.1", True),
    ("weird", "1.20.1", None),
])
def test_predicates(pred, ver, expected):
    assert mods.matches(pred, ver) is expected


def test_scan_reports_metadata_issues_and_sync_state(tmp_path):
    d = tmp_path / "mods"
    d.mkdir()
    jar(d / "fabric-api.jar", {"id": "fabric-api", "name": "Fabric API", "version": "0.92.2+1.20.1"},
        nested={"META-INF/jars/fabric-api-base.jar": {"id": "fabric-api-base", "version": "0.4.31"}})
    meta = {"id": "sodium", "name": "Sodium", "version": "0.5.3", "authors": ["jellysquid3", {"name": "IMS"}],
            "depends": {"minecraft": "1.20.1", "fabricloader": ">=0.12", "fabric-api-base": "*"}}
    meta["jars"] = []
    jar(d / "sodium.jar", meta)
    jar(d / "future.jar", {"id": "future", "version": "1", "depends": {"minecraft": ">=1.21", "cloth-config": "*"}})
    jar(d / "notfabric.jar")
    (d / "broken.jar").write_bytes(b"not a zip")
    jar(d / "newline.jar", raw='{"id": "nl", "version": "1", "description": "line one\nline two"}')

    # fabric-api lists its nested jar
    with zipfile.ZipFile(d / "fabric-api.jar", "a") as z:
        pass

    hashes = {"mods/sodium.jar": "aaa", "mods/fabric-api.jar": "bbb"}
    result = {m.file: m for m in mods.scan(d, "1.20.1", hashes, lambda rel, p: "aaa")}
    s = result["sodium.jar"]
    assert s.title == "Sodium" and s.authors == ["jellysquid3", "IMS"] and s.sync_state == "in_pack"
    assert result["fabric-api.jar"].sync_state == "modified"
    assert result["future.jar"].sync_state == "extra"
    assert any("1.21" in i for i in result["future.jar"].issues)
    assert any("cloth-config" in i for i in result["future.jar"].issues)
    assert result["notfabric.jar"].error and result["broken.jar"].error
    assert result["newline.jar"].description.startswith("line one")


def test_nested_jar_provides_dependency(tmp_path):
    d = tmp_path / "mods"
    d.mkdir()
    jar(d / "fabric-api.jar", {"id": "fabric-api", "version": "1",
                               "jars": [{"file": "META-INF/jars/base.jar"}]},
        nested={"META-INF/jars/base.jar": {"id": "fabric-api-base", "version": "1"}})
    jar(d / "uses.jar", {"id": "uses", "version": "1", "depends": {"fabric-api-base": "*"}})
    result = {m.file: m for m in mods.scan(d, "1.20.1")}
    assert result["uses.jar"].issues == []
