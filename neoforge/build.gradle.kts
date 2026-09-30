// NeoForge entrypoint module: a thin shell around mod-common.
// The jar bundles mod-common (Mixin + config + bridge) and core (transport stack) so that
// NeoForge's mod list sees a single self-contained artifact.
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

dependencies {
    implementation(project(":mod-common"))
    implementation(project(":core"))

    compileOnly("org.spongepowered:mixin:0.8.7")

    testImplementation(project(":core"))
}

neoForge {
    version = "21.1.252"

    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }
}

// Bundle mod-common and core into the NeoForge jar by unpacking their contents, so the mod is a
// single self-contained artifact (NeoForge's mods folder would otherwise see library jars that
// carry no mod metadata). Uses withType rather than a concrete task class because ModDevGradle may
// substitute its own Jar subtype.
tasks.withType<org.gradle.jvm.tasks.Jar>().matching { it.name == "jar" }.configureEach {
    archiveBaseName.set("hyperconduit-neoforge")

    dependsOn(project(":mod-common").tasks.named("jar"))
    dependsOn(project(":core").tasks.named("jar"))

    from({
        zipTree(project(":mod-common").tasks.named("jar").get().outputs.files.singleFile)
    })
    from({
        zipTree(project(":core").tasks.named("jar").get().outputs.files.singleFile)
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}
