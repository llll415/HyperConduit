import java.util.zip.ZipFile
import java.security.MessageDigest

// NeoForge entrypoint module: a thin shell around mod-common.
// The jar bundles mod-common, the reference core, and the sidecar platform matrix so that
// NeoForge's mod list sees a single self-contained artifact.
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

dependencies {
    implementation(project(":mod-common"))
    implementation(project(":core"))
    implementation(project(":sidecar-runtime"))

    compileOnly("org.spongepowered:mixin:0.8.7")

    testImplementation(project(":core"))
}

neoForge {
    version = "21.1.252"

    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }

    runs {
        create("server") {
            server()
            programArgument("--nogui")
            gameDirectory = layout.projectDirectory.dir("run-server")
            systemProperty("mixin.debug.export", "true")
        }
    }

    mods {
        create("hyperconduit") {
            sourceSet(sourceSets.main.get())
            sourceSet(project(":mod-common").sourceSets.main.get())
            sourceSet(project(":sidecar-runtime").sourceSets.main.get())
            sourceSet(project(":core").sourceSets.main.get())
        }
    }
}

// Bundle shared modules into the NeoForge jar by unpacking their contents, so the mod is a
// single self-contained artifact (NeoForge's mods folder would otherwise see library jars that
// carry no mod metadata). Uses withType rather than a concrete task class because ModDevGradle may
// substitute its own Jar subtype.
tasks.withType<org.gradle.jvm.tasks.Jar>().matching { it.name == "jar" }.configureEach {
    archiveBaseName.set("hyperconduit-neoforge")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    dependsOn(project(":mod-common").tasks.named("jar"))
    dependsOn(project(":core").tasks.named("jar"))
    dependsOn(project(":sidecar-runtime").tasks.named("jar"))

    from({
        zipTree(project(":mod-common").tasks.named("jar").get().outputs.files.singleFile)
    })
    from({
        zipTree(project(":core").tasks.named("jar").get().outputs.files.singleFile)
    })
    from({
        zipTree(project(":sidecar-runtime").tasks.named("jar").get().outputs.files.singleFile)
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val verifyDistribution by tasks.registering {
    group = "verification"
    description = "Checks platform binaries, manifests and notices in the final NeoForge jar."
    dependsOn(tasks.named("jar"))
    doLast {
        val artifact = tasks.named<org.gradle.jvm.tasks.Jar>("jar").get().archiveFile.get().asFile
        ZipFile(artifact).use { zip ->
            val platforms = listOf("windows-amd64", "linux-amd64", "linux-arm64", "darwin-amd64", "darwin-arm64")
            for (platform in platforms) {
                val root = "io/hyperconduit/sidecar/bin/$platform/"
                val file = "hyperconduit-tunnel" + if (platform.startsWith("windows")) ".exe" else ""
                val manifestEntry = zip.getEntry(root + "manifest.json") ?: error("Missing manifest: $platform")
                val manifest = zip.getInputStream(manifestEntry).use {
                    groovy.json.JsonSlurper().parse(it) as Map<*, *>
                }
                check(manifest.keys == setOf("protocolVersion", "version", "file", "sha256")) { "Unexpected manifest fields: $platform" }
                check(manifest["protocolVersion"] == 1 && manifest["version"] == "hy2core-dev" && manifest["file"] == file) {
                    "Invalid packaged manifest: $platform"
                }
                val entry = zip.getEntry(root + file) ?: error("Missing binary: $platform")
                check(entry.size in 1..(64L * 1024 * 1024)) { "Invalid binary size: $platform" }
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                check(manifest["sha256"] == sha) { "Packaged binary hash mismatch: $platform" }
            }
            val bundled = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("io/hyperconduit/sidecar/bin/") && !it.endsWith("/") }.toSet()
            check(bundled.size == platforms.size * 2) { "Unexpected or stale sidecar bundle entries" }
            for (notice in listOf("GO-LICENSE", "HY2-LICENSE.md", "THIRD_PARTY_NOTICES.md")) {
                check(zip.getEntry("META-INF/hyperconduit-sidecar/$notice") != null) { "Missing bundled notice: $notice" }
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyDistribution) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}
