# Setup: server, model, first conversation

## 1. Install the mod

1. Build: in this folder run `./gradlew build` (JDK 17 or newer). The jar is `build/libs/luminary-0.1.0.jar`.
2. Put the jar into the server's `mods/` next to **Fabric API 0.92.12+1.20.1** (Fabric Loader 0.16+, Minecraft 1.20.1).
3. Start the server once. It writes `config/luminary.json` with defaults: scripted dialogue, keyword intent planner,
   no model, autonomy off.

Clients that join need the same jar (the NPC entity has a client renderer); a client without it cannot see the NPC.

## 2. A local model with Ollama (recommended)

Ollama runs the model on the machine that hosts the Minecraft server (or one reachable from it).

1. Install Ollama from https://ollama.com (Windows, macOS, Linux).
2. Pull a model that answers in the players' language and follows JSON instructions. Starting points by GPU memory:
   - 8 GB: `ollama pull qwen2.5:7b`
   - 12 GB or more: `ollama pull qwen2.5:14b`
   - CPU only or 4-6 GB: `ollama pull qwen2.5:3b` (slower, simpler answers)
3. Check it: `ollama run qwen2.5:7b "Reply with the single word pong"` and stop it with `/bye`.
4. Edit `config/luminary.json`:

```json
{ "dialogueProvider": "model",
  "intentPlanner": "model",
  "llm": { "provider": "ollama", "endpoint": "http://127.0.0.1:11434", "model": "qwen2.5:7b",
           "requestTimeoutSeconds": 20, "dialogueTimeoutSeconds": 8, "maxConcurrent": 2, "allowedHosts": [], "logContent": false } }
```
   If Ollama runs on another machine, set its address in `endpoint` and add its host name to `allowedHosts`.
   Ollama itself must then listen on the network (`OLLAMA_HOST=0.0.0.0` on that machine).
5. Restart the server, then as an operator: `/luminary llm status` (health) and `/luminary llm ping` (one real request
   with latency). Without a model everything still works through the scripted lines and keyword rules.

Content of prompts and replies is never written to disk unless `logContent` is true; the journal
`logs/luminary/llm.jsonl` records sizes, latency and outcome per request.

The model must be reachable **from the machine that runs the Minecraft server**. `http://127.0.0.1:11434` means
"this same machine". If the server is on a hosting provider and Ollama runs at home, either run Ollama on a machine
the hosting can reach (set `endpoint` to its address, add its host to `allowedHosts`, start Ollama with
`OLLAMA_HOST=0.0.0.0`), publish the home machine through a tunnel (for example a Cloudflare Tunnel; its host name goes
into `allowedHosts`), or use a hosted API (section 3). `/luminary llm status` showing `failed` and `busy` counts with
`ok=0` is this problem, not a Luminary one; until it is fixed the NPC answers with its scripted lines.

## 3. A hosted model (OpenAI-compatible API)

Set `"provider": "openai"`, `"endpoint": "https://<host>/v1"`, `"model": "<name>"`, add the host to `allowedHosts`,
and export the key in the environment that starts the server:

```
export LUMINARY_LLM_API_KEY=...   # Linux/macOS
set LUMINARY_LLM_API_KEY=...      # Windows cmd, in the same start script
```
A key written into `luminary.json` is ignored with a warning.

## 4. First conversation and first task

```
/luminary npc spawn luminary:npc/lumin            spawn Lumin near you
right-click Lumin                                 scripted or model line
<chat> "Lumin, who are you?"                      dialogue through the model; the line must contain his name (any distance)
right-click Lumin holding an axe or a helmet      he takes the tool / wears the armour (one piece per slot)
/luminary npc gear luminary:npc/lumin             what he holds and wears
look at a chest, <chat> "Lumin, use this chest"      hands it over (or /luminary npc container allow luminary:npc/lumin <x y z>)
<chat> "Lumin, chop 3 logs for me"                keyword or model intent → validated task → logs in the chest
/luminary npc task status luminary:npc/lumin     state, ledger
/luminary npc memory luminary:npc/lumin          what Lumin remembers
```

Autonomy (off by default twice):
```
/luminary npc autonomy server on
/luminary npc autonomy luminary:npc/lumin on
/luminary npc order stock_wood luminary:npc/lumin <x y z of the chest> 8
```

## 5. Thaumcraft port

With the port installed (branch with the observer API), research completions, scans and aspect discoveries reach
Luminary automatically; see `docs/integration/README.md`. Check with `/luminary progression status` (signals are listed there) after a scan.

## 7. Presence, self-defence, the blade, the routine

Slice 15 adds the sections `presence`, `combat`, `relationship`, `weapon` and `routine` to `config/luminary.json`
(defaults are written on first start). For the Russian line set put `"presence": { "language": "ru" }`. Give Lumin
his places once (`/luminary npc place luminary:npc/lumin study <x y z>`, then `desk`, `site`, `home`) and he lives
his day between them. Everything else is on by default and needs no model. The full table of keys is in
`docs/BEHAVIOUR.md`.


## Client locked to the server (`config/luminary-client.json`, client side only)
```json
{ "lockToServer": true, "serverAddress": "play.example.com:25565", "serverName": "Luminary" }
```
With the lock on: the title screen's Singleplayer button becomes «Играть» and connects, Multiplayer and Realms are
removed, and any way onto the world list or the server list (a FancyMenu button included) connects instead; in the
pause menu «Отключиться» becomes «Выйти из игры», the death screen's «Главное меню» quits too, and a lost connection
offers «Переподключиться» and «Выйти из игры». The file is written with the lock off on the first client start; an
invalid address keeps it off (a warning in the log). Nothing changes on the server side.
