plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningEnvironment = listOf(
    "EMX_KEYSTORE_PATH",
    "EMX_KEYSTORE_PASSWORD",
    "EMX_KEY_ALIAS",
    "EMX_KEY_PASSWORD",
).associateWith { name ->
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
}
val hasCompleteReleaseSigning = releaseSigningEnvironment.values.all { it != null }
val missingReleaseSigningVariables = if (releaseSigningEnvironment.values.any { it != null }) {
    releaseSigningEnvironment.filterValues { it == null }.keys.toList()
} else {
    emptyList()
}

val validateReleaseSigningEnvironment = tasks.register("validateReleaseSigningEnvironment") {
    group = "verification"
    description = "Rejects partial release signing configuration; no configuration means unsigned."
    val missingVariables = missingReleaseSigningVariables
    doLast {
        if (missingVariables.isNotEmpty()) {
            throw GradleException(
                "Incomplete release signing configuration. Set the missing environment variables: " +
                    missingVariables.joinToString() +
                    ". To build an unsigned release, unset all four EMX signing variables: " +
                    "EMX_KEYSTORE_PATH, EMX_KEYSTORE_PASSWORD, EMX_KEY_ALIAS, EMX_KEY_PASSWORD.",
            )
        }
    }
}

// Validate only release builds, including aggregate assemble/build tasks, not debug CI.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigningEnvironment)
}

android {
    namespace = "io.emx"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    // NDK r28 emits 16 KB-aligned native libraries by default.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.emx"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                // Relative paths are resolved from the repository root.
                storeFile = rootProject.file(releaseSigningEnvironment.getValue("EMX_KEYSTORE_PATH")!!)
                storePassword = releaseSigningEnvironment.getValue("EMX_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("EMX_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("EMX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Never fall back to the debug key; missing configuration produces an unsigned APK.
            signingConfig = if (hasCompleteReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    packaging {
        jniLibs {
            // AGP 8.7 packages uncompressed native libraries with 16 KB ZIP alignment.
            useLegacyPackaging = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
