rootProject.name = "compose-windows-touch"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// jvmToolchain(25) is resolved by this build's own settings even when another project includes it as a
// composite build, so a machine or CI runner with some other JDK downloads 25 instead of failing.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
