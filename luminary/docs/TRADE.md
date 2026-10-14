# Lumin's economy: tasks, points, the shop and the Trial of the Envoy

Lumin pays in his own points, never coins. A player earns them by doing what he asks (killing the bosses of the
pack's main story) and spends them in his shop. Five tasks done with nothing bought open one special task, the
Trial of the Envoy, whose reward is his blade, with the player's name on it for good.

## Where the rules live
- `dev.luminary.core.npc.trade` (pure Java, unit-tested): `Quest`, `ShopOffer`, `PlayerLedger` (points, purchases,
  completed, active with kill counts, `specialClaimed`), `TradeService` (accept, kill, death, claim, buy),
  `TradeRules.SPECIAL_AFTER_QUESTS = 5`.
- `dev.luminary.fabric.npc.TradeManager`: the ledgers (one per player, inside the NPC record, persisted in
  `luminary_npc.dat` schema v2 under `trade`), the kill and death hooks (`ServerLivingEntityEvents.AFTER_DEATH`),
  the items handed out, the screen packets. Every client action is re-checked on the server against the ledger and
  the player's distance to the body (8 blocks).
- `dev.luminary.fabric.client.TradeScreen`: the dialogue screen. Portrait and name on the left with the points,
  his line underneath; tabs **Поручения** / **Торговля** / **Снаряжение** on the right. Dark navy and gold, like
  the Luminary launcher. The screen never decides anything: each button sends one action and the server answers
  with a fresh snapshot.

## In the game
- **Right-click Lumin** opens the screen. **Sneak + right-click** talks (chat). The **Снаряжение** tab opens the
  read-only gear screen (the sword is shown while it is his).
- **Tasks** (`data/luminary/luminary/quests/*.json`): `id`, `npc`, `order`, `kills` (entity type id → count),
  `points`, optional `special`, `resetOnDeath`, `rewardItems`, `title`/`description` lang keys (default
  `quest.<ns>.<path>.title/.desc`). A kill counts only after the task is accepted and only for the killer. A kill
  that finishes a task is announced in chat; a kill that advances one shows the count on the action bar.
- **Lumin's tasks**, in story order, from the pack's own FTB Quests main story (the `kill` tasks of `main_story.snbt`):

  | # | Task | Entity | Points |
  |---|---|---|---|
  | 1 | Ночной лич | `bosses_of_mass_destruction:lich` | 10 |
  | 2 | Иссохший Король | `soulsweapons:accursed_lord_boss` | 12 |
  | 3 | Длань Нижнего мира | `bosses_of_mass_destruction:gauntlet` | 12 |
  | 4 | Падшая Дракониха | `minecraft:ender_dragon` | 15 |
  | 5 | Чернитный голем | `adventurez:blackstone_golem` | 15 |
  | 6 | Конъюнктивус, Страж Темницы | `minecells:conjunctivius` | 15 |
  | 7 | Останки Древнего Чемпиона | `soulsweapons:draugr_boss` | 18 |
  | 8 | Столп Пустоты, Обсидилит | `bosses_of_mass_destruction:obsidilith` | 20 |
  | 9 | Потусторонний Глаз | `eldritch_end:eye` | 20 |
  | 10 | Три эссенции | `soulsweapons:chaos_monarch`, `soulsweapons:moonknight`, `aquamirae:captain_cornelia` | 30 |
  | 11 | Нуль, Сущность Пустоты | `archon:null` | 25 |
  | 12 | Магус, Архимаг | `spellbladenext:magus` | 25 |
  | 13 | Безликий | `eldritch_end:the_faceless` | 30 |
  | ✦ | Испытание Посланника (special) | Obsidilith, the Eye, Null, Magus, the Faceless: all five, **never dying** (death wipes the count) | the blade |

- **The shop** (`shop` list of `lumin.json`: `item`, `count`, `price`, `unique`): an offer naming an item that is
  not installed is skipped, so the Paladins & Priests lines simply do not show on a server without that mod.

  | Offer | Price |
  |---|---|
  | Светокаменная пыль ×8 (`luminary:lightstone_dust`) | 10 |
  | `paladins:holy_wand` / `holy_staff` | 20 / 30 |
  | `paladins:priest_spell_book` / `paladin_spell_book` | 25 / 25 |
  | `paladins:diamond_holy_wand` / `diamond_holy_staff` | 45 / 60 |
  | `paladins:netherite_holy_wand` / `netherite_holy_staff` | 90 / 120 |
  | Печать Посланника (`luminary:envoy_seal`, unique: one per player, no trader sells it) | 80 |

- **The Trial of the Envoy** is LOCKED until the player has completed 5 tasks **and bought nothing**, and while locked it is
  not in the screen at all (the server leaves it out of the snapshot, so nothing about it reaches the client). One purchase,
  ever, closes it for good (the ledger's `purchases` never goes back to zero). Claiming it calls
  `BehaviourService.transferWeapon`: custody becomes TRANSFERRED and the player receives the live blade. The blade
  stays **bound exactly as it was**, only the owner changes: `LuminaryOwner` keeps the NPC id (where the custody
  record lives), `LuminaryOwnerPlayer` is the player's UUID, `LuminaryOwnerLiteral` the name; the custody instance,
  marks, runes, blessings and every attribute are untouched. Tooltip: «Принадлежит: <player>» and «Связан с
  <player>…». It can be dropped (death, Q) and picked up again by its owner; in anyone else's hands it turns to light
  and is owed back: the owner gets it the moment they are on the server (`tickServer`, `deliverToOwner`). On the
  ground it never despawns, cannot burn or blow up, and after an hour there it is lifted back to the owner the same
  way. `/luminary npc weapon reclaim lumin` still takes it back (admin).
- **The Angelic Harp** (`luminary:angelic_harp`) is the sword's equal, in the sword's dress: ✦ Богоподобие ✦, 15
  damage at 1.7 swings a second, the Rune of Clarity and the Netherfused Rune of Embers, the same blessings
  (`weapon.blessings`), the shimmering name, the same tooltip layout. Its own voice: **Resonance** (a note per hit,
  up to 7, +0.5 damage each, fading at rest), **Hymn** (+0.10 chance per hit: two notes and a breath of healing),
  **+25% holy damage to the marked** (ignores armour), **Choir of the Catalog** (held: every 2 s allies within 4
  blocks are healed and hostiles marked), right-click **Chord of Light** (spends every note: 3 + 2 per note holy
  damage in 6 blocks, the undead twice, slowed and glowing, allies healed; 6 s cooldown), Shift + right-click
  **Lullaby** (3 notes: hostiles within 5 blocks drop their target, slowed and weakened 8 s; 30 s cooldown). Once the
  blade is gone Lumin fights with his harp (named, unbreakable, drawn only while fighting, never loot) and within 6
  blocks of his opponent plays the Chord instead of the blade's beam. A free harp sits in the creative Combat tab.
- **Lightstone dust** (`luminary:lightstone_dust`): a glinting material only he sells.
- **Seal of the Envoy** (`luminary:envoy_seal`): right-click = Blessing (players in 6 blocks heal 3 hearts, get
  Absorption II and Regeneration; monsters are weakened and glow; 90 s cooldown).
- **Lines** (character sheet groups, `lumin.json`): `trade_greeting`, `trade_claimable`, `trade_special`,
  `quest_accepted`, `quest_claimed`, `trade_bought`, `sword_given`.

## Karma: luck, prices, worthiness
Karma is the NPCs' relationship ledger (-100..100 per NPC, summed over every NPC and clamped, decaying towards zero
with time): tasks (+3 each), help and gifts raise it; blows, looting and theft lower it. `KarmaEffects` (pure) turns it
into:
- **Luck**: the player's `generic.luck` attribute gets a modifier of karma/25 in half points, capped at ±4,
  refreshed every 5 s per online player (`BehaviourService.applyKarmaLuck`). Per player, persistent.
- **Traders' prices**: a villager (and every villager-derived trader) asks more of a player with negative karma:
  up to +75% at karma -100 (`VillagerPricesMixin`, at the tail of vanilla's `prepareOffersFor`, so vanilla's
  reputation discounts still apply first). Never a discount; the wandering trader is untouched.
- **Worthiness**: the Trial of the Envoy is refused (`luminary.trade.unworthy`, the button reads «Недостоин») to a
  player Lumin is WARY or HOSTILE with, or whose karma is below zero.
The screen's left panel shows the karma and his attitude; hovering it shows trust, threat, the luck and the markup.
- **Alms**: the «Пожертвовать 10 ✧» button under the karma takes 10 emeralds from the player's inventory for +5 karma
  (the `DONATION` event: +5 karma, +2 trust, −10 threat; `KarmaEffects.DONATION_*`), and he answers with a `donation`
  line («Твои подаяния очищают твою душу и смывают грехи»). Disabled without the emeralds; checked again on the server. At the body: a bell, a chime and a level-up note, a ring of light rising, souls and glow drifting off (`TradeManager.almsEffects`).

## Testing commands (op 3)
- `/luminary npc trade lumin status [player]`: the ledger and every quest's state.
- `/luminary npc trade lumin complete [player]`: all 13 story tasks done by decree (points and reward items as for
  a real claim); the trial becomes available if nothing was bought. `… complete [player] all` also completes the
  trial: the blade changes hands, the proclamation goes out.
- `/luminary npc trade lumin reset [player]`: points, tasks and purchases wiped; a blade given to that player is
  reclaimed (`reclaimWeapon`), so the trial can be played again from zero.
- `/luminary npc trade lumin points <player> <amount>`: grant or take points.
- `/luminary npc relations lumin reset [player]`: karma, trust and threat with Lumin back to zero (luck follows at once);
  `/luminary npc relations lumin karma <player> <value>`: karma set outright.

## The proclamation
When the blade changes hands (`transferWeapon`, so the Trial's claim and the admin command alike), every player on the
server reads, in the sword's shimmering lettering framed in ✦, «<Люмин> ✦ Благословенному <ник> передан артефакт ✦»,
then the artifact's shimmering name and a purple line that it now bears the player's name, with a chime
(`npc.luminary.weapon.proclaimed*`).

## Screenshots
`-Dluminary.screenshot=<file>.png` on a client makes `TradeScreenShot` open the screen with a sample snapshot on the
title screen, save that file and `<file>_shop.png`, and quit (no world, so the portrait panel stays empty there).

## Assumptions
- "Оформление лаунчера": the launcher's look was not available here; the screen uses the Luminary palette of the
  rest of the mod (deep navy, warm gold, parchment text). Colours are the constants at the top of `TradeScreen`.
- The boss ids come from Prominence II: Hasturian Era 4.1.1 (`main_story.snbt`), the version the owner's
  `mods.csv` lists. A boss whose mod is missing simply never dies, so its task stays open.
