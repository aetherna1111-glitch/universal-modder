# Master task report: Luminary, the Ascending Intelligence NPC (slice 15)

Date: 2026-10-14. Repository: `universal-modder/luminary` (mirrored to `aetherna1111-glitch/AI-agent`).

## 1. Findings from the repository reconnaissance

- Target: Minecraft 1.20.1, Fabric Loader 0.16.14 (the owner's pack runs 0.19.5), Fabric API 0.92.12+1.20.1,
  Yarn 1.20.1+build.10, Loom 1.6.12, Java 17 bytecode on a JDK 21 runtime. Pure core `dev.luminary.core` (no
  Minecraft classes) and a Fabric layer `dev.luminary.fabric`; GameTests are a second source set / mod.
- Already present before this slice: NPC entity with roster reconciliation (one body per definition), scripted and
  model dialogue, memory with provenance, validated tasks (gather, move, deposit, withdraw, smelt), intents (keyword
  and model, grounded in scanned candidates), autonomy with budgets, gear by right-click, attend-a-watching-player
  goal, name-only chat addressing, Thaumcraft bridge. Baseline: `./gradlew build` green, 108 unit tests, 31 GameTests.
- Missing before this slice (now implemented, see below): versioned character definition, gaze reactions with
  cooldowns, whisper channel, multi-turn context, relationships, self-defence, knockout, the unique weapon and its
  custody, routine, persistent multi-step plans, an explicit capability allowlist, metrics.
- Found and fixed along the way: both persistent states stored their JSON as one NBT string, which Minecraft caps at
  65535 bytes; a grown memory would have failed to save silently. They now store UTF-8 byte arrays and still read the
  old key.

## 2. What was implemented

See `README.md` section "Living there" and `docs/BEHAVIOUR.md` for the operator view, `docs/decisions/ADR-0013` for
the reasoning. In short: `CharacterSheet`, `GazeTracker`, `MutterChannel`, `ConversationContext`,
`RelationshipLedger`/`Relationship`/`RelationshipRules`, `CombatMachine`/`CombatRules`/`CombatState`, `Knockout`,
`WeaponCustody`, `DailyRoutine`/`RoutineState`, `NpcPlan`/`PlanStep`/`PlanRunner`/`PlanTemplates`,
`Capability`/`CapabilityRegistry`, `Metrics` (all pure Java, injected time), plus `BehaviourService`, `CombatGoal`,
`RoutineGoal`, `LuminBladeItem`/`LuminaryItems`, entity changes (synced knockout pose, damage interception,
down-interactions), `LuminaryConfig.Behaviour` with sections `presence`, `combat`, `relationship`, `weapon`,
`routine`, commands `/luminary npc behaviour|relations|revive|weapon|place|routine|plan` and `/luminary metrics`,
NPC state schema v2 with a v1 migration, Lumin's character sheet with English and Russian lines.

## 3. Verification

All commands run from `luminary/` on JDK 21 (bytecode `release 17`), offline Gradle cache.

| Command | Result |
|---|---|
| `./gradlew build` | BUILD SUCCESSFUL; 144 unit tests, 0 failures (was 108) |
| `./gradlew runGametest` | "All 37 required tests passed" in 3 consecutive runs after the last change (31 before this slice + 6 new) |

What the GameTests prove on a headless 1.20.1 server with an injected clock (`NpcService.setClockOffset`):
a gaze earns one authored line and then none inside the cooldown, a whisper reaches a near listener and not a far
one; three hits raise the shield, then the blade, the first blow takes half the attacker's health, zero health
knocks the body down (same entity, invulnerable, five-minute UTC deadline), food shortens it, the clock ends it;
the blade stolen from the downed NPC is held by the thief only, a copy in anyone else's hands vanishes, at exactly
one hour the custody returns with a new instance and every copy (inventory, dropped, later-surfacing) is purged;
an apology ends a quarrel, a declared duel costs no karma, a hostile player is refused work, the conversation
context keeps turns; the routine walks to the study and reads there; a two-step plan runs through validated tasks
once and skips its redundant step. Unit tests cover every state machine edge (thresholds 3 and 5, window expiry,
retreat, apology, duel, knockout, custody transitions, plan retries, codec round trip, allowlist).

Issues found by the tests and fixed: vanilla invulnerability frames (test hits must grow), GameTest batches sharing
one server clock (clock-sensitive tests run in their own batches), 1.20.1 mock players not being in the world
(now spawned; this also turned a slice 13 assertion from accidental into real), and the 64 KB NBT string cap on the
persisted states (now byte arrays).

Not run: a client (no renderer check for the blade model and the lying pose); a real Ollama (the model path is
unchanged in this slice and was exercised by the owner before).

## 4. Local setup

Unchanged from `docs/SETUP.md`: install the jar on server and clients, optional Ollama with `llm.provider: ollama`,
set `presence.language` to `ru` for Russian authored lines, give Lumin his places with `/luminary npc place`.
No API key, no paid service. All thresholds are in `config/luminary.json`.

## 5. Limitations and risks

- A stale blade copy in a container is inert until picked up (then purged); containers are not scanned.
- Knockout triggers on the raw damage amount against current health (before armour reduction).
- Routine props are vanilla items and poses; no custom animation model.
- Apology and duel detection are keyword-based (English, Russian).
- Combat damage is a fraction of the target's maximum health, so it stays "half a player" under modded health, but
  armour and enchantments reduce it as vanilla does.
- Not verified here: a client rendering the blade and the lying pose (no client in CI); a live server with players.

## 6. Recommended next actions

1. Play-test on the owner's server: gaze lines, a whisper, one fight to knockout, one theft, routine places.
2. Hand places over by chat ("this is your desk" while looking at a block) through the existing grant path.
3. Let plans be requested by chat (keyword planner → `PlanTemplates`), still through `CapabilityRegistry`.
4. A GeckoLib or custom model for lying, reading and writing animations.
