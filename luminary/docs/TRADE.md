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

- **The Trial of the Envoy** is LOCKED until the player has completed 5 tasks **and bought nothing**. One purchase,
  ever, closes it for good (the ledger's `purchases` never goes back to zero). Claiming it calls
  `BehaviourService.transferWeapon`: custody becomes TRANSFERRED, the player receives the live blade, and
  `HolySwordItem.transferTo` swaps the owner id on the item from Lumin's to the player's UUID
  (`LuminaryOwnerPlayer`, name in `LuminaryOwnerLiteral`), keeping the custody instance, marks, runes, blessings and
  every attribute. The tooltip reads «Принадлежит: <player>» and «Дар Люмина…». Without an NPC owner the blade is
  never recalled and never purged. `/luminary npc weapon reclaim lumin` still takes it back (admin).
- **The Angelic Harp** (`luminary:angelic_harp`): once the blade is gone Lumin fights with the harp (unbreakable,
  his name on it, drawn only while fighting, never loot). Within 6 blocks of his opponent he plays the Chord of
  Light instead of the blade's beam. A player's harp from the creative tab: 6 damage, hits make the target glow,
  right-click = Chord of Light (7 holy damage, 14 to the undead, slow and glowing in 6 blocks; allies heal 3 hearts
  and regenerate; 10 s cooldown).
- **Lightstone dust** (`luminary:lightstone_dust`): a glinting material only he sells.
- **Seal of the Envoy** (`luminary:envoy_seal`): right-click = Blessing (players in 6 blocks heal 3 hearts, get
  Absorption II and Regeneration; monsters are weakened and glow; 90 s cooldown).
- **Lines** (character sheet groups, `lumin.json`): `trade_greeting`, `trade_claimable`, `trade_special`,
  `quest_accepted`, `quest_claimed`, `trade_bought`, `sword_given`.

## Assumptions
- "Оформление лаунчера": the launcher's look was not available here; the screen uses the Luminary palette of the
  rest of the mod (deep navy, warm gold, parchment text). Colours are the constants at the top of `TradeScreen`.
- The boss ids come from Prominence II: Hasturian Era 4.1.1 (`main_story.snbt`), the version the owner's
  `mods.csv` lists. A boss whose mod is missing simply never dies, so its task stays open.
