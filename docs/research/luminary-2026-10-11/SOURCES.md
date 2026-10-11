# Sources (all fetched or read on 2026-10-11)

## Repository files read
- `universal-modder`: `AGENTS.md`, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, `pyproject.toml`, `.gitignore`,
  `bin/um`, `um/kb.py` (notes enumeration, check, index), `tests/test_um.py` (test list), `.github/workflows/test.yml`,
  `skills/mod-any-game/references/engines/minecraft.md`, `examples/minecraft-gta5-passthrough/mc/{build.gradle,
  gradle.properties, gradle/wrapper/gradle-wrapper.properties, src/main/resources/fabric.mod.json}`,
  `knowledge/games/minecraft/kindred-local-llm-ai-companion-fabric.md`,
  `knowledge/games/minecraft/neoforge-1-21-1-custom-dimension-and-tool-tier.md` (header).
- `thaumcraft-fabric`: `CLAUDE.md`, `gradle.properties`, `build.gradle`, `src/main/resources/fabric.mod.json`,
  `docs/MIGRATION_STATUS.md`, `src/main/java/thaumcraft/Thaumcraft.java`, package listings, `LICENSE` head.

## GitHub API (via the GitHub MCP server)
- Branch lists and recent commits of `aetherna1111-glitch/universal-modder` (`integration/thaumcraft-port`,
  `skill/port-a-mod`) and `aetherna1111-glitch/thaumcraft-fabric` (`feature/createtaumechanics-and-sborka`,
  `claude/epic-pasteur-gzffxb`, `claude/loving-bohr-pt98hn`).

## Fabric / Minecraft
- https://meta.fabricmc.net/v2/versions/loader/1.21.1 and /1.20.1 — loader 0.19.5 newest
- https://meta.fabricmc.net/v2/versions/yarn/1.21.1 — `1.21.1+build.3`
- https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml and
  `.../fabric-loom/<v>/fabric-loom-<v>.module` for 1.13.6, 1.14.10, 1.15.5, 1.16.3, 1.17.21, 1.18.4 (JVM / plugin API versions)
- https://maven.fabricmc.net/docs/yarn-1.21.1+build.3/net/minecraft/world/PersistentState.html, `PersistentState.Type.html`,
  `PersistentStateManager.html`, `server/world/ServerWorld.html`, `test/GameTest.html`, `entity/EntityType.Builder.html`
- https://raw.githubusercontent.com/FabricMC/fabric/1.21.1/ … `ServerLifecycleEvents.java`, `ServerTickEvents.java`,
  `ServerPlayConnectionEvents.java`, `CommandRegistrationCallback.java`, `PlayerBlockBreakEvents.java`,
  `FabricGameTest.java`, `ServerPlayNetworking.java`, `PayloadTypeRegistry.java`, `ResourceManagerHelper.java`,
  `FabricEntityTypeBuilder.java` (deprecated), `FabricEntityType.java`, `FabricDefaultAttributeRegistry.java`
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/1.21/gradle.properties and
  `gradle/wrapper/gradle-wrapper.properties` — Loom `1.18-SNAPSHOT`, Gradle 9.7.1

## Modrinth API
- `/v2/project/{geckolib, create-fabric, create, modonomicon, thaumon, patchouli, polymer, fabric-permissions-api,
  placeholder-api, owo-lib, cloth-config, architectury-api, fabric-api}` and `/version` listings filtered by
  `game_versions=["1.21.1"]`/`["1.20.1"]`; `/v2/search?query=thaumcraft` with fabric + 1.21.1 facets.
  `ftb-quests`, `ftb-quests-fabric`, `ftb-library`, `ftb-library-fabric`, `ftb-teams` → 404.

## Ollama
- https://raw.githubusercontent.com/ollama/ollama/main/docs/api.md — `/api/chat` parameters (`format`, `think`,
  `keep_alive`, `options`, `stream`)

## Session tool output
- `java -version` (21.0.12.1), `/opt/gradle/bin/gradle --version` (8.14.3), `uv run --with pytest pytest -q tests`
  (87 passed, 1 skipped), two failed Gradle configuration attempts in `luminary/` (Loom 1.18.4: JVM 25 required;
  Loom 1.17.21: Gradle plugin API 9.5.0 required).
