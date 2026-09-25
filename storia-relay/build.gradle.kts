plugins {
    java
}

group = "dev.storia"
version = providers.gradleProperty("relayVersion").getOrElse("1.0.0")

sourceSets {
    main {
        java {
            srcDir("src/main/java")
            // The encrypted offload protocol is shared with the Storia server (no Minecraft classes in it).
            srcDir("../folia-server/src/main/java")
            include("dev/storia/relay/**", "dev/storia/offload/protocol/**")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveFileName.set("storia-relay-${project.version}.jar")
    manifest {
        attributes("Main-Class" to "dev.storia.relay.StoriaRelay", "Implementation-Version" to project.version)
    }
}
