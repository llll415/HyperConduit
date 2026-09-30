// HyperConduit core: pure-Java userspace reliable transport + Brutal congestion control.
// Deliberately has ZERO compile/runtime dependencies (JDK only) so it can be shaded into a
// mod jar without colliding with Minecraft's own Netty, and unit-tested without a mod loader.
plugins {
    `java-library`
    application
}

application {
    mainClass = providers.gradleProperty("mainClass").orElse("io.hyperconduit.demo.Endpoint")
}

// Netty is marked compileOnly: Minecraft provides it at runtime (1.21.1 ships 4.1.97.Final),
// so we compile against the same version but never package it. The Netty bridge lives in
// io.hyperconduit.netty.
dependencies {
    compileOnly("io.netty:netty-common:4.1.97.Final")
    compileOnly("io.netty:netty-buffer:4.1.97.Final")
    compileOnly("io.netty:netty-transport:4.1.97.Final")
    compileOnly("io.netty:netty-codec:4.1.97.Final")
    compileOnly("io.netty:netty-handler:4.1.97.Final")
    compileOnly("io.netty:netty-resolver:4.1.97.Final")

    testImplementation("io.netty:netty-common:4.1.97.Final")
    testImplementation("io.netty:netty-buffer:4.1.97.Final")
    testImplementation("io.netty:netty-transport:4.1.97.Final")
    testImplementation("io.netty:netty-codec:4.1.97.Final")
    testImplementation("io.netty:netty-handler:4.1.97.Final")
    testImplementation("io.netty:netty-resolver:4.1.97.Final")
}

// core has zero runtime dependencies (JDK only), so this plain jar is fully self-contained:
// `java -jar hyperconduit-core.jar server --bind=... --psk=...` works with no shading.
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "io.hyperconduit.demo.Endpoint",
            "Implementation-Title" to "HyperConduit core",
            "Implementation-Version" to project.version,
        )
    }
    archiveBaseName.set("hyperconduit-core")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
