import java.security.MessageDigest

plugins {
    `java-library`
}

dependencies {
    compileOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("com.google.code.gson:gson:2.10.1")
    compileOnly("io.netty:netty-transport:4.1.97.Final")
    compileOnly("io.netty:netty-codec:4.1.97.Final")
    testImplementation("io.netty:netty-transport:4.1.97.Final")
    testImplementation("io.netty:netty-codec:4.1.97.Final")
}

val sidecarRoot = rootProject.file("sidecar")
val goCommand = providers.gradleProperty("sidecarGo").orElse("go")
val processedResources = layout.buildDirectory.dir("resources/main")
val sidecarManifestVersion = "hy2core-dev"
val sidecarProtocolVersion = 1

// Keep the bundle order stable: Windows first, then Linux, then macOS.
val sidecarPlatformIds = listOf(
    "windows-amd64",
    "linux-amd64",
    "linux-arm64",
    "darwin-amd64",
    "darwin-arm64",
)

fun sidecarProgram(platformId: String): String =
    if (platformId.startsWith("windows-")) "hyperconduit-tunnel.exe" else "hyperconduit-tunnel"

fun sidecarTaskSuffix(platformId: String): String =
    platformId.split("-").joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }

// Same source + same Go toolchain must produce the same binary. Track the toolchain identity as a
// task input so a Go upgrade invalidates the cached platform builds. GOTOOLCHAIN is intentionally
// not forced; this resolved `go version` is the identity that actually builds the binaries.
val goVersion = providers.exec {
    commandLine(goCommand.get(), "version")
}.standardOutput.asText.map { it.trim() }

val sidecarBuildTasks = linkedMapOf<String, TaskProvider<Exec>>()
val sidecarManifestTasks = linkedMapOf<String, TaskProvider<Task>>()
val sidecarBinaryFiles = linkedMapOf<String, Provider<RegularFile>>()
val sidecarManifestFiles = linkedMapOf<String, Provider<RegularFile>>()

var previousBuildTask: TaskProvider<Exec>? = null
sidecarPlatformIds.forEach { platformId ->
    val (os, arch) = platformId.split("-", limit = 2)
    val program = sidecarProgram(platformId)
    val suffix = sidecarTaskSuffix(platformId)
    val binary = layout.buildDirectory.file(
        "generated/sidecar-resources/io/hyperconduit/sidecar/bin/$platformId/$program",
    )

    val buildTask = tasks.register<Exec>("buildSidecar$suffix") {
        group = "sidecar"
        description = "Builds the $platformId sidecar binary with CGO disabled."
        workingDir(sidecarRoot)
        inputs.files(fileTree(sidecarRoot) {
            include("**/*.go", "**/go.mod", "**/go.sum")
            exclude("build/**", "config/**")
        })
        inputs.property("platform", platformId)
        inputs.property("goVersion", goVersion)
        inputs.property("goBuildFlags", "-buildvcs=false -trimpath -ldflags=-buildid=")
        outputs.file(binary)
        environment("GOWORK", "off")
        environment("CGO_ENABLED", "0")
        environment("GOOS", os)
        environment("GOARCH", arch)
        // Do not let ambient GOFLAGS/GOEXPERIMENT inject debug/race/coverage flags or experimental
        // runtime changes into the release binary.
        environment("GOFLAGS", "")
        environment("GOEXPERIMENT", "")
        if (arch == "amd64") {
            environment("GOAMD64", "v1")
        } else if (arch == "arm64") {
            environment("GOARM64", "v8.0")
        }
        doFirst { binary.get().asFile.parentFile.mkdirs() }
        commandLine(
            goCommand.get(),
            "build",
            "-buildvcs=false",
            "-trimpath",
            "-ldflags=-buildid=",
            "-o", binary.get().asFile.absolutePath,
            "./cmd/hyperconduit-tunnel",
        )
    }
    // Build the matrix serially when all platforms are requested; independent single-platform
    // builds still work on their own (mustRunAfter does not add a dependency).
    previousBuildTask?.let { prior -> buildTask.configure { mustRunAfter(prior) } }
    previousBuildTask = buildTask

    val manifest = layout.buildDirectory.file(
        "generated/sidecar-resources/io/hyperconduit/sidecar/bin/$platformId/manifest.json",
    )
    val manifestTask = tasks.register("sidecarManifest$suffix") {
        group = "sidecar"
        description = "Writes the $platformId sidecar manifest."
        dependsOn(buildTask)
        inputs.file(binary)
        inputs.property("manifestVersion", sidecarManifestVersion)
        inputs.property("protocolVersion", sidecarProtocolVersion)
        outputs.file(manifest)
        doLast {
            val binaryFile = binary.get().asFile
            val manifestFile = manifest.get().asFile
            manifestFile.parentFile.mkdirs()

            val digest = MessageDigest.getInstance("SHA-256")
            binaryFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            manifestFile.writeText(
                """{"protocolVersion":$sidecarProtocolVersion,"version":"$sidecarManifestVersion","sha256":"$hash","file":"$program"}""" + "\n",
            )
        }
    }

    sidecarBuildTasks[platformId] = buildTask
    sidecarManifestTasks[platformId] = manifestTask
    sidecarBinaryFiles[platformId] = binary
    sidecarManifestFiles[platformId] = manifest
}

