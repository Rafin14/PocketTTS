import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = providers.environmentVariable("POCKETTTS_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("POCKETTTS_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("POCKETTTS_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("POCKETTTS_KEY_PASSWORD").orNull
val requiredNdkVersion = "27.2.12479018"
// Build inputs stay in the supplied ZIP; only runtime assets and their notices enter the APK.
val bundledPocketAssets = tasks.register<Sync>("bundledPocketAssets") {
    from(zipTree(rootProject.file("PocketTTS-english-FP32.zip"))) {
        include("manifest.json", "MODEL_LICENSE.txt", "VOICE_ATTRIBUTION.md", "models/*.onnx", "models/tokenizer.model", "voices/alba.wav")
        into("pockettts")
    }
    into(layout.buildDirectory.dir("generated/pocketAssets"))
    doLast {
        val root = destinationDir.resolve("pockettts")
        val entries = root.walkTopDown().filter { it.isFile && it.name != "files.tsv" }.sortedBy { it.relativeTo(root).path }.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            "${file.relativeTo(root).invariantSeparatorsPath}\t${file.length()}\t${digest.digest().joinToString("") { "%02x".format(it) }}"
        }.toList()
        root.resolve("files.tsv").writeText(entries.joinToString("\n"))
    }
}
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "org.pockettts.android.engine"
    compileSdk = 35
    // A CI/WSL build may provide a complete NDK outside the Android SDK.
    // Android Studio falls back to the version managed by the SDK.
    ndkVersion = requiredNdkVersion
    providers.environmentVariable("ANDROID_NDK_HOME").orNull?.let { candidate ->
        val sourceProperties = file("$candidate/source.properties")
        val matchesRequiredVersion = sourceProperties.isFile && sourceProperties
            .readLines()
            .any { it.trim() == "Pkg.Revision = $requiredNdkVersion" }
        if (matchesRequiredVersion) ndkPath = candidate
    }

    defaultConfig {
        applicationId = "org.pockettts.android.engine"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.5.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++17", "-O3") }
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { buildConfig = true; compose = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/pocketAssets"))
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(bundledPocketAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
