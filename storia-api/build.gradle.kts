plugins {
    java
}

group = "dev.storia"
version = providers.gradleProperty("apiVersion").getOrElse("dev")

// The plugin API lives with the server's own classes; this jar is only for plugins to compile against
// (compileOnly). At runtime the classes come from the Storia server.
sourceSets {
    main {
        java {
            srcDir("../folia-server/src/main/java")
            include("dev/storia/api/**")
        }
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveFileName.set("storia-api-${project.version}.jar")
}
tasks.named<Jar>("sourcesJar") {
    archiveFileName.set("storia-api-${project.version}-sources.jar")
}
tasks.named<Jar>("javadocJar") {
    archiveFileName.set("storia-api-${project.version}-javadoc.jar")
}
