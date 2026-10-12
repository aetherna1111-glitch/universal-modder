from conftest import auth, register


def test_friend_request_lifecycle(client):
    steve = register(client, "Steve")["access_token"]
    alex = register(client, "Alex", "another good pw")["access_token"]
    eve = register(client, "Eve", "yet another pw")["access_token"]

    assert client.get("/api/v1/users/search", params={"q": "al"}, headers=auth(steve)).json()["users"][0]["name"] == "Alex"
    assert client.get("/api/v1/users/search", params={"q": "st"}, headers=auth(steve)).json()["users"] == []  # not self

    assert client.post("/api/v1/friends/requests", json={"username": "Alex"}, headers=auth(steve)).json()["status"] == "pending"
    assert client.post("/api/v1/friends/requests", json={"username": "Alex"}, headers=auth(steve)).status_code == 409
    assert client.post("/api/v1/friends/requests", json={"username": "Steve"}, headers=auth(steve)).status_code == 400
    assert client.post("/api/v1/friends/requests", json={"username": "Ghost"}, headers=auth(steve)).status_code == 404

    out = client.get("/api/v1/friends", headers=auth(steve)).json()
    assert [o["name"] for o in out["outgoing"]] == ["Alex"]
    incoming = client.get("/api/v1/friends", headers=auth(alex)).json()["incoming"]
    rid = incoming[0]["id"]

    # Neither the requester nor a third party can accept on Alex's behalf.
    assert client.post(f"/api/v1/friends/requests/{rid}/accept", headers=auth(steve)).status_code == 404
    assert client.post(f"/api/v1/friends/requests/{rid}/accept", headers=auth(eve)).status_code == 404

    assert client.post(f"/api/v1/friends/requests/{rid}/accept", headers=auth(alex)).status_code == 204
    friends = client.get("/api/v1/friends", headers=auth(steve)).json()["friends"]
    assert friends[0]["name"] == "Alex" and friends[0]["presence"] == "offline"

    client.post("/api/v1/presence", json={"state": "online"}, headers=auth(alex))
    assert client.get("/api/v1/friends", headers=auth(steve)).json()["friends"][0]["presence"] == "online"

    # Presence is visible to friends only.
    assert client.get("/api/v1/users/Alex", headers=auth(steve)).json()["presence"] == "online"
    assert "presence" not in client.get("/api/v1/users/Alex", headers=auth(eve)).json()

    assert client.delete("/api/v1/friends/Alex", headers=auth(steve)).status_code == 204
    assert client.get("/api/v1/friends", headers=auth(steve)).json()["friends"] == []


def test_reverse_request_auto_accepts_and_decline(client):
    steve = register(client, "Steve")["access_token"]
    alex = register(client, "Alex", "another good pw")["access_token"]
    client.post("/api/v1/friends/requests", json={"username": "Alex"}, headers=auth(steve))
    assert client.post("/api/v1/friends/requests", json={"username": "Steve"}, headers=auth(alex)).json()["status"] == "accepted"
    client.delete("/api/v1/friends/Steve", headers=auth(alex))

    client.post("/api/v1/friends/requests", json={"username": "Alex"}, headers=auth(steve))
    rid = client.get("/api/v1/friends", headers=auth(alex)).json()["incoming"][0]["id"]
    assert client.post(f"/api/v1/friends/requests/{rid}/decline", headers=auth(alex)).status_code == 204
    assert client.get("/api/v1/friends", headers=auth(steve)).json()["outgoing"] == []


def test_cancel_outgoing(client):
    steve = register(client, "Steve")["access_token"]
    alex = register(client, "Alex", "another good pw")["access_token"]
    client.post("/api/v1/friends/requests", json={"username": "Alex"}, headers=auth(steve))
    rid = client.get("/api/v1/friends", headers=auth(steve)).json()["outgoing"][0]["id"]
    assert client.delete(f"/api/v1/friends/requests/{rid}", headers=auth(alex)).status_code == 404
    assert client.delete(f"/api/v1/friends/requests/{rid}", headers=auth(steve)).status_code == 204


def test_news_and_info(client, settings):
    import sqlite3
    conn = sqlite3.connect(settings.db_path)
    conn.execute("INSERT INTO news(title, body, published_at) VALUES ('Hi', 'Body', 1)")
    conn.commit()
    assert client.get("/api/v1/news").json()["news"][0]["title"] == "Hi"
    info = client.get("/api/v1/info").json()
    assert info["yggdrasil_url"] == "http://localhost:8080/yggdrasil"
    h = client.get("/api/v1/health")
    assert h.headers["cache-control"] == "no-store" and h.headers["x-content-type-options"] == "nosniff"
