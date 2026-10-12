# ADR-0001: Where Luminary lives and what it targets

Date: 2026-10-12 · Status: accepted (location provisional)

## Decision

1. Luminary targets **Minecraft Java 1.20.1, Fabric Loader ≥ 0.16, Fabric API 0.92.12+1.20.1, Java 17**
   (owner's instruction of 2026-10-11, overriding an earlier 1.21.1 assumption).
2. The first slice lives in **`universal-modder/luminary/`** as a standalone Gradle project with its own wrapper.
   The repository's CI is not changed by this ADR; a separate workflow file may add a Gradle job.

## Context

- The owner's existing stack (the Thaumcraft 4 port, Create Fabric 6.0.8.1, the Kindred NPC precedent, the
  modpack work) is entirely 1.20.1; Create Fabric has no 1.21.1 build. See
  `../../../docs/research/luminary-2026-10-11/TARGET_1_20_1_CORRECTION.md`.
- The owner authorized the first slice without choosing a repository (open question Q2). The designated working
  branch exists only in this repository, so the code is placed here. Moving the directory to its own repository later
  is a `git subtree split` / copy, with no code change.

## Consequences

- Two Gradle builds live in this repository (`examples/minecraft-gta5-passthrough/mc` on 26.3 and `luminary/`).
  They share nothing and must not be run concurrently.
- `um publish check .` will scan this directory: no secrets, no third-party jars, no game files may be committed.
- The pure-Java core (`dev.luminary.core`) is kept free of Minecraft imports so that a later move to 1.21.x only
  touches `dev.luminary.fabric`.
