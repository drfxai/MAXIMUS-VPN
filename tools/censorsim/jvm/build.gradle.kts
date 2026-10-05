// Compiles the app's import, config-builder and stealth code on a plain JVM (the Android build is not
// needed) and runs it against the simulated censor with a real Xray binary standing in for libXray.
plugins {
    kotlin("jvm") version "2.2.10"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("org.json:json:20240303")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
kotlin { jvmToolchain(21) }
sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin", "stubs", "../../../app/src/main/java")
        kotlin.include(
            "SimRunner.kt", "Phase4.kt", "android/**", "com/example/vpn/diagnostics/Stubs.kt", "com/example/vpn/tunnel/ProxyDnsTransportStub.kt",
            "com/example/data/model/**", "com/example/core/**", "com/example/vless/**", "com/example/mihomo/**",
            "com/example/xray/XrayConfigBuilder.kt", "com/example/xray/XrayConfigParser.kt",
            "com/example/xray/XrayLogManager.kt", "com/example/xray/RealDelayProbe.kt",
            "com/example/vpn/engine/ProtocolLinks.kt", "com/example/vpn/engine/RuntimeCapabilities.kt",
            "com/example/vpn/engine/EngineSelectionPolicy.kt", "com/example/vpn/engine/WireGuardConf.kt",
            "com/example/vpn/engine/UniversalImportEngine.kt", "com/example/vpn/engine/ConfigurationAdapter.kt",
            "com/example/vpn/stealth/**", "com/example/vpn/EndpointResolver.kt",
            "com/example/vpn/smart/ServerRace.kt", "com/example/vpn/smart/NetworkMemory.kt", "com/example/vpn/smart/WatchPolicy.kt",
            "com/example/vpn/share/**", "com/example/vpn/safety/**"
        )
    }
}
application { mainClass.set("SimRunnerKt") }