val buildSidecar by tasks.registering {
    group = "sidecar"
    description = "Builds the sidecar binary for all supported platforms."
    dependsOn(sidecarBuildTasks.values)
}

val sidecarManifest by tasks.registering {
    group = "sidecar"
    description = "Writes the sidecar manifest for all supported platforms."
    dependsOn(sidecarManifestTasks.values)
}

tasks.processResources {
    dependsOn(sidecarManifest)
    // Only the exact five platform outputs below are copied. Delete exactly
    // build/resources/main/io/hyperconduit/sidecar/bin first so a stale host-only platform
    // directory cannot leak into the runtime jar; no other processResources output is touched.
    doFirst { project.delete(processedResources.get().dir("io/hyperconduit/sidecar/bin")) }
    sidecarPlatformIds.forEach { platformId ->
        from(sidecarBinaryFiles.getValue(platformId)) {
            into("io/hyperconduit/sidecar/bin/$platformId")
        }
        from(sidecarManifestFiles.getValue(platformId)) {
            into("io/hyperconduit/sidecar/bin/$platformId")
        }
    }
    from(rootProject.file("sidecar/THIRD_PARTY_NOTICES.md")) { into("META-INF/hyperconduit-sidecar") }
    from(rootProject.file("sidecar/licenses")) { into("META-INF/hyperconduit-sidecar/licenses") }
    from(rootProject.file("sidecar/hy2-core/LICENSE.md")) {
        into("META-INF/hyperconduit-sidecar")
        rename { "HY2-LICENSE.md" }
    }
    // The Go runtime/standard library are linked into the executable.
    from(providers.provider {
        val output = providers.exec {
            commandLine(goCommand.get(), "env", "GOROOT")
        }.standardOutput.asText.get().trim()
        file("$output/LICENSE")
    }) {
        into("META-INF/hyperconduit-sidecar")
        rename { "GO-LICENSE" }
    }
}

val verifySidecarBundle by tasks.registering {
    group = "verification"
    description = "Verifies all five bundled sidecar binaries and manifests."
    dependsOn(tasks.processResources)
    // Native PE/ELF/Mach-O header and machine-type checks live in BundledPlatformsTest; this task
    // performs the cheap packaging checks: exact platform/file sets, strict manifest schema,
    // presence, size and SHA-256.
    doLast {
        val binRoot = processedResources.get().dir("io/hyperconduit/sidecar/bin").asFile
        val expectedPlatforms = sidecarPlatformIds.toSet()
        val actualPlatforms = binRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { it.name }
            .toSet()
        check(actualPlatforms == expectedPlatforms) {
            "Bundled sidecar platforms are $actualPlatforms, expected exactly $expectedPlatforms"
        }

        sidecarPlatformIds.forEach { platformId ->
            val program = sidecarProgram(platformId)
            val platformDir = binRoot.resolve(platformId)
            val expectedFiles = setOf(program, "manifest.json")
            val actualFiles = platformDir.listFiles().orEmpty().map { it.name }.toSet()
            check(actualFiles == expectedFiles) {
                "Bundled files for $platformId are $actualFiles, expected exactly $expectedFiles"
            }

            val binary = platformDir.resolve(program)
            val manifest = platformDir.resolve("manifest.json")
            check(binary.isFile) { "Missing bundled sidecar binary: $binary" }
            check(manifest.isFile) { "Missing bundled sidecar manifest: $manifest" }
            val size = binary.length()
            check(size > 0 && size < 64L * 1024 * 1024) {
                "Bundled sidecar binary has invalid size: $binary ($size bytes)"
            }

            // Parse strictly: exactly the four documented fields, with no extra keys.
            val manifestJson = groovy.json.JsonSlurper().parse(manifest) as Map<*, *>
            check(manifestJson.keys == setOf("protocolVersion", "version", "sha256", "file")) {
                "Unexpected manifest schema in $manifest: ${manifestJson.keys}"
            }
            val protocolVersion = (manifestJson["protocolVersion"] as? Number)?.toInt()
            check(protocolVersion == sidecarProtocolVersion) {
                "Invalid protocolVersion in $manifest: $protocolVersion"
            }
            val version = manifestJson["version"] as? String
            check(version == sidecarManifestVersion) {
                "Invalid version in $manifest: $version"
            }
            val file = manifestJson["file"] as? String
            check(file == program) { "Manifest $manifest names '$file', expected '$program'" }
            val sha256 = manifestJson["sha256"] as? String
            check(sha256 != null && sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Manifest $manifest must contain a 64-character lowercase sha256"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            binary.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actualSha256 == sha256) {
                "SHA-256 mismatch for $binary: manifest=$sha256 actual=$actualSha256"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifySidecarBundle)
}

tasks.named<Jar>("jar") {
    // Only this module's jar: the NeoForge jar has its own packaging rules.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    mustRunAfter(verifySidecarBundle)
    systemProperty("sidecar.projectRoot", rootProject.projectDir.absolutePath)
}
