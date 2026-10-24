// SPDX-License-Identifier: GPL-3.0-only

import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.tellyscope"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tellyscope"
        // 26 (Android 8.0): java.time and adaptive icons without desugaring, ~97% of
        // phones in use. Kadb needs 23; the TV side has its own floor (wireless debugging is 11+).
        minSdk = 26
        targetSdk = 37
        // A PR into main must raise both past the latest v* tag (.github/workflows/release-check.yml).
        versionCode = 9
        versionName = "0.6.1"

        // An alpha tag (release.yml, vX.Y.Z-alpha.N on dev) passes -Ptellyscope.alpha=N:
        // a separate app ID, so it installs next to the release.
        providers.gradleProperty("tellyscope.alpha").orNull?.let { run ->
            applicationIdSuffix = ".alpha"
            versionNameSuffix = "-alpha.$run"
        }
    }

    // Only CI signs, from repo secrets; without RELEASE_KEYSTORE the release APK is unsigned.
    val keystore = providers.environmentVariable("RELEASE_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                // keytool's PKCS12 default has one password for the store and the key.
                val password = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
                storePassword = password
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Bouncy Castle (via Kadb) ships the same license files in three jars.
            excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric renders the screenshot tests (see ScreenshotTest).
        unitTests.isIncludeAndroidResources = true
    }
}

// The label helper (src/helper): plain Java against android.jar, dexed on its own and shipped as
// an asset. It runs on the TV through app_process, so it must not touch app code or libraries.
val compileLabelHelper by tasks.registering(JavaCompile::class) {
    source = fileTree("src/helper/java")
    classpath = files(androidComponents.sdkComponents.bootClasspath)
    destinationDirectory = layout.buildDirectory.dir("intermediates/labelHelper/classes")
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

/** D8 ships inside AGP, so this needs no build-tools version of its own. */
abstract class DexLabelHelper : DefaultTask() {
    @get:InputFiles abstract val classes: ConfigurableFileCollection
    @get:InputFiles abstract val androidJar: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun dex() {
        val out = outputDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        D8.run(
            D8Command.builder()
                .addProgramFiles(classes.asFileTree.matching { include("**/*.class") }.files.map { it.toPath() })
                .addLibraryFiles(androidJar.files.map { it.toPath() })
                // Old TVs too: the fallback covers OEM blocks, not a dex the TV can't load.
                .setMinApiLevel(21)
                .setOutput(out.resolve("label-helper.jar").toPath(), OutputMode.DexIndexed)
                .build(),
        )
    }
}

val dexLabelHelper by tasks.registering(DexLabelHelper::class) {
    classes.from(compileLabelHelper)
    androidJar.from(androidComponents.sdkComponents.bootClasspath)
    outputDir = layout.buildDirectory.dir("generated/labelHelper/assets")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(dexLabelHelper, DexLabelHelper::outputDir)
    }
}

dependencies {
    implementation(project(":adb"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
