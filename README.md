# AE2LT Packaged Pattern Provider

## Overview

AE2LT Packaged Pattern Provider is an AE2 Lightning Tech addon for Minecraft 1.21.1 on NeoForge. It adds `Packaged Pattern Provider` and `Wireless Packaged Pattern Provider` blocks and extends AE2 / AE2LT pattern dispatch into selected multiblock crafting integrations.

## Supported Versions

- Minecraft: 1.21.1
- Java: 21
- NeoForge: 21.1.x
- Current addon / AE2LT tested NeoForge: 21.1.230
- AE2 official 1.21.1 branch observed NeoForge: 21.1.169
- Gradle Wrapper: 8.8

Current local verification observed a NeoForge patch-version difference: this addon currently builds with 21.1.230, while the Applied Energistics 2 `1.21.1` branch previously declared 21.1.169. That difference did not block local compilation or tests, but it should not be treated as guaranteed runtime compatibility. If dependency resolution or runtime issues appear, align the NeoForge patch version first.

## Required Dependencies

- Applied Energistics 2
- AE2 Lightning Tech
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

Windows PowerShell example (adjust the JDK path for your machine):

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
```

## Optional Upstream Projects

For source-level debugging or cross-project integration work, clone:

- [AE2 Lightning Tech](https://github.com/MOAKIEE/AE2-Lightning-Tech)
- [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)

## Building Applied Energistics 2 Locally

```powershell
cd C:\Users\Administrator\IdeaProjects\Applied-Energistics-2
git checkout 1.21.1
.\gradlew.bat clean build --no-daemon
```

Local phase-1 verification observed an output jar similar to:

`appliedenergistics2-19.2.18-alpha.3+1.21.1.jar`

## Building AE2 Lightning Tech Locally

```powershell
cd C:\Users\Administrator\IdeaProjects\AE2-Lightning-Tech
.\gradlew.bat clean build --no-daemon
```

Local phase-1 verification observed an output jar similar to:

`ae2lt-1.1.3.jar`

## Configuring AE2LT Dependency

This project uses the published AE2 Lightning Tech Maven coordinate by default:

```properties
ae2lt_maven_notation=maven.modrinth:ae2-lightning-tech:zotfKhYP
```

For local AE2LT development, clear or comment `ae2lt_maven_notation`, then point Gradle at a local jar by using one of:

1. `ae2lt_jar` in `gradle.properties`
2. `AE2LT_JAR` environment variable
3. Legacy `ae2lt_local_jar` property

Example `gradle.properties` entry:

```properties
ae2lt_jar=../AE2-Lightning-Tech/build/libs/ae2lt-1.1.3.jar
```

Example PowerShell override:

```powershell
$env:AE2LT_JAR="C:\Project\AE2-Lightning-Tech\build\libs\ae2lt-1.1.3.jar"
```

If no Maven coordinate is configured and the local jar cannot be found, the build fails with a clear error message instead of a vague compile failure.

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

- The project builds against the published AE2 Lightning Tech Maven coordinate by default; local jar fallback remains available for source-level AE2LT development.
- The AE2 official `1.21.1` branch and the addon / AE2LT projects currently observe different NeoForge patch versions.
- `PackagedPatternProviderLogic` dispatch fallback and registry hardening are in place, but test coverage is still focused on targeted regression paths rather than broad integration scenarios.
- Optional-mod reflection handling is still mixed, but the cached lookup helper now covers several hot-path adapters. New optional-mod hot paths should prefer cached lookup helpers first, and only move to `MethodHandle` / `VarHandle` when profiling shows a real win.
- Full CI success depends on the published AE2 Lightning Tech Maven artifact remaining available from the configured repositories.

## Troubleshooting

- `java -version` shows 8 but `javac -version` shows 21: check `PATH` ordering and `JAVA_HOME`.
- `JAVA_HOME` points to a non-21 JDK: switch the shell to JDK 21 before running Gradle.
- `AE2LT jar not found`: this only applies when `ae2lt_maven_notation` is empty; check `ae2lt_jar`, `AE2LT_JAR`, or the legacy `ae2lt_local_jar`.
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
