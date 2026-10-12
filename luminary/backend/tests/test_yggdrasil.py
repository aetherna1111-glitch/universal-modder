import base64
import json
import sqlite3
import time

from conftest import auth, make_skin, register

from luminary_auth import security


def _game(client, token):
    r = client.post("/api/v1/game/token", headers=auth(token))
    assert r.status_code == 200
    return r.json()


def test_metadata(client, app):
    m = client.get("/yggdrasil/").json()
    assert m["signaturePublickey"].startswith("-----BEGIN PUBLIC KEY-----")
    assert "localhost" in m["skinDomains"]
    assert m["meta"]["feature.no_mojang_namespace"] is True
    assert client.get("/yggdrasil/").headers["X-Authlib-Injector-API-Location"] == "/yggdrasil/"
    assert "PRIVATE" not in json.dumps(m)


def test_full_join_flow_with_signed_textures(client, app):
    token = register(client)["access_token"]
    client.put("/api/v1/me/skin?model=slim", content=make_skin(slim=True), headers=auth(token))
    game = _game(client, token)
    uuid = security.offline_uuid("Steve")
    r = client.post("/yggdrasil/sessionserver/session/minecraft/join",
                    json={"accessToken": game["access_token"], "selectedProfile": uuid, "serverId": "-6f3a2b"})
    assert r.status_code == 204, r.text

    # The Minecraft server (with authlib-injector) verifies the join...
    r = client.get("/yggdrasil/sessionserver/session/minecraft/hasJoined",
                   params={"username": "Steve", "serverId": "-6f3a2b"})
    assert r.status_code == 200
    prof = r.json()
    assert prof["id"] == uuid and prof["name"] == "Steve"
    tex = next(p for p in prof["properties"] if p["name"] == "textures")
    assert app.state.signer.verify(tex["value"], tex["signature"])
    payload = json.loads(base64.b64decode(tex["value"]))
    assert payload["profileId"] == uuid
    assert payload["textures"]["SKIN"]["metadata"] == {"model": "slim"}
    skin_url = payload["textures"]["SKIN"]["url"]
    assert skin_url.startswith("http://localhost:8080/textures/")

    # ...and a second client fetches the same signed profile and the PNG.
    other = client.get(f"/yggdrasil/sessionserver/session/minecraft/profile/{uuid}", params={"unsigned": "false"}).json()
    tex2 = next(p for p in other["properties"] if p["name"] == "textures")
    assert app.state.signer.verify(tex2["value"], tex2["signature"])
    png = client.get(skin_url.replace("http://localhost:8080", "")).content
    assert png.startswith(b"\x89PNG")

    # A join can be consumed once only.
    r = client.get("/yggdrasil/sessionserver/session/minecraft/hasJoined",
                   params={"username": "Steve", "serverId": "-6f3a2b"})
    assert r.status_code == 204


def test_cannot_join_as_someone_else(client):
    steve = register(client, "Steve")["access_token"]
    register(client, "Alex", "another good pw")
    game = _game(client, steve)
    r = client.post("/yggdrasil/sessionserver/session/minecraft/join",
                    json={"accessToken": game["access_token"], "selectedProfile": security.offline_uuid("Alex"),
                          "serverId": "abc"})
    assert r.status_code == 403 and r.json()["error"] == "ForbiddenOperationException"
    r = client.get("/yggdrasil/sessionserver/session/minecraft/hasJoined", params={"username": "Alex", "serverId": "abc"})
    assert r.status_code == 204


def test_forged_or_session_token_cannot_join(client):
    token = register(client)["access_token"]
    uuid = security.offline_uuid("Steve")
    for bad in ("forged", token):  # a launcher session token is not a game token
        r = client.post("/yggdrasil/sessionserver/session/minecraft/join",
                        json={"accessToken": bad, "selectedProfile": uuid, "serverId": "abc"})
        assert r.status_code == 403


