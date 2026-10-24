"""Tests for tools/audit_scripts.py against a synthetic Far Cry folder (no game files)."""
import os
import sys
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import audit_scripts  # noqa: E402


def make_game(tmp):
    os.makedirs(os.path.join(tmp, "FCData"))
    with zipfile.ZipFile(os.path.join(tmp, "FCData", "Scripts.pak"), "w") as z:
        z.writestr("Scripts/Default/Entities/Trigger.lua",
                   "function T:OnEnter(p)\n  if (p == _localplayer) then Hud:AddMessage('x') end\n  -- _localplayer in a comment\nend\n")
        z.writestr("Scripts/Default/GameRules.lua", "if Game:IsMultiplayer() then end\n")
        z.writestr("Textures/readme.txt", "_localplayer not lua")
    lvl = os.path.join(tmp, "Levels", "pier")
    os.makedirs(lvl)
    with open(os.path.join(lvl, "Mission1.lua"), "w") as fh:
        fh.write("Game:TouchCheckPoint(1, pos, ang)\nGame:LoadLevel('research')\nGame:Save()\n")
    with zipfile.ZipFile(os.path.join(lvl, "level.pak"), "w") as z:
        z.writestr("cutscene.lua", "Movie:PlaySequence('intro')\n")
    os.makedirs(os.path.join(tmp, "Levels", "fort"))
    return tmp


def test_report_finds_each_hazard(tmp_path):
    game = make_game(str(tmp_path))
    report, hits, scanned = audit_scripts.build_report(game, None)
    keys = sorted(set(h[0] for h in hits))
    assert keys == ["checkpoint", "cutscene", "hud-on-server", "level-transition", "local-player", "mp-branch", "save-load"]
    assert scanned == 4                                  # three .lua plus one inside the level pak
    assert sum(1 for h in hits if h[0] == "local-player") == 1   # comment line ignored
    assert "Levels/pier/Mission1.lua:2" in report and "| level-transition | 1 |" in report
    assert "readme" not in report


def test_level_filter(tmp_path):
    game = make_game(str(tmp_path))
    _, hits, _ = audit_scripts.build_report(game, "fort")
    assert all(not h[1].startswith("Levels/pier") for h in hits)
