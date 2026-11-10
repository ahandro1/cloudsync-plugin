// Standalone JVM build for the pure-Kotlin logic in CloudSync/src/main/kotlin/com/cloudsync/core.
// Run from the project root with:  ./gradlew -p core-test test
// Kotlin version matches the template root build (kotlin-gradle-plugin 2.1.0).

plugins {
    kotlin("jvm") version "2.1.0"
}

kotlin {
    jvmToolchain(17)
}

// Compile only the shared pure-Kotlin core package, not the Android module.
kotlin.sourceSets["main"].kotlin.setSrcDirs(listOf("../CloudSync/src/main/kotlin/com/cloudsync/core"))

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")

    testImplementation(kotlin("test"))
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
