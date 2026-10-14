# Luminary — handoff for a new chat

Read this first when you continue the Luminary work in a fresh session. It is the condensed state of the project as
of 2026-10-14 (commit `bb080e2` on `universal-modder` branch `claude/dreamy-knuth-rizfww`). The owner writes in
Russian; code, docs and commit messages are in English.

## What Luminary is

A Fabric 1.20.1 mod (`luminary`, version 0.1.0) with a persistent AI NPC **Lumin** («Люмин», id `luminary:npc/lumin`)
living in the owner's modpack (Prominence II: Hasturian Era 4.1.1, distributed by a custom launcher that mirrors a
`client-pack` folder on the hosting into every player's `.minecraft`). The launcher's built-in server is
`135.106.221.145:27195`.

- Code: `universal-modder/luminary` (this directory). Mirror: GitHub `aetherna1111-glitch/ai-agent` branch `main`
  via `git subtree split --prefix=luminary -b luminary-mirror && git push aiagent luminary-mirror:main`.
- Architecture: pure-Java core `dev.luminary.core.*` (no Minecraft classes, unit-tested) + Fabric layer
  `dev.luminary.fabric.*`. Server authoritative. NPC state is JSON in `<world>/data/luminary_npc.dat` (schema v2).
- Language model: Ollama (`qwen2.5:3b`) through `ModelDialogueProvider`; the API key, if any, comes only from the
  environment variable `LUMINARY_LLM_API_KEY`. The model never gets write access to story, progression or the world.
- Config: `config/luminary.json` (server: dialogue provider, combat rules …) and `config/luminary-client.json`
  (client: `lockToServer`, `serverAddress`, `serverName`, `customTitle`).

## Standing rules from the owner

- Never put keys or secrets in chat, docs or the repo.
- No paid services.
- Every commit ends with the two attribution lines used in this branch's history (see `git log`).
- Never claim a build or test passed unless it was actually run. No destructive git.
- Do not change gameplay in `thaumcraft-fabric` (TC4 fidelity rules there). No PR to the port's master.
- Bosses for quests come from the pack's FTB Quests files, not from memory.
- After every change deliver the rebuilt `luminary-0.1.0.jar` to the owner ("replace only the luminary jar").
- Work autonomously; do not ask for confirmation on routine decisions.

## Build and test

```bash
cd luminary
./gradlew --offline build          # 162 unit tests + jar at build/libs/luminary-0.1.0.jar
./gradlew --offline runGametest    # 49 GameTests, report in build/gametest/junit.xml
```

GameTest classes must be listed under the `fabric-gametest` entrypoint in `src/gametest/resources/fabric.mod.json`.
Headless client screenshots: `./gradlew downloadAssets`, then
`LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew --offline runClient --args="--width 960 --height 540"`
with `-Dluminary.screenshot=<file>` (trade screen) or `-Dluminary.screenshot.title=<file>` (title screen); the dev hook is
`fabric/client/TradeScreenShot`. Toolchain: Loader 0.16.14 (owner runs 0.19.5), Fabric API 0.92.12, Yarn
1.20.1+build.10, Loom 1.6.12, JDK 21 building for release 17, Gradle offline.

Last recorded verification: `build` passed (162 unit tests) and `runGametest` printed "All 49 required tests passed"
at commit `bb080e2`. Later commits are assets/docs only.

## Implemented systems (where to look)

