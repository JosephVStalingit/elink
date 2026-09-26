pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev")
        maven("https://maven.minecraftforge.net")
        maven("https://maven.neoforged.net/releases/")
    }
}

plugins {
    id("gg.meza.stonecraft") version "1.14.2"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

stonecutter {
    centralScript = "build.gradle.kts"
    kotlinController = true
    shared {
        fun mc(
            version: String,
            vararg loaders: String,
        ) {
            // Naming the version directories "1.20.1-fabric", "1.21.1-fabric", and so on
            for (it in loaders) version("$version-$it", version)
        }

        // NeoForge for 1.20.1 is published as net.neoforged:forge (the fork predates the rename) and
        // Architectury Loom's NeoForge provider insists on net.neoforged:neoforge there, so 1.20.1
        // ships Fabric and Forge. Forge 47.4 is the same code line as NeoForge 47.1.
        mc("1.20.1", "fabric", "forge")
        mc("1.21.1", "fabric", "forge")
        // NeoForge 1.21.1 is wired up in the sources and metadata, but maven.neoforged.net is not
        // reachable from every network, and a node whose toolchain cannot be downloaded breaks the
        // configuration of all other nodes. Uncomment to enable it:
        // mc("1.21.1", "neoforge")

        vcsVersion = "1.20.1-fabric"
    }
    create(rootProject)
}

rootProject.name = "mcp2p"
