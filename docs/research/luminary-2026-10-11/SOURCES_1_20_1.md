# Sources for the 1.20.1 pass (fetched or read 2026-10-11)

- https://piston-meta.mojang.com/mc/game/version_manifest_v2.json → 1.20.1 version JSON (`javaVersion.majorVersion = 17`)
- https://meta.fabricmc.net/v2/versions/yarn/1.20.1 (`1.20.1+build.10`), /loader/1.20.1 (0.19.5)
- https://maven.fabricmc.net/net/fabricmc/fabric-loom/{1.6.12,1.7.4,1.8.13,1.9.2,1.10.5,1.11.8,1.12.7}/fabric-loom-<v>.module (JVM and plugin API versions)
- Yarn 1.20.1+build.10 Javadoc: `world/PersistentState`, `PersistentState.Type`, `PersistentStateManager`,
  `test/GameTest`, `util/Identifier`, `entity/mob/MobEntity`, `entity/ai/pathing/EntityNavigation`, `server/MinecraftServer`, `world/World`
- `FabricMC/fabric` branch `1.20.1`: `ServerPlayNetworking.java`, `FabricPacket.java`, `PacketType.java`,
  `FabricEntityTypeBuilder.java`, `FabricDefaultAttributeRegistry.java`, `ServerLifecycleEvents.java`, `ServerTickEvents.java`,
  `ServerPlayConnectionEvents.java`, `CommandRegistrationCallback.java`, `FabricGameTest.java`, `ResourceManagerHelper.java`,
  `UseBlockCallback.java`, `UseItemCallback.java`, `PlayerBlockBreakEvents.java`
- `FabricMC/fabric-example-mod` branch `1.20`: `gradle.properties` (loader 0.19.5, loom 1.18-SNAPSHOT), wrapper 9.7.1
- Modrinth `/v2/project/<slug>/version?game_versions=["1.20.1"]&loaders=["fabric"]` for geckolib, create-fabric,
  modonomicon, patchouli, polymer, fabric-permissions-api, placeholder-api, owo-lib, cloth-config, architectury-api,
  techreborn, modern-industrialization, fabric-api; `/v2/project/{aspectslib,techreborn,modern-industrialization}`;
  search `thaumcraft` (fabric + 1.20.1 facets); search `ftb quests`
- https://maven.ftb.dev/releases/dev/ftb/mods/{ftb-quests-fabric,ftb-library-fabric,ftb-teams-fabric}/maven-metadata.xml
- GitHub API `FTBTeam/FTB-Quests`: not reachable from this session (repository access not enabled) → license UNVERIFIED
- Repository files: `um/publish.py` (`check`, `SECRET_PATTERNS`, `TEXT_EXT`, `SKIP_DIRS`), `um/kb.py` (`notes`, `check`, `index`),
  `tests/test_um.py` (`test_repo_knowledge_is_valid`, `test_skill_copies_match`), `.github/workflows/test.yml`,
  `hooks/hooks.json`, `.claude/settings.json`, `thaumcraft-fabric/build.gradle`, `thaumcraft-fabric/src/main/java/thaumcraft/Thaumcraft.java`