| System | Core | Fabric | Docs / tests |
|---|---|---|---|
| Quests, points, shop | `core/npc/trade/*` (`Quest`, `TradeService`, `PlayerLedger`, `TradeRules`) | `fabric/npc/TradeManager` (actions 0 accept, 1 claim, 2 buy, 3 gear, 4 talk, 5 refresh, 6 donate; packets `luminary:trade_open` / `luminary:trade_action`), `fabric/client/TradeScreen` | `docs/TRADE.md`, `TradeServiceTest`, `TradeGameTests` |
| Quest data | — | `data/luminary/luminary/quests/*.json` (13 FTB-Quests bosses + hidden `trial_of_the_envoy.json`, unlocked after 5 quests, refused when unworthy) | `docs/TRADE.md` |
| Karma effects | `core/npc/relationship/KarmaEffects` (luck 1/25 per karma up to 4; traders' markup from −10 up to 75 %, discount from +10 up to 30 %; alms 10 emeralds → +5 karma) | `BehaviourService.applyKarmaLuck`, `fabric/mixin/VillagerPricesMixin` (`luminary.mixins.json`, logs `[luminary] villager prices for …`) | `KarmaEffectsTest`, `TradeGameTests` |
| Holy blade and harp | `core/npc/weapon/*` (custody, one-hour theft, return) | `HolySwordItem` (`transferTo` keeps the NPC binding, changes only the owner), `AngelicHarpItem` (sword's equal: 15 dmg, notes, chord, lullaby, Choir aura), `BehaviourService.transferWeapon` + shimmering chat proclamation, `recallDroppedBlade`, `offerBladeBack` (two clicks to return) | `docs/BEHAVIOUR.md`, `WeaponCustodyTest`, `BehaviourGameTests` |
| Self-defence | `core/npc/combat/CombatRules` (defaults `attacksBeforeCombat 5`, `holdGroundRadius 10`) | `LuminaryNpcEntity.damage` (blocked shield blows count), `BehaviourService.onAttacked` (blow 1 warning, blow 3 warning shot, blow 5 fight), `CombatGoal` (pursuit 1.35, presses the attacker 8 s) | `BehaviourGameTests` |
| Chat understanding | `core/npc/conversation/Topic`, `TopicMatcher` (ru/en stems, placeholders), `core/npc/intent/WorkCues` | `NpcService.request` (topics answered from the sheet, model only for uncovered text), `BehaviourService.situation` / `placeholders` | `TopicMatcherTest`, `TradeGameTests` (chat topics) |
| Downed interaction | — | `BehaviourService.interactDown` (no sneaking: empty hand takes the blade, then the bag; food helps); Carry On blacklist `data/carryon/tags/entity_types/entity_blacklist.json` | `docs/BEHAVIOUR.md` |
| Client lock + menu | — | `fabric/client/ServerLock` (Play connects to the server, Quit only), `LuminaryTitleScreen` | `docs/SETUP.md` |
| Commands | — | `fabric/command/LuminaryCommands`: `/luminary npc trade <npc> status|complete [player] [all]|reset [player]|points <player> <n>`, `/luminary npc relations <npc> reset [player]|karma <player> <value>` (op 3) | `docs/TRADE.md` |
| Mask and vestments | — | `textures/models/armor/vestments_atlas.png` 512×512 (3D relief mask, 68 cuboids), generator script lived in the session scratchpad (`gen_vestments.py`, not in the repo) | `VestmentGameTests` |

NPC sheet and shop: `data/luminary/luminary/npcs/lumin.json` (line groups `trade_greeting`, `trade_claimable`,
`trade_special`, `quest_accepted`, `quest_claimed`, `trade_bought`, `sword_given`, `donation`, `warning`,
`warning_shot`, `topic.*`, `word.*`). Lang: `assets/luminary/lang/{ru_ru,en_us}.json`.

## Modpack facts learned the hard way

- ImmediatelyFast 1.5.5 needs `font_atlas_resizing` and `experimental_screen_batching` keys in
  `config/immediatelyfast.json`; the pack's jar was updated to 1.5.5.
- AAAParticles is a dependency of other pack mods: removing it breaks resource reload and translations (raw lang keys).
- Pixel artifacts in menus were most likely the NVIDIA driver (GTX 1070 Ti, 572.70); the owner was updating it.
- Carry On 2.1.2.7 picks up entities on sneak + empty hand, hence the blacklist and the no-sneak interactions.
- Artifacts 9.5.17 villager hat hooks `getPlayerReputation` inside `updateSpecialPrices`; our mixin injects at the
  tail of `prepareOffersFor` and takes the discount off after vanilla's.
- Immersive Portals 5.2.0 needs exactly sodium 0.5.13 and iris 1.7.6 (the pack has them), pehkui ≥ 3.4.1; there is no
  Fabric bridge for Valkyrien Skies 2.
- FancyMenu 3 layouts in `config/fancymenu/customization/` (`lscreen.txt`, `LoadingScreen.txt`,
  `Prominence Inventory.txt`) all use `config/fancymenu/assets/hasturian-era-logo.png`; the main menu uses
  `assets/bcg/main_menu_logo.png`. Replacing those two PNGs in `client-pack` rebrands everything.
- The launcher deletes anything in `.minecraft` that is not in `client-pack` (see the pack's INSTALL.md).

## Client-pack update delivered last

Archive `luminary-client-pack-update.zip` (sent to the owner in the previous chat): `mods/luminary-0.1.0.jar`,
`mods/ImmediatelyFast-Fabric-1.5.5+1.20.4.jar`, `config/luminary.json`, `config/luminary-client.json`
(server `135.106.221.145:27195`), FancyMenu `lscreen.txt` + `LoadingScreen.txt`, `assets/hasturian-era-logo.png`,
`assets/bcg/main_menu_logo.png`, `assets/luminary/{loading_bg.png,logo.png,tips.txt}`, README. Everything goes under
`client-pack/` with the same relative paths.

## Open items waiting on the owner

1. Do traders' discounts work now? Check `latest.log` for the `[luminary] villager prices` line.
2. Did the driver update remove the pixel artifacts?
3. Immersive Portals together with Valkyrien Skies: send a crash report if it crashes.
4. Examples of off-topic model answers from Lumin, to tune `TopicMatcher` or the prompt.
5. Result of `/luminary npc trade lumin reset`.

Possible follow-ups: a VS2 ↔ Immersive Portals compat bridge (only with a crash report), more topics for the matcher,
GeckoLib model for Lumin.

## Reports and decisions

`docs/reports/2026-10-14-master-task-slice-15.md`, `docs/reports/2026-10-14-trade-slice-16.md`,
`docs/decisions/ADR-0001 … ADR-0013`, `docs/integration/` (Thaumcraft port bridge).
