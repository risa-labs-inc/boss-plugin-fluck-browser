import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.2.3"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo.
        // Also on the test classpath (compileOnly doesn't propagate there) so
        // tests can reference api types like BrowserHandle.
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.51.jar"))
        testImplementation(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.51.jar"))
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
        testImplementation(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Compose Icons (SimpleIcons)
    implementation("br.com.devsrsouza.compose.icons:simple-icons:1.1.1")

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // --- Co-browse tab sharing ---
    // Embedded Ktor (CIO) WebSocket server is BUNDLED child-first into the plugin
    // JAR (io.ktor.* is NOT in the host's parent-first shared set). Pinned to the
    // host's ktor line (3.4.3) so the bundled server stays ABI-compatible with the
    // host-provided kotlinx-coroutines / kotlinx-serialization (parent-first).
    implementation("io.ktor:ktor-server-cio:3.4.3")
    implementation("io.ktor:ktor-server-websockets:3.4.3")
    // Serialization runtime is provided by the host (parent-first) — compile only.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // ZXing for the share-dialog QR code (bundled child-first; com.google.zxing.* is
    // not in the host's parent-first set). Matches the host's zxing line (3.5.4).
    implementation("com.google.zxing:core:3.5.4")

    // Tests
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-fluck-browser-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Fluck Browser Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.fluckbrowser.FluckBrowserDynamicPlugin"
        )
    }

    // Include compiled classes
    from(sourceSets.main.get().output)

    // Include plugin manifest + the cobrowse-viewer web assets
    from("src/main/resources")

    // Bundle the embedded Ktor (CIO) WebSocket server child-first. io.ktor.* (and
    // its child-first runtime deps atomicfu / typesafe-config / kotlinx-io) are NOT
    // in BossConsole's parent-first shared set, so they must ride in the plugin JAR.
    // kotlinx-serialization / kotlinx-coroutines / slf4j / Compose are deliberately
    // omitted — the host provides them parent-first. (Mirrors terminal-tab.)
    // Resolve via a Provider so it survives Gradle's configuration cache. A plain
    // `from({ configurations.runtimeClasspath.get()... })` closure is skipped on a
    // config-cache HIT, silently dropping ktor from the JAR (the embedded WebSocket
    // server then NoClassDefFounds at runtime).
    from(configurations.named("runtimeClasspath").map { cp ->
        cp.filter { jar ->
            val name = jar.name
            name.startsWith("ktor-") ||
                name.startsWith("atomicfu") ||
                name.startsWith("config-") ||
                name.startsWith("kotlinx-io-") ||
                name.startsWith("core-") // com.google.zxing:core (QR code)
        }.map { zipTree(it) }
    })
}

// The default `jar` (classes-only) writes the SAME archive path as buildPluginJar
// and silently clobbers the bundled plugin JAR whenever it runs later in the task
// graph (e.g. `test` resolving the runtime classpath triggers :jar). Classify it
// so the two archives never collide; buildPluginJar's output stays canonical.
tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}
