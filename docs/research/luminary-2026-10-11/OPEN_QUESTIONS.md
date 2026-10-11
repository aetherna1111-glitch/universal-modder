# Open questions for the owner (ranked by impact)

**Q1. Minecraft version: 1.20.1 or 1.21.1?** The thaumaturgy source (`thaumcraft-fabric`), Create Fabric, the
modpack work on `feature/createtaumechanics-and-sborka` and the Kindred NPC precedent are all **1.20.1**; Create
Fabric has no 1.21.1 build (VERIFIED). Recommendation: 1.20.1 now, pure-Java core so a later move is cheap.
Decision needed before any code.

**Q2. Where does Luminary live?** New repository (recommended) vs `universal-modder/luminary/` vs inside the
Thaumcraft port (not recommended; its `CLAUDE.md` forbids non-TC4 gameplay). See `PROJECT_MAP.md` §3.

**Q3. Client-side mod required, or vanilla clients (Polymer)?** Decides GeckoLib vs Polymer models and whether
custom screens are possible.

**Q4. Book/UI front end:** Modonomicon (MIT) vs custom screens vs Patchouli (non-commercial license).

**Q5. Thaumcraft bridge mechanism:** add a small event/API surface to the port (needs the other agent's cooperation)
vs Luminary-side mixins (fragile). The port currently has no event package (VERIFIED at `e197531`).

**Q6. LLM hosting:** does the dedicated server host have a GPU? If not: remote provider (cost, privacy) or
`DisabledProvider` only. Which provider is acceptable, and is any paid API approved? (No charges may be incurred
without approval.)

**Q7. Authority over the NPC:** who may assign tasks (everyone / Luminarium members / admins)?

**Q8. Visibility rules:** which discoveries are RESTRICTED and to whom (roles? facilities?).

**Q9. Strategic approaches (containment / investigation / exploitation):** chosen by admins, by a vote facility, or
emergent from aggregate behaviour?

**Q10. Licensing of the port and of Luminary:** MIT like the toolkit? Required before any public jar.

**Q11. Cleanup of the untracked `universal-modder/luminary/` scaffold** left by this session (see
`SESSION_NOTES.md`): delete, or keep as toolchain reference?

**Q12. Should this research be converted into `knowledge/techniques/*.md` notes** (requires regenerating
`knowledge/INDEX.md`, a protected file under the current mandate)?
