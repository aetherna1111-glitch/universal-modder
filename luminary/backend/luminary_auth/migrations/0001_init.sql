-- Luminary accounts schema v1
CREATE TABLE users (
    id                  INTEGER PRIMARY KEY,
    username            TEXT NOT NULL,
    username_lower      TEXT NOT NULL UNIQUE,
    password_hash       TEXT NOT NULL,
    created_at          INTEGER NOT NULL,
    password_changed_at INTEGER NOT NULL,
    disabled            INTEGER NOT NULL DEFAULT 0,
    is_admin            INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE textures (
    hash       TEXT PRIMARY KEY,
    width      INTEGER NOT NULL,
    height     INTEGER NOT NULL,
    size       INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

-- One Minecraft profile per account. uuid is the offline-mode UUID of the name
-- (Java UUID.nameUUIDFromBytes("OfflinePlayer:" + name)) so existing worlds keep player data.
CREATE TABLE profiles (
    uuid            TEXT PRIMARY KEY,
    user_id         INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    name_lower      TEXT NOT NULL UNIQUE,
    skin_hash       TEXT REFERENCES textures(hash),
    skin_model      TEXT NOT NULL DEFAULT 'classic' CHECK (skin_model IN ('classic', 'slim')),
    skin_updated_at INTEGER
);

-- Only SHA-256 hashes of tokens are stored.
CREATE TABLE tokens (
    token_hash   TEXT PRIMARY KEY,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind         TEXT NOT NULL CHECK (kind IN ('session', 'game')),
    client_token TEXT,
    parent_hash  TEXT,
    created_at   INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    last_used_at INTEGER
);
CREATE INDEX tokens_user ON tokens(user_id);
CREATE INDEX tokens_parent ON tokens(parent_hash);

CREATE TABLE joins (
    server_id    TEXT NOT NULL,
    profile_uuid TEXT NOT NULL,
    ip           TEXT,
    created_at   INTEGER NOT NULL,
    PRIMARY KEY (server_id, profile_uuid)
);

CREATE TABLE reserved_names (
    name_lower TEXT PRIMARY KEY,
    note       TEXT,
    created_at INTEGER NOT NULL
);

CREATE TABLE friendships (
    id           INTEGER PRIMARY KEY,
    requester_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    addressee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status       TEXT NOT NULL CHECK (status IN ('pending', 'accepted')),
    created_at   INTEGER NOT NULL,
    responded_at INTEGER,
    CHECK (requester_id <> addressee_id)
);
CREATE UNIQUE INDEX friendships_pair ON friendships(min(requester_id, addressee_id), max(requester_id, addressee_id));

CREATE TABLE presence (
    user_id    INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    state      TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE news (
    id           INTEGER PRIMARY KEY,
    title        TEXT NOT NULL,
    body         TEXT NOT NULL,
    published_at INTEGER NOT NULL
);

CREATE TABLE rate_events (
    bucket TEXT NOT NULL,
    at     REAL NOT NULL
);
CREATE INDEX rate_events_bucket ON rate_events(bucket, at);
