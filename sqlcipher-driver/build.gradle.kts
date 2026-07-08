import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

// Maven Central namespace (io.github.<user> is auto-verified via the GitHub repo).
// The Kotlin package stays `com.yet.sqlcipher`; group and package need not match.
group = "io.github.yet300"
version = "0.1.0"

kotlin {
    androidLibrary {
        namespace = "com.yet.sqlcipher"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    jvmToolchain(21)

    listOf(iosArm64(), iosSimulatorArm64(), macosArm64(), macosX64()).forEach { target ->
        val slice = when (target.konanTarget) {
            KonanTarget.IOS_ARM64 -> "ios-arm64"
            KonanTarget.IOS_SIMULATOR_ARM64 -> "ios-simulator-arm64"
            KonanTarget.MACOS_ARM64 -> "macos-arm64"
            else -> "macos-x64"
        }
        // The cinterop klib carries the static SQLCipher archive (built by
        // native/build-sqlcipher.sh), so consumers get the cipher linked in transitively —
        // no SPM package, no vendored xcframework, no linker options on their side.
        // Consumers must keep SQLDelight's `linkSqlite = false` so the system SQLite is
        // never a competing sqlite3_* exporter in their final link.
        target.compilations.getByName("main").cinterops.create("sqlcipher") {
            defFile(project.file("src/nativeInterop/cinterop/sqlcipher.def"))
            extraOpts("-libraryPath", project.file("native/libs/$slice").absolutePath)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // SqlDriver/SqlSchema appear in the public API.
            api(libs.sqldelight.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }
        appleMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}


mavenPublishing {
    // New Central Portal (central.sonatype.com tokens) — the default (and only) host since
    // vanniktech 0.35; legacy OSSRH staging is gone.
    publishToMavenCentral()
    // Sign only when a key is available (CI / release). Keeps publishToMavenLocal
    // and consumer integration via mavenLocal working without GPG configured.
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent ||
        providers.gradleProperty("signing.gnupg.keyName").isPresent
    ) {
        signAllPublications()
    }
    coordinates(group.toString(), "sqlcipher-driver", version.toString())

    pom {
        name = "SqlCipherDriver"
        description = "SQLCipher-encrypted SQLDelight drivers for Kotlin Multiplatform (Android + iOS), with the iOS cipher binary bundled in the klib."
        inceptionYear = "2026"
        url = "https://github.com/yet300/SQLCipher"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "yet300"
                name = "yet300"
                url = "https://github.com/yet300"
            }
        }
        scm {
            url = "https://github.com/yet300/SQLCipher"
            connection = "scm:git:git://github.com/yet300/SQLCipher.git"
            developerConnection = "scm:git:ssh://git@github.com/yet300/SQLCipher.git"
        }
    }
}