def test_has_joined_wrong_server_id_ip_and_expiry(client, settings):
    token = register(client)["access_token"]
    game = _game(client, token)
    uuid = security.offline_uuid("Steve")
    client.post("/yggdrasil/sessionserver/session/minecraft/join",
                json={"accessToken": game["access_token"], "selectedProfile": uuid, "serverId": "s1"})
    assert client.get("/yggdrasil/sessionserver/session/minecraft/hasJoined",
                      params={"username": "Steve", "serverId": "other"}).status_code == 204
    assert client.get("/yggdrasil/sessionserver/session/minecraft/hasJoined",
                      params={"username": "Steve", "serverId": "s1", "ip": "192.0.2.1"}).status_code == 204
    conn = sqlite3.connect(settings.db_path)
    conn.execute("UPDATE joins SET created_at = ?", (int(time.time()) - 60,))
    conn.commit()
    assert client.get("/yggdrasil/sessionserver/session/minecraft/hasJoined",
                      params={"username": "Steve", "serverId": "s1"}).status_code == 204


def test_authenticate_refresh_validate_invalidate(client):
    register(client)
    r = client.post("/yggdrasil/authserver/authenticate",
                    json={"username": "Steve", "password": "correct horse battery", "clientToken": "ct1",
                          "agent": {"name": "Minecraft", "version": 1}})
    assert r.status_code == 200
    body = r.json()
    assert body["selectedProfile"]["name"] == "Steve" and body["clientToken"] == "ct1"
    at = body["accessToken"]
    assert client.post("/yggdrasil/authserver/validate", json={"accessToken": at, "clientToken": "other"}).status_code == 403
    r = client.post("/yggdrasil/authserver/refresh", json={"accessToken": at, "clientToken": "ct1"})
    new = r.json()["accessToken"]
    assert client.post("/yggdrasil/authserver/validate", json={"accessToken": at}).status_code == 403
    assert client.post("/yggdrasil/authserver/validate", json={"accessToken": new}).status_code == 204
    client.post("/yggdrasil/authserver/invalidate", json={"accessToken": new, "clientToken": "ct1"})
    assert client.post("/yggdrasil/authserver/validate", json={"accessToken": new}).status_code == 403


def test_authenticate_wrong_password(client):
    register(client)
    r = client.post("/yggdrasil/authserver/authenticate", json={"username": "Steve", "password": "nope nope"})
    assert r.status_code == 403 and r.json()["error"] == "ForbiddenOperationException"


def test_profile_lookup_endpoints(client):
    register(client)
    uuid = security.offline_uuid("Steve")
    r = client.post("/yggdrasil/api/profiles/minecraft", json=["steve", "nobody", "STEVE"])
    assert r.json() == [{"id": uuid, "name": "Steve"}]
    assert client.get("/yggdrasil/api/users/profiles/minecraft/Steve").json()["id"] == uuid
    assert client.get("/yggdrasil/api/users/profiles/minecraft/Nobody").status_code == 204
    unsigned = client.get(f"/yggdrasil/sessionserver/session/minecraft/profile/{uuid}").json()
    assert all("signature" not in p for p in unsigned["properties"])
    assert client.get("/yggdrasil/sessionserver/session/minecraft/profile/not-a-uuid").status_code == 204
    r = client.post("/yggdrasil/api/profiles/minecraft", json=["x"] * 101)
    assert r.status_code == 400


def test_has_joined_sets_playing_presence_for_friends(client):
    steve = register(client, "Steve")["access_token"]
    alex = register(client, "Alex", "another good pw")["access_token"]
    client.post("/api/v1/friends/requests", json={"username": "Alex"}, headers=auth(steve))
    req = client.get("/api/v1/friends", headers=auth(alex)).json()["incoming"][0]
    client.post(f"/api/v1/friends/requests/{req['id']}/accept", headers=auth(alex))
    game = _game(client, steve)
    client.post("/yggdrasil/sessionserver/session/minecraft/join",
                json={"accessToken": game["access_token"], "selectedProfile": security.offline_uuid("Steve"),
                      "serverId": "z"})
    client.get("/yggdrasil/sessionserver/session/minecraft/hasJoined", params={"username": "Steve", "serverId": "z"})
    friends = client.get("/api/v1/friends", headers=auth(alex)).json()["friends"]
    assert friends[0]["name"] == "Steve" and friends[0]["presence"] == "playing"
