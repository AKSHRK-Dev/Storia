plugins {
    java
}

group = "dev.stolia"
version = providers.gradleProperty("relayVersion").getOrElse("1.0.0")

sourceSets {
    main {
        java {
            srcDir("src/main/java")
            // The encrypted offload protocol is shared with the Stolia server (no Minecraft classes in it).
            srcDir("../folia-server/src/main/java")
            include("dev/stolia/relay/**", "dev/stolia/offload/protocol/**")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveFileName.set("stolia-relay-${project.version}.jar")
    manifest {
        attributes("Main-Class" to "dev.stolia.relay.StoliaRelay", "Implementation-Version" to project.version)
    }
}
