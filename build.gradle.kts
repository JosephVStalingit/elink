import gg.meza.stonecraft.mod

plugins {
    id("gg.meza.stonecraft")
}

modSettings {
    clientOptions {
        fov = 90
        guiScale = 3
        narrator = false
        darkBackground = true
        musicVolume = 0.0
    }

    variableReplacements =
        mapOf(
            // The active Stonecutter version, without a snapshot alias.
            "minecraftVersionVirtual" to stonecutter.current.version,
            // Forge wants the loader version on its own: 1.20.1-47.4.23 becomes 47.
            "forgeLoaderVersion" to
                if (project.mod.isForge) {
                    project.mod.prop("forge_version").substringAfter("-").substringBefore(".")
                } else {
                    ""
                },
        )
}

// WebRTC and MQTT signalling libraries.
//
// `modImplementation` puts them on the compile and runtime classpath, `include` nests them into the
// mod jar so players do not have to download anything else. Loom synthesises a `fabric.mod.json`
// for jars that do not have one, which is what makes Fabric Loader pick the nested jars up (a jar
// without that metadata is ignored). The libraries must not be relocated: webrtc-java's NativeLoader
// looks its native library up by hard-coded resource name.
//
// The versions are read from gradle.properties because they are independent of the Minecraft
// version; only the natives classifier has to be named explicitly (see docs/dependency-bundling.md).
//
// The root project is the Stonecutter controller and has no Minecraft plugins applied, so the
// dependencies are declared for the per-version subprojects only.
if (project != rootProject) {
    val webrtcVersion = providers.gradleProperty("webrtc.java.version").get()
    val nativesClassifier = providers.gradleProperty("webrtc.natives.classifier").get()
    val pahoVersion = providers.gradleProperty("paho.version").get()

    // NeoForge for 1.20.1 is published under the net.neoforged:forge coordinates, which Loom's NeoForge
    // provider does not accept, so 1.20.1 does not have a NeoForge node at all (see settings.gradle.kts).

    dependencies {
        // Gradle's string notation is used on purpose: the Kotlin DSL extensions Loom adds for these
        // configurations are not part of the script's implicit imports.
        add("modImplementation", "dev.onvoid.webrtc:webrtc-java:$webrtcVersion")
        add("include", "dev.onvoid.webrtc:webrtc-java:$webrtcVersion")

        // Native build of the WebRTC library; a single shared library at the root of the jar.
        // It needs both configurations: `include` nests it into the mod jar so players get it, and
        // `runtimeOnly` puts it on the development runtime classpath, because Loom only nests
        // included jars while packaging and not while running the game from the IDE.
        add("include", "dev.onvoid.webrtc:webrtc-java:$webrtcVersion:$nativesClassifier")
        add("runtimeOnly", "dev.onvoid.webrtc:webrtc-java:$webrtcVersion:$nativesClassifier")

        // MQTT signalling client used to exchange SDP offers/answers and ICE candidates.
        add("modImplementation", "org.eclipse.paho:org.eclipse.paho.client.mqttv3:$pahoVersion")
        add("include", "org.eclipse.paho:org.eclipse.paho.client.mqttv3:$pahoVersion")
    }
}
