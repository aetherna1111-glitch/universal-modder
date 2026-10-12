import sqlite3
import time

from conftest import auth, register

from luminary_auth import security


def test_offline_uuid_matches_java_name_uuid_from_bytes():
    # Java: UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(UTF_8))
    assert security.dashed(security.offline_uuid("Notch")) == "b50ad385-829d-3141-a216-7e7d7539ba7f"


def test_register_login_me_logout(client):
    body = register(client)
    assert body["profile"]["name"] == "Steve"
    assert body["profile"]["uuid"] == security.offline_uuid("Steve")
    assert "password" not in str(body).lower()
    token = body["access_token"]
    me = client.get("/api/v1/me", headers=auth(token))
    assert me.status_code == 200 and me.json()["username"] == "Steve"

    login = client.post("/api/v1/auth/login", json={"username": "steve", "password": "correct horse battery"})
    assert login.status_code == 200
    token2 = login.json()["access_token"]
    assert token2 != token

    assert client.post("/api/v1/auth/logout", headers=auth(token)).status_code == 204
    assert client.get("/api/v1/me", headers=auth(token)).status_code == 401
    assert client.get("/api/v1/me", headers=auth(token2)).status_code == 200


def test_password_hash_is_argon2id_and_tokens_are_hashed(client, settings):
    token = register(client)["access_token"]
    conn = sqlite3.connect(settings.db_path)
    pw_hash = conn.execute("SELECT password_hash FROM users").fetchone()[0]
    assert pw_hash.startswith("$argon2id$")
    stored = [r[0] for r in conn.execute("SELECT token_hash FROM tokens")]
    assert token not in stored and security.token_hash(token) in stored


def test_registration_validation(client):
    bad = [("ab", "longenoughpw"), ("bad name", "longenoughpw"), ("Привет", "longenoughpw"),
           ("Alex", "short"), ("Alex", "alex"), ("Alex", "12345678"), ("Alex", "x" * 200)]
    for name, pw in bad:
        r = client.post("/api/v1/auth/register", json={"username": name, "password": pw})
        assert r.status_code == 400, (name, pw, r.text)
        assert "message" in r.json()
        assert pw not in r.text  # never echo passwords


def test_duplicate_and_reserved_names(client, settings):
    register(client, "Steve")
    r = client.post("/api/v1/auth/register", json={"username": "STEVE", "password": "another good pw"})
    assert r.status_code == 409
    conn = sqlite3.connect(settings.db_path)
    conn.execute("INSERT INTO reserved_names(name_lower, note, created_at) VALUES ('alex', '', 0)")
    conn.commit()
    r = client.post("/api/v1/auth/register", json={"username": "Alex", "password": "another good pw"})
    assert r.status_code == 409


def test_registration_can_be_closed(client, settings):
    settings.registration_open = False
    r = client.post("/api/v1/auth/register", json={"username": "Steve", "password": "correct horse battery"})
    assert r.status_code == 403


def test_login_does_not_reveal_whether_account_exists(client):
    register(client)
    a = client.post("/api/v1/auth/login", json={"username": "Steve", "password": "wrong password!"})
    b = client.post("/api/v1/auth/login", json={"username": "Nobody", "password": "wrong password!"})
    assert a.status_code == b.status_code == 401
    assert a.json() == b.json()


def test_account_lockout_after_repeated_failures(app):
    from fastapi.testclient import TestClient
    register(TestClient(app, client=("198.51.100.1", 1)))
    # Failures spread over several IPs (credential stuffing) still lock the account.
    for i in range(10):
        c = TestClient(app, client=(f"198.51.100.{i + 10}", 1))
        assert c.post("/api/v1/auth/login", json={"username": "Steve", "password": "wrong password!"}).status_code == 401
    c = TestClient(app, client=("198.51.100.99", 1))
    r = c.post("/api/v1/auth/login", json={"username": "Steve", "password": "correct horse battery"})
    assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0


