# Build configuration proposal for Luminary on 1.20.1 (not executed)

Nothing here was run. Pins come from `MINECRAFT_FABRIC_1_20_1.md` and the Loom module metadata read in this session.

## Loom / Gradle / JVM pairing (VERIFIED from `fabric-loom-<v>.module`)

| Loom | JVM required | Gradle plugin API | Used by |
|---|---|---|---|
| 1.6.12 | 8 (any modern JDK works) | not declared in metadata | `thaumcraft-fabric` (Gradle 8.8), Kindred (Gradle 8.7) |
| 1.10.5 | 17 | 8.12 → Gradle ≥ 8.12 | — |
| 1.11.8 / 1.12.7 | 21 | 8.14 (1.12.7) | needs a Java 21 JDK to build; still can target `release 17` |
| 1.13.6–1.17.21 | 21 | 9.5 (1.17.21) | — |
| 1.18.4 | 25 | 9.7 | `fabric-example-mod` (all branches, incl. `1.20`), bundled 26.3 example |

Recommendation: **Loom 1.6.12 + Gradle 8.8 wrapper + JDK 17**, identical to the Thaumcraft port, so one JDK and one
Gradle line serve both projects and the port's GameTest harness translates 1:1. Alternative with a newer toolchain:
Loom 1.10.5 + Gradle 8.12+ + JDK 17. Avoid Loom ≥ 1.11 unless the owner standardizes on JDK 21.

Risk to confirm (UNVERIFIED): Loom 1.6.x against newer Gradle 8.x releases prints deprecation warnings (Kindred note:
"deprecated Gradle features (non-critical)"); it built fine there.

## `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
minecraft_version=1.20.1
yarn_mappings=1.20.1+build.10
loader_version=0.19.5
fabric_version=0.92.12+1.20.1
loom_version=1.6.12
mod_version=0.1.0
maven_group=dev.luminary
archives_base_name=luminary
# optional
geckolib_version=4.8.4
```

## `build.gradle` (Groovy)

```groovy
plugins { id 'fabric-loom' version "${loom_version}"; id 'maven-publish' }
version = project.mod_version; group = project.maven_group
base { archivesName = project.archives_base_name }
repositories {
    mavenCentral()
    maven { url = 'https://maven.fabricmc.net/' }
    // GeckoLib (optional dep): coordinates UNVERIFIED this session — read https://github.com/bernie-g/geckolib wiki "Getting started" for 1.20.1 before adding
}
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
    // modCompileOnly for optional integrations (thaumcraft port jar via a flatDir/local maven; GeckoLib)
    testImplementation platform('org.junit:junit-bom:5.10.2'); testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
sourceSets { gametest { compileClasspath += main.compileClasspath + main.output; runtimeClasspath += main.runtimeClasspath + main.output } }
loom {
    mods { luminary { sourceSet sourceSets.main }; 'luminary-gametest' { sourceSet sourceSets.gametest } }
    runs {
        gametest { server(); name 'Game Test'; source sourceSets.gametest; vmArg '-Dfabric-api.gametest'
                   vmArg "-Dfabric-api.gametest.report-file=${project.layout.buildDirectory.get().asFile}/gametest/junit.xml"; runDir 'build/gametest' }
        server   { server(); runDir 'run/server' }   // smoke test; pre-create run/server/eula.txt with eula=true
    }
}
processResources { inputs.property 'version', project.version; filesMatching('fabric.mod.json') { expand 'version': project.version } }
tasks.withType(JavaCompile).configureEach { options.release = 17; options.encoding = 'UTF-8' }
java { withSourcesJar(); sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
test { useJUnitPlatform() }
```
The `gametest` source set, second Loom mod and run config are copied from the port's working `build.gradle`
(VERIFIED there). JUnit in a Loom project is LIKELY fine (Kindred: 19 unit tests).

## `fabric.mod.json`

```json
{ "schemaVersion": 1, "id": "luminary", "version": "${version}", "name": "Luminary", "environment": "*",
  "entrypoints": { "main": ["dev.luminary.fabric.Luminary"] },
  "depends": { "fabricloader": ">=0.16.0", "minecraft": "1.20.1", "java": ">=17", "fabric-api": "*" },
  "suggests": { "geckolib": "*", "thaumcraft": "*" },
  "custom": { "luminary": {} } }
```
GameTests go in the `gametest` source set's own `fabric.mod.json` with `"entrypoints": {"fabric-gametest": [...]}`
(LIKELY key; the port's `src/gametest/resources` holds the real example — read it before copying).

## Building against the Thaumcraft port

Use `modCompileOnly files("libs/thaumcraft-fabric-<ver>.jar")` or publish the port to `mavenLocal()` from its
own build (`maven-publish` is already applied there — VERIFIED). Never commit the jar. `createtaumechanics` on the
port's branch already does "built against the Thaumcraft port jar" (commit message — LIKELY the same approach;
read its `build.gradle` when access to that branch is wanted).

## Container-specific notes

In this cloud container `repo.maven.apache.org` intermittently answered HTTP 429; `repo1.maven.org` did not. Only
JDK 21 is installed here; a 1.20.1 build would run with `options.release = 17` on JDK 21 (LIKELY fine with Loom
1.6.12/1.10.5), but the recommended local setup is JDK 17.
