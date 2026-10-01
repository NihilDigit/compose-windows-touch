import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "dev.nihildigit"
version = "0.1.0"

// Compose Multiplatform release this was written and tested against. The library never pulls it in for
// consumers: every user already has Compose Desktop, and the bridge reaches into its internals, so the
// version that matters is the one the application runs on.
val composeVersion = "1.12.0"

kotlin {
    // FFM (java.lang.foreign) is final from JDK 22. Compiling on 25 with the release flag keeps the API
    // surface at 22, the oldest runtime that can load this.
    jvmToolchain(25)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
            freeCompilerArgs.add("-Xjdk-release=22")
        }
    }

    sourceSets {
        jvmMain.dependencies {
            compileOnly("org.jetbrains.compose.runtime:runtime:$composeVersion")
            compileOnly("org.jetbrains.compose.ui:ui:$composeVersion")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.ui:ui:$composeVersion")
        }
    }
}

mavenPublishing {
    // Central Portal: the vanniktech plugin handles staging upload, signing and, with automaticRelease,
    // the promotion from staging to released.
    publishToMavenCentral(automaticRelease = true)
    // Signing is a Central requirement, not a local one; CI supplies signingInMemoryKey, so a machine
    // without a key can still run publishToMavenLocal.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("compose-windows-touch")
        description.set(
            "Real multi-touch and pen input for Compose Desktop on Windows: WM_POINTER messages " +
                "reach Compose as Touch, Stylus and Eraser pointers instead of a single mouse pointer.",
        )
        url.set("https://github.com/NihilDigit/compose-windows-touch")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("nihildigit")
                name.set("nihildigit")
                url.set("https://github.com/NihilDigit")
            }
        }
        scm {
            url.set("https://github.com/NihilDigit/compose-windows-touch")
            connection.set("scm:git:git://github.com/NihilDigit/compose-windows-touch.git")
            developerConnection.set("scm:git:ssh://git@github.com/NihilDigit/compose-windows-touch.git")
        }
        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/NihilDigit/compose-windows-touch/issues")
        }
    }
}

// Maven Central counts every uploaded file against a quota, and Gradle writes four checksums for each
// file, the .asc signatures included. Central needs the MD5 and SHA-1 of an artifact and nothing for a
// signature, so the rest is removed from the staging directory the plugin zips at the end of the build.
tasks.withType<PublishToMavenRepository>().configureEach {
    if (!name.endsWith("ToMavenCentralRepository")) return@configureEach
    doLast {
        if (repository.url.scheme != "file") return@doLast
        val staging = File(repository.url)
        if (!staging.isDirectory) return@doLast
        staging.walkTopDown()
            .filter { file -> file.isFile && isChecksumCentralDoesNotNeed(file.name) }
            .forEach { it.delete() }
    }
}

fun isChecksumCentralDoesNotNeed(name: String): Boolean =
    name.endsWith(".sha256") || name.endsWith(".sha512") || name.endsWith(".asc.md5") || name.endsWith(".asc.sha1")
