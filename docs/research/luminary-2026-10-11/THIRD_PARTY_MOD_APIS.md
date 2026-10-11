# Third-party mod APIs and integration points

Scope: mods that matter for Luminary's eras (industry, thaumaturgy), its UI (books/quests), and its NPC (models).
"API" below means a surface the mod's authors publish for other mods; "internal" means classes that may change
without notice. Where this document says UNVERIFIED, the next step is to read the mod's source at the exact version.

## 1. Integration layer design (independent of any mod)

```java
public interface ModIntegration {
    String modId();                                  // Fabric mod id, e.g. "create", "thaumcraft", "geckolib"
    default boolean isPresent(Predicate<String> modLoaded) { return modLoaded.test(modId()); }
    void initialize(IntegrationContext ctx) throws Exception;   // called only when present; exceptions are isolated
}

public final class IntegrationRegistry {
    // Fabric: FabricLoader.getInstance().isModLoaded(id)  — VERIFIED API (fabric-loader)
    public void initializeAll(Predicate<String> modLoaded, IntegrationContext ctx, Logger log) {
        for (ModIntegration i : integrations) {
            if (!i.isPresent(modLoaded)) { log.info("[luminary] optional mod {} absent, integration skipped", i.modId()); continue; }
            try { i.initialize(ctx); } catch (Throwable t) { log.error("[luminary] integration {} failed and was disabled", i.modId(), t); }
        }
    }
}
```
Rules: integrations are the only code allowed to import another mod's classes; keep each in its own package
(`dev.luminary.integration.<modid>`); never reference those classes from core so that class loading fails inside
the try/catch and not at mod init (a `NoClassDefFoundError` at init would crash the server). Version-specific code goes
behind `FabricLoader.getInstance().getModContainer(id).get().getMetadata().getVersion()` checks (LIKELY API).

Fabric also supports **entrypoint-based** integration: declare `"luminary": ["..."]` custom entrypoints in other
mods' `fabric.mod.json` and read them with `FabricLoader.getInstance().getEntrypointContainers("luminary", LuminaryPlugin.class)`
(VERIFIED mechanism exists in loader; used by many libraries). This is the cleanest way for the Thaumcraft port or
add-ons to register discoveries/technologies into Luminary **without Luminary depending on them**.

## 2. GeckoLib 4.9.3 (Fabric 1.21.1) — VERIFIED availability, LIKELY API details

- Modrinth: license MIT, loaders fabric/forge/neoforge/quilt, file `geckolib-fabric-1.21.1-4.9.3.jar` (2026-09-16),
  depends on Fabric API. Source: `github.com/bernie-g/geckolib`.
- Model/animation pipeline: Blockbench "GeckoLib Animated Model" export → `assets/<ns>/geo/*.geo.json`,
  `animations/*.animation.json`, textures; entity implements `GeoEntity` with an `AnimatableInstanceCache` and
  registers controllers (`AnimationController`) that pick a `RawAnimation` per tick from entity state (LIKELY, 4.x API).
- Server/client split: animation controllers run **client-side**; the server drives them by syncing state
  (DataTracker fields or GeckoLib's `triggerAnim` for one-shots — LIKELY). This matches the requirement "animation
  reflects actual task state": the server publishes `TaskPhase` (IDLE, WALKING, CHOPPING, CARRYING, FAILED), the
  client maps it to animations; no gameplay runs in the animation layer.
- Maven coordinates for Gradle were not fetched in this session (README fetch returned nothing on the `1.21.1`
  branch name); the GeckoLib wiki lists a Cloudsmith/own maven — UNVERIFIED, check before adding.
- Alternative without a dependency: vanilla `EntityModel` + `EntityRenderer` with hand-written animation
  (`AnimationHelper`/keyframe `Animation` classes exist in vanilla since 1.19.3 — LIKELY).

## 3. Create (Fabric 6.0.8.1, **1.20.1 only**) — VERIFIED availability

- No 1.21.1 Fabric build. `createtaumechanics` on the owner's Thaumcraft branch already targets it, with Create block
  entity types for encased shafts/cogwheels and the `create:item_application` recipe type (from the commit message,
  LIKELY accurate).
- Public surface (LIKELY): recipe types (`create:mixing`, `create:pressing`, `create:item_application`), block tags
  (`create:casing`), `KineticBlockEntity` for stress/speed, ponder scenes. Reading "did the civilization build a
  working kinetic network" = count/sample `KineticBlockEntity` with non-zero speed inside a claimed city region
  (LIKELY feasible; internal class, pin the version).
- For a 1.21.1 Luminary, the industry era would need a different mod (e.g. Create on NeoForge is out of scope for
  Fabric; Tech Reborn / Modern Industrialization have 1.21.1 Fabric builds — UNVERIFIED this session).

## 4. Modonomicon (MIT + CC-BY-4.0, Fabric 1.21.1) — VERIFIED availability

- "In-game documentation mod inspired by Thaumcraft's Thaumonomicon and Patchouli", multiloader, source
  `github.com/klikli-dev/modonomicon`.
- Books are data-driven JSON (`data/<ns>/modonomicon/books/...`) with **entry unlock conditions** (advancement-based
  and others) — LIKELY. For the Catalog of the Unknown the question is whether entries can be unlocked by a
  server-side condition that Luminary controls (e.g. a custom condition type or an advancement Luminary grants when a
  discovery becomes VERIFIED). Granting a hidden advancement per verified discovery is a loader-neutral bridge that
  needs no Modonomicon API at all (LIKELY workable). UNVERIFIED: whether Modonomicon exposes a custom-condition
  registry on Fabric 1.21.1.
- Risk: Modonomicon renders the same book for everyone; "public knowledge" fits, "restricted knowledge" needs
  per-player conditions, which advancements provide.

## 5. Patchouli (CC-BY-NC-SA-3.0) — VERIFIED availability and license

Functionally similar; the non-commercial clause makes it unsuitable if the server earns money. Prefer Modonomicon.

## 6. Polymer (LGPL-3.0, Fabric 1.21.1) — VERIFIED availability

Lets a server-only mod show custom items/blocks/entities to **vanilla** clients. Relevant decision: if the owner wants
players to join without installing Luminary client-side, Polymer is the route; GeckoLib models would then be replaced
by Polymer's display-entity-based models. UNVERIFIED feasibility for an animated humanoid NPC; LIKELY feasible for
UI/items.

## 7. fabric-permissions-api (MIT, lucko) — VERIFIED availability

`Permissions.check(ServerCommandSource, "node", defaultOpLevel)` returns a `Predicate` usable in `.requires(...)`
(LIKELY). Falls back to op level when no permission provider (LuckPerms) is installed. Use for
`luminary.story.*`, `luminary.catalog.verify`, `luminary.npc.admin`.

## 8. FTB Quests — UNVERIFIED for Fabric 1.21.1

Not found on Modrinth under the slugs tried; distributed on CurseForge/FTB maven (LIKELY). Kindred read quest
titles as advisory hints only. If the modpack uses FTB Quests, Luminary should treat quest completion as an
**input signal** to progression (via its API events), never as the source of truth.

## 9. The Thaumcraft 4 port (`thaumcraft-fabric`, 1.20.1)

Covered in `THAUMATURGY_RESEARCH.md`. Summary: no public API module exists yet (VERIFIED in the local snapshot: all
code is in `thaumcraft.*` implementation packages); the Luminary bridge should be an entrypoint/event contract that the
port implements, not Luminary importing `thaumcraft.research.*`.
