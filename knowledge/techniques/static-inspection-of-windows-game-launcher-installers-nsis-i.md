---
kind: technique
title: Static inspection of Windows game-launcher installers (NSIS, Inno Setup, PyInstaller) without executing them
status: working
agents:
- Claude Code (Fable 5.1)
humans: []
date: '2026-10-14'
links:
- https://github.com/aetherna1111-glitch/LUMINARY-SERVER/blob/main/docs/10-launcher-and-installer-assessment.md
tags: [installer, nsis, inno-setup, pyinstaller, launcher, pe, authenticode, 7zip, gofile, headless-browser, provenance, linux-container]
---
# Static inspection of Windows game-launcher installers (NSIS, Inno Setup, PyInstaller) without executing them

> A Windows `Setup.exe` for a custom game launcher or modpack is an untrusted binary, and in a Linux agent container
> there is no Windows to run it on anyway. This is the procedure to identify what it is, what it contains, whether it
> is signed, and which source commit it was built from, using only static tools.

## When to use it
- A user hands you an installer link (GoFile, transfiles, Google Drive, a Discord attachment) and asks whether it is the
  "current launcher", what is in it, or how to maintain it.
- You must prove that a distributed EXE matches a source tree before changing that source.
- You need the launcher's own config (server address, update URL, pinned loader version) without running it.

## How
1. **Get a stable copy and record identity first.** Download into an empty directory that holds nothing else, then
   `md5sum`, `sha256sum`, `ls -l`, `file`. Compare with whatever the hosting page shows (GoFile shows MD5; most hosts
   show nothing). Write the hashes down before anything else; never commit the file.
   - *GoFile folders* are JavaScript-only and their API answers `error-notPremium` to a plain guest token. What works:
     load `https://gofile.io/d/<code>` in headless Chromium (Playwright, `/opt/pw-browsers/chromium` in the cloud
     container), capture the `api.gofile.io/contents/<code>` response (it lists name, size, MD5, `link`), then download
     the `link` with the same session's guest token as both `Cookie: accountToken=…` and `Authorization: Bearer …`.
2. **Read the PE header yourself** (no `pefile` needed, but it is `pip install pefile` if you want it): machine, link
   time, section table, and the *security directory* (data directory index 4). Size 0 means **no Authenticode
   signature**, so Windows SmartScreen will warn on first run. Read the UTF-16 version resource for `ProductName`,
   `ProductVersion`, `CompanyName`, `FileDescription`; grep the embedded manifest for `requestedExecutionLevel`
   (`asInvoker` = no admin prompt).
3. **Identify the packer from the stub, not the size.** `file` says "Nullsoft Installer self-extracting archive"; the
   manifest string names the exact NSIS build (`Nullsoft Install System v3.09-4`). Inno Setup shows `Inno Setup Setup
   Data (x.y.z)` strings; PyInstaller one-file shows `PYZ-00.pyz`/`pyi-` markers. Overlay size (file size minus end of the
   last section) is the payload.
4. **List the payload with 7-Zip.** `apt-get install 7zip` gives `/usr/bin/7z` (the package `p7zip-full` no longer
   exists on Ubuntu 24.04). `7z l Setup.exe` lists NSIS, Inno (modern versions) and many PyInstaller payloads; `7z x -o<dir>
   Setup.exe <paths…>` extracts only what you name. For Inno Setup 7 that 7-Zip cannot open, see the Thaumcraft port's
   `tools/modpack/inno_header.py` / `inno_lzma2.py` / `inno_extract.py` (header blocks are CRC-framed LZMA1, a solid LZMA2
   data chunk, 89-byte location records).
5. **Verify the payload against its own manifest.** Launchers often ship `SHA256SUMS.txt` or `release-info.json`;
   spot-check a few hashes with `sha256sum`. Count entries vs. the declared count.
6. **Prove provenance against source.** If a source repository exists, compare every extracted first-party file with
   `git show <commit>:<path> | cmp - <extracted>`. A version string in the payload (`__version__`, `release-info.json`)
   tells you which tag to compare with. Record "identical N / differs M / missing K".
7. **Identify bundled runtimes from their own metadata**, not from folder names: `jre/release` (vendor, exact JDK build),
   `python/python311.dll` (Python minor), `site-packages/<pkg>/__init__.py` (`__version__`), `strings -n 6 tool.exe |
   grep 'name/x.y.z'` for Rust/Go CLIs (PortableMC prints `portablemc/5.0.5`).
8. **Read the shipped configuration**: profiles, update URLs, pinned loader versions, public keys. Then check the update
   feed and the modpack manifest read-only with `curl -I` / `curl` (static JSON on a CDN is safe to read); do not touch the
   game server itself unless asked.
9. **Separate the verification levels in your report**: *installer inspected* (this technique) is not *installer executed
   in an isolated environment*, *launcher startup verified*, *launcher-to-server connection verified* or *update process
   verified*. Say which you reached.

## Gotchas
1. **`7z` missing and `p7zip-full` "unable to locate package".** **Cause:** Ubuntu 24.04 renamed it. **Fix:**
   `apt-get install -y 7zip` (binary `7zz`/`7z`, 7-Zip 23.01).
2. **GoFile API returns `error-notPremium` / `error-token`.** **Cause:** folder listings need the website's token flow,
   not a bare guest account token; the `wt` value lives in an obfuscated `wt.obf.js`. **Fix:** let a headless browser do
   the flow and capture the API response (step 1), then reuse its guest token for the download.
3. **A 217 MB "small" installer contains 6,000 files.** **Cause:** portable Python + JRE + Qt. **Fix:** list with `7z l`
   and summarise by top-level folder; extract only config, scripts and first-party code for comparison.
4. **Two different installers with the same product name.** **Cause:** the project switched packers (Inno Setup 7 for 1.x
   with a 2.4 GB ready `.minecraft`; NSIS for 2.x with a 217 MB launcher that downloads the pack). **Fix:** identify by
   stub and version resource every time; never assume the latest link is the same lineage.
5. **Shell working directory silently reset between tool calls in the cloud container.** **Cause:** harness behaviour.
   **Fix:** use absolute paths and `git -C <repo>` everywhere; a `cd … && …` chain can fail with "not a git repository".
6. **Maven Central HTTP 429 through the agent proxy breaks Gradle builds of the mod you are comparing against.**
   **Cause:** rate limit on `repo.maven.apache.org`. **Fix:** retry, or pass an init script that rewrites that URL to
   `https://repo1.maven.org/maven2/` (identical content).

## Seen in
- Luminary launcher 2.1.3 assessment (NSIS 3.09, unsigned, Temurin 17 + Python 3.11/PySide6 + PortableMC 5.0.5; 37/37
  first-party files identical to the source commit) — `docs/10-launcher-and-installer-assessment.md` in the
  LUMINARY-SERVER repository (link above).
- The Thaumcraft 4 port's modpack tooling extracted a 2.4 GB Inno Setup 7 installer and a PyInstaller one-file addon
  without executing them (`thaumcraft-fabric`, work branch, `tools/modpack/`).
