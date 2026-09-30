// mod-common: shared Mixin layer for both Fabric and NeoForge, using Mojang official mappings.
// Built with NeoForge's ModDevGradle because that is the only supported way to resolve a
// Minecraft 1.21.1 dependency with official (Mojang) mappings; a plain Maven build cannot
// resolve `net.minecraft:client:1.21.1`. The Fabric side consumes this same jar and adds only
// a thin loader-specific entrypoint around it.
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

dependencies {
    api(project(":core"))

    // Mixin is provided by the loader at runtime (both Fabric Loader and NeoForge bundle it),
    // so it is compileOnly here and never packaged.
    compileOnly("org.spongepowered:mixin:0.8.7")

    // Gson for the config file; MC bundles it at runtime. Version must match what MC 1.21.1
    // strictly requires (2.10.1), otherwise the dependency resolution fails on the strict constraint.
    compileOnly("com.google.code.gson:gson:2.10.1")

    testImplementation(project(":core"))
    testImplementation("com.google.code.gson:gson:2.10.1")
}

neoForge {
    version = "21.1.252"

    // Parchment gives us readable parameter names on top of Mojang's official mappings.
    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}
