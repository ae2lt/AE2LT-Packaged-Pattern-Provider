# AE2LT Packaged Pattern Provider

## Overview

AE2LT Packaged Pattern Provider is an AE2 Lightning Tech addon for Minecraft 1.20.1 on Forge. It adds `Packaged Pattern Provider` and `Wireless Packaged Pattern Provider` blocks and extends AE2 / AE2LT pattern dispatch into selected multiblock crafting integrations.

## Supported Versions

- Minecraft: 1.20.1
- Java: 17
- Forge: 47.1.x (development baseline: 47.1.47)
- Applied Energistics 2: 15.4.10 (range `[15.4.10,16)`)
- AE2 Lightning Tech: 2.1.0-beta.1 (`refactor/thunderbolt-three-layer-clean-1.20.1`)
- Thunderbolt Core: 2.0.0-beta.1 (`refactor/thunderbolt-three-layer-clean-1.20.1`)
- AE2WTLib: 15.3.3-forge
- Curios: 5.14.1+1.20.1
- Gradle Wrapper: 8.8

This branch is the Forge 1.20.1 port of the addon. The `main` branch targets
Minecraft 1.21.1 on NeoForge; both consume the same three-layer-clean refactor of
AE2 Lightning Tech and Thunderbolt Core, so the overload-pattern contract
(`OverloadedPatternDetails`) and the wireless support types are shared, while the
platform layer (registries, capabilities, recipe and NBT APIs) differs.

## Required Dependencies

- Applied Energistics 2
- AE2 Lightning Tech
- Thunderbolt Core
- AE2WTLib
- Curios
- Forge

The current `build.gradle` also uses:

- GuideME
- JEI runtime dependency for local development
- Jade runtime dependency for local development

## Development Setup

You need:

- JDK 17
- Git
- The included Gradle Wrapper
- A Minecraft 1.20.1 Forge mod development environment

Both AE2 Lightning Tech and Thunderbolt Core have to be present in Maven Local
before this branch will resolve. Build each from its
`refactor/thunderbolt-three-layer-clean-1.20.1` branch:

```powershell
# Thunderbolt Core publishes itself
.\gradlew.bat publishToMavenLocal

# AE2 Lightning Tech has no publishing block; build the jar and install it as
# com.moakiee.ae2lt:ae2lt:2.1.0-beta.1
.\gradlew.bat jarJar
```

Then place `build/libs/ae2lt-forge-1.20.1-2.1.0-beta.1.jar` in
`~/.m2/repository/com/moakiee/ae2lt/ae2lt/2.1.0-beta.1/` as
`ae2lt-2.1.0-beta.1.jar` alongside a minimal POM.

Forge only applies access transformers declared by the project itself, so AE2's
transformer is vendored at `gradle/dev/ae2-dev-accesstransformer.cfg` purely so
the `runClient` / `runServer` / `runGameTestServer` tasks can load AE2. It is not
part of the published jar; in production FML applies AE2's own copy.

Windows PowerShell example:

```powershell
java -version
javac -version
```

## Optional Upstream Projects

For source-level debugging or cross-project integration work, clone:

- [AE2 Lightning Tech](https://github.com/ae2lt/AE2-Lightning-Tech)
- [Thunderbolt Core](https://github.com/ae2lt/Thunderbolt-Core)
- [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)

## Building Applied Energistics 2 Locally

```powershell
git checkout 1.20.1
.\gradlew.bat clean build --no-daemon
```

This branch resolves AE2 `15.4.10` from `modmaven.dev`, so a local AE2 build is
only needed for source-level debugging.

## Building AE2 Lightning Tech and Thunderbolt Core Locally

```powershell
cd <path>\Thunderbolt-Core
git checkout refactor/thunderbolt-three-layer-clean-1.20.1
.\gradlew.bat clean publishToMavenLocal --no-daemon

cd <path>\AE2-Lightning-Tech
git checkout refactor/thunderbolt-three-layer-clean-1.20.1
.\gradlew.bat clean jarJar --no-daemon
```

Thunderbolt publishes itself as `com.moakiee.thunderbolt:thunderbolt:2.0.0-beta.1`.

AE2 Lightning Tech has no `maven-publish` block on this line, so its reobfuscated
`jarJar` output has to be installed by hand as
`com.moakiee.ae2lt:ae2lt:2.1.0-beta.1`:

```
~/.m2/repository/com/moakiee/ae2lt/ae2lt/2.1.0-beta.1/
    ae2lt-2.1.0-beta.1.jar   <- build/libs/ae2lt-forge-1.20.1-2.1.0-beta.1.jar
    ae2lt-2.1.0-beta.1.pom   <- minimal POM with the same coordinates
```

Use the reobfuscated `jarJar` artifact, not the `-slim` one: this branch consumes
it through `fg.deobf`, which expects production (SRG) names.

## Configuring the AE2LT and Thunderbolt Dependencies

Both are plain Maven coordinates resolved from `mavenLocal()`, pinned by
`gradle.properties`:

```properties
ae2lt_version=2.1.0-beta.1
thunderbolt_version=2.0.0-beta.1
```

AE2LT is resolved non-transitively, because this addon declares its own
development runtime rather than inheriting AE2LT's full local mod set.

## Build

```powershell
.\gradlew.bat clean build --no-daemon
```

## Test

```powershell
.\gradlew.bat clean test --no-daemon
```

## Current Project Architecture

- `AE2LTPackagedProvider.java`: mod entry point
- `registry`: block, item, block entity, and creative tab registration
- `block`: block definitions
- `blockentity`: packaged provider block entities
- `logic`: core packaged dispatch logic
- `logic/multiblock`: multiblock adapter framework
- `resources`: language files, models, blockstates, loot tables, and the `mods.toml` template
- `test`: pure logic unit tests

## Supported Multiblock Integrations

Integration code currently exists for:

- Actually Additions
- Ars Nouveau
- Draconic Evolution
- Extended Crafting
- Mystical Agriculture
- Occultism
- Mekanism More Machines
- Malum
- Botania

These entries reflect source code present in the repository, not a blanket statement that every target has already been runtime-validated in every environment.

## How to Add a New Multiblock Adapter

1. Add a new adapter package under `logic/multiblock`.
2. Implement the existing `MultiblockAdapter` interface, returning a binding result that identifies whether the target should run virtually or through real dispatch.
3. Register the adapter during common setup or registry initialization.
4. Use reflection or equivalent safe detection for optional mod access.
5. Add matching and failure-path tests.
6. Update this README support list.

## Known Limitations

- This branch targets AE2LT `2.1.0-beta.1` and Thunderbolt `2.0.0-beta.1`; it is not load-compatible with the pre-refactor dependency line.
- **Third-party integration targets are not yet re-verified for 1.20.1.** Every
  adapter under `logic/multiblock/<mod>/` reaches its target mod purely by
  reflection and degrades to a no-op when a class, method or field is absent, so
  the port loads and runs safely. The reflection target names, and the recipe
  JSON under `data/ae2ltpp/recipes/packaged_core/`, still describe the 1.21.1
  builds of those mods. Each of the twelve integrations needs to be re-checked
  against its actual 1.20.1 jar before it can be called supported; until then
  assume an adapter is inactive rather than working.
- `PackagedPatternProviderLogic` dispatch fallback and registry hardening are in place, but test coverage is still focused on targeted regression paths rather than broad integration scenarios.
- Optional-mod reflection handling is still mixed, but the cached lookup helper now covers several hot-path adapters. New optional-mod hot paths should prefer cached lookup helpers first, and only move to `MethodHandle` / `VarHandle` when profiling shows a real win.
- A clean machine or CI runner must provide matching AE2LT and Thunderbolt refactor artifacts through the documented local-jar or Maven overrides.

## Troubleshooting

- `JAVA_HOME` points to a non-17 JDK: ForgeGradle 6 needs to run on JDK 17 for
  this branch; switch the shell before invoking Gradle.
- `Could not find com.moakiee.ae2lt:ae2lt` or `com.moakiee.thunderbolt:thunderbolt`:
  build both dependencies from their `-1.20.1` refactor branches into Maven Local
  as described under Development Setup.
- `IllegalAccessError` mentioning `net.minecraft.world.level.block.Blocks` during a
  run task: `gradle/dev/ae2-dev-accesstransformer.cfg` is missing or stale for the
  configured `ae2_version`; refresh it from the AE2 jar's
  `META-INF/accesstransformer.cfg`.
- `Invalid AccessTransformer line`: Forge's parser rejects a bare `#`, so every
  comment line in that file needs text after the marker.
- Gradle is using the wrong JVM: run `.\gradlew.bat --version` and check the JVM line.

## Port Verification Status

Verified locally on this branch:

- `gradlew build` succeeds against Forge `47.1.47` / AE2 `15.4.10` on JDK 17.
- All 57 unit tests pass, including the AE2LT dependency-boundary test that keeps
  main sources on `com.moakiee.ae2lt.api.*` plus the documented wireless support
  contract.
- `gradlew runGameTestServer` reaches a started server and shuts down cleanly.
  The mod initializes, all three mixins apply to their AE2 targets, registration
  completes, and no datapack or recipe parse errors are reported.

Not yet verified:

- In-game behaviour of the provider blocks (a `runClient` session).
- Any of the twelve third-party multiblock integrations — see Known Limitations.
