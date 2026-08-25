# AE2LT Packaged Pattern Provider

## Overview

AE2LT Packaged Pattern Provider is an AE2 Lightning Tech addon for Minecraft 1.21.1 on NeoForge. It adds `Packaged Pattern Provider` and `Wireless Packaged Pattern Provider` blocks and extends AE2 / AE2LT pattern dispatch into selected multiblock crafting integrations.

## Supported Versions

- Minecraft: 1.21.1
- Java: 21
- NeoForge: 21.1.x
- AE2 Lightning Tech: 2.1.0-beta.1 (`refactor/thunderbolt-three-layer-clean`)
- Thunderbolt Core: 2.0.0-beta.1 (`refactor/thunderbolt-three-layer-clean`)
- Current addon / AE2LT tested NeoForge: 21.1.230
- AE2 official 1.21.1 branch observed NeoForge: 21.1.169
- Gradle Wrapper: 8.8

Current local verification observed a NeoForge patch-version difference: this addon currently builds with 21.1.230, while the Applied Energistics 2 `1.21.1` branch previously declared 21.1.169. That difference did not block local compilation or tests, but it should not be treated as guaranteed runtime compatibility. If dependency resolution or runtime issues appear, align the NeoForge patch version first.

## Required Dependencies

- Applied Energistics 2
- AE2 Lightning Tech
- Thunderbolt Core
- AE2WTLib
- Curios
- NeoForge

The current `build.gradle` also uses:

- GuideME
- JEI runtime dependency for local development
- Jade runtime dependency for local development

## Development Setup

You need:

- JDK 21
- Git
- The included Gradle Wrapper
- A Minecraft 1.21.1 NeoForge mod development environment

Windows PowerShell example:

```powershell
java -version
javac -version
```

## Optional Upstream Projects

For source-level debugging or cross-project integration work, clone:

- [AE2 Lightning Tech](https://github.com/MOAKIEE/AE2-Lightning-Tech)
- [Thunderbolt Core](https://github.com/ae2lt/Thunderbolt-Core)
- [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)

## Building Applied Energistics 2 Locally

```powershell
git checkout 1.21.1
.\gradlew.bat clean build --no-daemon
```

Local phase-1 verification observed an output jar similar to:

`appliedenergistics2-19.2.18-alpha.3+1.21.1.jar`

## Building AE2 Lightning Tech Locally

```powershell
cd C:\Users\Administrator\IdeaProjects\Thunderbolt-Core
git checkout refactor/thunderbolt-three-layer-clean
.\gradlew.bat clean build publishToMavenLocal --no-daemon

cd C:\Users\Administrator\IdeaProjects\AE2-Lightning-Tech
git checkout refactor/thunderbolt-three-layer-clean
.\gradlew.bat clean build --no-daemon
```

The matching refactor builds produce jars similar to:

- `thunderbolt-2.0.0-beta.1.jar`
- `ae2lt-2.1.0-beta.1.jar`

## Configuring AE2LT Dependency

This branch uses the sibling AE2 Lightning Tech refactor build by default:

```properties
ae2lt_maven_notation=
ae2lt_jar=../AE2-Lightning-Tech/build/libs/ae2lt-2.1.0-beta.1.jar
```

You can override the jar through `ae2lt_jar`, `AE2LT_JAR`, or the legacy
`ae2lt_local_jar` property. A Maven coordinate may still be supplied through
`ae2lt_maven_notation`; its transitive development runtime is disabled because
this addon declares its own runtime mod set.

## Configuring Thunderbolt Dependency

Thunderbolt 2.0.0-beta.1 is resolved from `mavenLocal()` by default after running
its `publishToMavenLocal` task. To use a jar directly, clear
`thunderbolt_maven_notation` and set `thunderbolt_jar`, `THUNDERBOLT_JAR`, or the
legacy `thunderbolt_local_jar` property:

```properties
thunderbolt_maven_notation=
thunderbolt_jar=../thunderbolt/build/libs/thunderbolt-2.0.0-beta.1.jar
```

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
- `resources`: language files, models, blockstates, loot tables, and `neoforge.mods.toml` template
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
- The AE2 official `1.21.1` branch and the addon / AE2LT projects currently observe different NeoForge patch versions.
- `PackagedPatternProviderLogic` dispatch fallback and registry hardening are in place, but test coverage is still focused on targeted regression paths rather than broad integration scenarios.
- Optional-mod reflection handling is still mixed, but the cached lookup helper now covers several hot-path adapters. New optional-mod hot paths should prefer cached lookup helpers first, and only move to `MethodHandle` / `VarHandle` when profiling shows a real win.
- A clean machine or CI runner must provide matching AE2LT and Thunderbolt refactor artifacts through the documented local-jar or Maven overrides.

## Troubleshooting

- `java -version` shows 8 but `javac -version` shows 21: check `PATH` ordering and `JAVA_HOME`.
- `JAVA_HOME` points to a non-21 JDK: switch the shell to JDK 21 before running Gradle.
- `Could not find com.moakiee.ae2lt:AE2-Lightning-Tech`: run `publishToMavenLocal` in the AE2 Lightning Tech project and confirm that its version matches `ae2lt_maven_notation`.
- Gradle is using the wrong JVM: run `.\gradlew.bat --version` and check the JVM line.
- NeoForge version conflicts: compare the three projects' `gradle.properties`, generated mod metadata, and dependency resolution results.

## Version Alignment Notes

Local phase-1 verification produced the following baseline:

- Applied Energistics 2: `1.21.1` branch built successfully, observed NeoForge `21.1.169`
- AE2 Lightning Tech: `main` branch built successfully
- AE2LT Packaged Pattern Provider: built successfully, observed NeoForge `21.1.230`

That version difference did not block local compilation or tests, but runtime compatibility should still be verified. If runtime issues appear later, prioritize:

1. Aligning the NeoForge patch version across AE2, AE2LT, and this addon
2. Re-checking the AE2 dependency version in this addon
3. Reviewing `neoforge.mods.toml` dependency ranges
4. Inspecting Gradle dependency resolution output
