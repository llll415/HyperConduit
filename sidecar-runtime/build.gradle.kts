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

val hostOs = when {
    System.getProperty("os.name").lowercase().contains("win") -> "windows"
    System.getProperty("os.name").lowercase().contains("mac") -> "darwin"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    else -> error("Unsupported sidecar build OS")
}
val hostArch = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "amd64"
    "arm64", "aarch64" -> "arm64"
    else -> error("Unsupported sidecar build architecture")
}
val platformId = "$hostOs-$hostArch"
val program = "hyperconduit-tunnel" + if (hostOs == "windows") ".exe" else ""
val generated = layout.buildDirectory.dir("generated/sidecar-resources")
val binaryPath = generated.map { it.file("io/hyperconduit/sidecar/bin/$platformId/$program") }
val goCommand = providers.gradleProperty("sidecarGo").orElse("go")

val buildSidecar by tasks.registering(Exec::class) {
    workingDir(rootProject.file("sidecar"))
    inputs.files(fileTree(rootProject.file("sidecar")) {
        include("**/*.go", "**/go.mod", "**/go.sum")
        exclude("build/**", "config/**")
    })
    outputs.file(binaryPath)
    environment("GOWORK", "off")
    environment("CGO_ENABLED", "0")
    environment("GOOS", hostOs)
    environment("GOARCH", hostArch)
    doFirst { binaryPath.get().asFile.parentFile.mkdirs() }
    commandLine(goCommand.get(), "build", "-trimpath", "-o", binaryPath.get().asFile.absolutePath, "./cmd/hyperconduit-tunnel")
}

val sidecarManifest by tasks.registering {
    dependsOn(buildSidecar)
    inputs.file(binaryPath)
    inputs.property("manifestVersion", "hy2core-dev")
    inputs.property("protocolVersion", 1)
    val manifest = generated.map { it.file("io/hyperconduit/sidecar/bin/$platformId/manifest.json") }
    outputs.file(manifest)
    doLast {
        val hash = MessageDigest.getInstance("SHA-256").digest(binaryPath.get().asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        manifest.get().asFile.writeText("""{"protocolVersion":1,"version":"hy2core-dev","sha256":"$hash","file":"$program"}""" + "\n")
    }
}

sourceSets.main { resources.srcDir(generated) }
tasks.processResources {
    dependsOn(sidecarManifest)
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

tasks.test {
    systemProperty("sidecar.projectRoot", rootProject.projectDir.absolutePath)
}
