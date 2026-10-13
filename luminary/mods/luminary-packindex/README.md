# luminary-packindex

A server-only Fabric mod for the Luminary pack. On every server start (before the world loads) it rebuilds
`manifest.json` and `manifest-extra.json` in the servarium `client-pack` folder from the files that are actually
there. Updating the pack becomes:

1. Stop the server.
2. Upload, replace or delete files in `client-pack/mods`, `config` and the other folders with the hosting file
   manager.
3. Start the server.

Players' launchers pick the change up on their next Play. Nobody edits a manifest by hand.

## What it publishes

The rules match the launcher (`luminary_app/modpack.py`) and `make_manifest.py`:

- **Folders** come from `config/luminary-packindex.json`, which is created with the defaults on first start.
  - `folders` (written to `manifest.json`): `mods` (mirror), `config`, `defaultconfigs`, `fancymenu_data`,
    `patchouli_books` (update). Every launcher version accepts these.
  - `extra_folders` (written to `manifest-extra.json`, read by launchers 2.1.3+): `resourcepacks`, `shaderpacks`.
  - `launcher/` (launcher self-update) is never indexed.
- **Files skipped and logged:** hidden files and folders, `*.part`, `*.tmp`, `*.crdownload`, `Thumbs.db`. Servarium
  does not serve hidden files, and the others are uploads in progress.
- **Names launchers would reject** stop the update. That covers `CON.jar`, trailing spaces or dots, `:` or `\`, two
  names differing only in case, links, and files over 2 GB. The old manifests stay, and the server console prints
  `[Luminary] Список файлов сборки НЕ обновлён` with each problem.
- **Empty files** listed in the previous manifest are kept. Servarium refuses to store empty files, and launchers
  create them locally.
- **When nothing changed**, nothing is rewritten. The version stays, so players are not offered an empty update.
- **Writes are atomic.** Each manifest goes to a hidden temp file and is then moved into place. `manifest-extra.json`
  is written before `manifest.json`.
- **Hashes are cached** in `config/luminary-packindex-cache.json`. The first start hashes the whole pack (about 5 s
  for 1.8 GB); later starts take under a second.
- **Non-ASCII names work** (Cyrillic, `§`) even when the server's JVM runs without a UTF-8 locale. File names are
  read from the raw bytes (`Path.toUri()`) and never built from text.

## Safety rules (from an independent review)

The update stops, the old manifests stay, and the console prints the reason in these cases:

- **A folder is gone or emptied.** This covers a folder whose published list had files in it but which is now missing
  or has no files left, for example after a failed upload or unpack. Publishing it would move every player's copy of
  that folder to `.backup`. To drop a folder on purpose, remove it from `config/luminary-packindex.json`.
- **A file changed while it was being hashed** (an upload still running), so its hash would match no complete file.
- **Names Windows cannot create**, which every launcher accepts but Windows players could never sync:
  `? * | < > "`, control characters, `CON .jar`, `COM¹`, `CONIN$`.
- **Characters the server's Java does not know** (newer Unicode), whose letter case could compare differently from
  the launcher's.
- **An unreadable previous manifest.** It is the only record of the empty files.

While an update is refused, the console also lists published files that no longer match the folder. Players cannot
fetch those until the list is rebuilt.

Other behaviour:

- **Hash cache:** keyed on size, mtime and ctime in nanoseconds, so a same-size edit that restores the old mtime is
  still re-hashed.
- **Case comparison:** final sigma is folded, so Java never sees two names where the launcher sees one.
- **Empty files from the old list:** an entry is dropped when its parent path is now a file.
- **Unchanged check:** the old list counts as unchanged only if it is strict JSON, by the launcher's rules, and
  lists exactly the same entries. Otherwise it is rewritten.

**Do not edit or delete `manifest.json` by hand.** It is the only record of the 126 empty files, which servarium
cannot store.

## Build and test

```sh
python3 build.py --libs <fabric server libraries dir> --out build --test
```

It needs only Fabric Loader, Gson and SLF4J, which are all on a Fabric 1.20.1 server's classpath. `--test` runs
`test/` (65 checks). `PackIndexerTest cli <client-pack> <cache.json>` indexes a folder from the command line.

## Verified

- **Cross-check against the live pack.** On a copy of the servarium `client-pack` it produced exactly the published
  `manifest.json` and `manifest-extra.json`: 2,796 files, 126 of them empty files carried over. It rewrote nothing.
- **Launcher round trip.** After `luminary-vsfix-1.0.0.jar` was added to `mods/`, it published a new version. The
  2.1.3 launcher code, run for a player who already had the pack, downloaded exactly that one file.
- **Real Fabric 1.20.1 server.** The first start logged `Сборка обновлена … файлов 2797` (4.9 s). The second logged
  `Сборка без изменений` (0.4 s).
