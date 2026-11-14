plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cz.k4.salamanderdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.k4.salamanderdroid"
        minSdk = 30
        targetSdk = 35
        // CI numbers the builds, so a newer APK always installs over an older one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.2.1"
    }

    // Release signing key from the environment (CI secrets); without it the debug key is used.
    val keystorePath = System.getenv("SIGNING_KEYSTORE")?.takeIf { it.isNotEmpty() && file(it).isFile }
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "salamander"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystorePath != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module",
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric fetches the Android runtime itself; use the same mirror as the build.
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            it.maxHeapSize = "2g"
            // File names with diacritics need a UTF-8 locale in the test JVM (Android always has one).
            it.environment("LC_ALL", "C.UTF-8")
            // The UI tests and the embedded SSH server do not share a JVM well.
            it.forkEvery = 1
        }
    }
    lint {
        // Dependency update hints are noise for the build; real problems still fail it.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.luben:zstd-jni:1.5.7-3@aar")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.hierynomus:smbj:0.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("com.github.luben:zstd-jni:1.5.7-3")
    testImplementation("org.apache.sshd:sshd-sftp:2.15.0")
    testImplementation("org.slf4j:slf4j-nop:2.0.17")
}
