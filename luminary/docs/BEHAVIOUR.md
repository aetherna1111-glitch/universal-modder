# Behaviour reference: how Lumin lives, defends himself and loses his blade

This is the operator-facing description of slice 15. Rules live in `config/luminary.json` (section names below);
all of them are read at server start and on `/reload`.

## Client / server boundary

- Everything decides on the server: gaze detection, whispers, relationships, combat, knockout, custody, routine,
  plans. The client needs the mod only to render the NPC and the blade item; it receives ordinary chat lines, the
  synced `DOWN` flag (lying pose) and vanilla equipment/pose updates. No custom packets exist; nothing the client
  sends can change NPC state except through vanilla actions (hitting, right-clicking, chatting).
- The model never receives entity objects and never writes state. Its only outputs are a bounded line, an optional
  allowed clue, an optional low-trust note, and an intent name filtered by `CapabilityRegistry`.

## Presence (`presence`)

| Key | Default | Meaning |
|---|---|---|
| `language` | `en` | Line set of the authored channels (`en` or `ru`; English is the fallback) |
| `gazeCooldownSeconds` | 45 | One gaze line per player per this interval |
| `gazeGlobalCooldownSeconds` | 10 | At most one gaze line to anyone per this interval |
| `gazeMinimumMillis` | 600 | A glance shorter than this is ignored |
| `whisperEnabled` | true | |
| `whisperRange` | 5.0 | Blocks within which whispers are heard |
| `whisperCooldownSeconds` | 90 | Minimum spacing; deterministic jitter up to `whisperJitterSeconds` (45) is added |

Whispers are derived from the real `Activity` (task phase, routine slot, combat state); they are gray italic chat
lines sent only to players in range and never while a conversation is going on.

## Relationships (`relationship`)

| Key | Default |
|---|---|
| `threatHalfLifeMinutes` | 10 |
| `karmaRecoveryPerDay` | 2.0 |
| `hostileThreat` / `waryThreat` | 60 / 25 |
| `friendKarma` / `friendTrust` | 20 / 30 |

Event deltas (karma / trust / threat): conversation 0/+1/−2, requested work +1/+2/−1, gift +5/+6/−10,
attacked −8/−10/+30, attacked after a warning −15/−15/+45, duel hit 0/0/+25, apologized +4/+3/−40,
stole the weapon −30/−40/+20, returned it +15/+20/−20, helped while down +12/+15/−30, looted while down −6/−8/+5.
`/luminary npc relations <npc>` shows the decayed values and the standing.

## Self-defence and knockout (`combat`)

| Key | Default | Meaning |
|---|---|---|
| `attacksBeforeCombat` | 3 | Hits inside `attackWindowSeconds` (60) before the NPC strikes back; design range 3..5 |
| `shieldFirst` | true | First hit raises the shield (real blocking) and speaks a warning |
| `deescalateDistance` / `deescalateAfterSeconds` | 8.0 / 20 | Opponent beyond the distance for the time → stand down |
| `recoverySeconds` | 30 | After a fight or a knockout, before CALM |
| `weaponDamageFraction` | 0.5 | Of the opponent's maximum health per blow with the blade (2 damage unarmed) |
| `strikeReach` | 3.5 | Strikes only within this distance |
| `holdGroundRadius` | 6.0 | How far from the fight's start the NPC will step; beyond it, no pursuit |
| `knockoutMinutes` | 5 | Real time down (UTC deadline, persisted) |
| `helpShortensBySeconds` | 60 | Per feeding |
| `duelConsentSeconds` | 90 | Window after "let's duel" in which hits are sparring |

States: `CALM`, `DEFENSIVE_WARNING`, `GUARDED_DEESCALATION`, `COMBAT`, `RECOVERY`. Apology words ("sorry",
"извини", "прости"...) addressed to the NPC by the opponent end warning/guard at once and lower the blade in combat.
While down: right-click with food helps; empty hand loots one item; sneaking + empty hand takes the blade.
`/luminary npc behaviour <npc>` shows state, opponent, deadline, custody, routine and plan; `/luminary npc revive`.

## The Holy Sword of Lumin (`weapon`)

| Key | Default |
|---|---|
| `theftDurationMinutes` | 60 |

Item `luminary:holy_sword_of_lumin`: 9 damage, 1.6 swings/s, unbreakable. **Witness**: +1 mark per hit (max 7),
+0.5 damage per mark, a mark fades after 30 s of rest. **Verdict** (use): 12-block beam, 3 + 2 × marks damage,
undead ×1.5 and burning, glowing 10 s, spends the marks, 6 s cooldown. **Sanctuary** (sneak + use, 3 marks): circle of
light, radius 5, 8 s: players and Lumin regenerate and resist, hostile mobs weaken and glow; 30 s cooldown. Lumin
himself uses the beam on an opponent beyond reach (≤ 10 blocks) every 6 s for a quarter of their health instead of
pursuing. With Better Combat installed it swings as a claymore (`data/luminary/weapon_attributes/`).

Identity is the custody instance id in the item NBT (`LuminaryOwner`, `LuminaryInstance`). Exactly one id is valid;
copies with another id vanish when a player carries them (`inventoryTick`, once a second) and all reachable copies are
purged at the return. Only the thief may hold the live instance during the theft; the permanent owner after a
transfer. A blade with no owner tag is a forgery and vanishes. `/luminary npc weapon <npc>` (status), `reclaim` (op 4),
`transfer <player> <reason>` (op 4, the single permanent-transfer point; the reason is recorded, not interpreted).

## Routine (`routine`)

`enabled` (true). Places: `/luminary npc place <npc> study|desk|site|home|workshop <pos>` and `clear <kind>`;
`/luminary npc routine <npc> on|off` per NPC. Slots (Minecraft day time): 0–4000 READING at STUDY, 4000–7000 WRITING at
DESK, 7000–11500 INVESTIGATING at SITE, 11500–13000 READING at STUDY, 13000–24000 RESTING at HOME. A slot whose place
is unknown is skipped. The NPC walks; in an unloaded chunk nothing happens, and on load it resumes from the clock.

## Plans

`/luminary npc plan stock <npc> <tree> <chest> <logs>`, `/luminary npc plan smelt <npc> <chest> <furnace> <item> <count>`,
`status`, `cancel`. Steps are executed through `NpcService.assign` with all validators; a step is verified by its task's
ledger; two attempts per step; impossible preconditions skip the step; the requester is told when the plan ends.

## Limitations (honest list)

- A stale blade copy lying in a container is inert and invisible to the server until a player picks it up; it is then
  purged. Containers are not scanned.
- Knockout uses the raw damage amount against current health (before armour), so a very heavy hit on an armoured NPC
  can knock it down slightly earlier than vanilla arithmetic would kill it.
- Routine props are vanilla items held in hand plus pose changes; there is no custom animation model yet.
- Whisper and gaze lines are authored text, not model text, by design.
- The duel/apology detection is keyword-based (English and Russian).
