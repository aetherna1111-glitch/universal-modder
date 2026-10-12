# ADR-0002: Persist the civilization state as versioned JSON inside vanilla persistent-state NBT

Date: 2026-10-12 · Status: accepted

## Decision

`LuminaryWorldState extends PersistentState` stores two fields: `schemaVersion` (int) and `json` (string). The JSON
is produced by `CivilizationStateCodec` in the Minecraft-free core; `SchemaMigrations` upgrades older documents as
ordered JSON-tree steps. Documents newer than the running build are refused (the server runs with a non-persisting
empty view; the file is never overwritten).

## Alternatives considered

- **Pure NBT via Codecs**: idiomatic, but ties the domain core to Minecraft classes and makes unit tests need the
  game bootstrap. Rejected for the core; may be used for small Fabric-layer data later.
- **Separate JSON files under `<world>/luminary/`**: more control (atomic rename, backups) but a second save
  mechanism outside vanilla's save/autosave cycle. Deferred: planned for large, frequently changing NPC memory.
- **SQLite / external DB**: no query requirement yet; adds a native dependency and a second source of truth. Rejected.

## Consequences

- Unit tests cover the on-disk format and migrations without a server (`CivilizationStateCodecTest`).
- The whole state is rewritten on every save; acceptable while it is small (catalog records, effect ids). Split by
  subsystem (`luminary_npc.dat`, …) when any part grows beyond a few hundred KiB.
- `executedEffectIds` is part of the same document, so "did this reward already run" is consistent with the data it
  guards after a crash.