def test_per_ip_login_limit(client):
    for _ in range(20):
        client.post("/api/v1/auth/login", json={"username": "Nobody", "password": "whatever123"})
    r = client.post("/api/v1/auth/login", json={"username": "Nobody", "password": "whatever123"})
    assert r.status_code == 429


def test_expired_session_is_rejected(client, settings):
    token = register(client)["access_token"]
    conn = sqlite3.connect(settings.db_path)
    conn.execute("UPDATE tokens SET expires_at = ?", (int(time.time()) - 1,))
    conn.commit()
    assert client.get("/api/v1/me", headers=auth(token)).status_code == 401


def test_refresh_rotates_session(client):
    token = register(client)["access_token"]
    r = client.post("/api/v1/auth/refresh", headers=auth(token))
    assert r.status_code == 200
    new = r.json()["access_token"]
    assert client.get("/api/v1/me", headers=auth(token)).status_code == 401
    assert client.get("/api/v1/me", headers=auth(new)).status_code == 200


def test_game_token_cannot_manage_account(client):
    token = register(client)["access_token"]
    game = client.post("/api/v1/game/token", headers=auth(token)).json()
    assert client.get("/api/v1/me", headers=auth(game["access_token"])).status_code == 401
    assert client.post("/api/v1/auth/password", headers=auth(game["access_token"]),
                       json={"old_password": "x", "new_password": "y" * 10}).status_code == 401


def test_logout_revokes_game_tokens_from_that_session(client):
    token = register(client)["access_token"]
    game = client.post("/api/v1/game/token", headers=auth(token)).json()
    assert client.post("/yggdrasil/authserver/validate", json={"accessToken": game["access_token"]}).status_code == 204
    client.post("/api/v1/auth/logout", headers=auth(token))
    assert client.post("/yggdrasil/authserver/validate", json={"accessToken": game["access_token"]}).status_code == 403


def test_password_change_signs_out_other_sessions(client):
    token = register(client)["access_token"]
    other = client.post("/api/v1/auth/login", json={"username": "Steve", "password": "correct horse battery"}).json()
    r = client.post("/api/v1/auth/password", headers=auth(token),
                    json={"old_password": "wrong one!", "new_password": "a brand new pass"})
    assert r.status_code == 401
    r = client.post("/api/v1/auth/password", headers=auth(token),
                    json={"old_password": "correct horse battery", "new_password": "a brand new pass"})
    assert r.status_code == 204
    assert client.get("/api/v1/me", headers=auth(token)).status_code == 200
    assert client.get("/api/v1/me", headers=auth(other["access_token"])).status_code == 401
    assert client.post("/api/v1/auth/login", json={"username": "Steve", "password": "a brand new pass"}).status_code == 200


def test_missing_or_malformed_auth_header(client):
    assert client.get("/api/v1/me").status_code == 401
    assert client.get("/api/v1/me", headers={"Authorization": "Basic abc"}).status_code == 401
    assert client.get("/api/v1/me", headers=auth("x" * 1000)).status_code == 401


def test_disabled_user_cannot_log_in(client, settings):
    token = register(client)["access_token"]
    conn = sqlite3.connect(settings.db_path)
    conn.execute("UPDATE users SET disabled = 1")
    conn.commit()
    assert client.get("/api/v1/me", headers=auth(token)).status_code == 401
    r = client.post("/api/v1/auth/login", json={"username": "Steve", "password": "correct horse battery"})
    assert r.status_code == 401


def test_sql_injection_attempts_are_inert(client):
    register(client)
    r = client.post("/api/v1/auth/login", json={"username": "Steve' OR '1'='1", "password": "' OR '1'='1"})
    assert r.status_code == 401
    token = client.post("/api/v1/auth/login", json={"username": "Steve", "password": "correct horse battery"}).json()["access_token"]
    r = client.get("/api/v1/users/search", params={"q": "%' OR 1=1 --"}, headers=auth(token))
    assert r.status_code == 400
