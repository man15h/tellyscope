// SPDX-License-Identifier: GPL-3.0-only

import com.android.build.api.artifact.SingleArtifact
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
        versionCode = 26
        versionName = "0.14.0"

        // An alpha tag (release.yml, vX.Y.Z-alpha.N on dev) passes -Ptellyscope.alpha=N:
        // a separate app ID, so it installs next to the release.
        providers.gradleProperty("tellyscope.alpha").orNull?.let { run ->
            applicationIdSuffix = ".alpha"
            versionNameSuffix = "-alpha.$run"
        }
    }

    // Only the languages Tellyscope is translated into: drops the ~80 other locales AndroidX and
    // Material ship (about 440 KB). A new values-xx/strings.xml adds its code here too.
    androidResources {
        localeFilters += listOf("en", "es", "zh-rCN", "ja", "ru", "hi")
        // Lists them for Android 13+'s per-app language setting (default locale: res/resources.properties).
        generateLocaleConfig = true
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

    // The helpers' pure parts (BannerColors) are unit-tested like app code.
    sourceSets["test"].java.srcDir("src/helper/java")

    testOptions {
        // Robolectric renders the screenshot tests (see ScreenshotTest).
        unitTests.isIncludeAndroidResources = true
    }
}

// The TV helpers (src/helper: Labels, and the remote's Keys): plain Java against android.jar, dexed on
// their own and shipped as one asset. They run on the TV through app_process, so they must not touch
// app code or libraries.
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

/** The repo's CHANGELOG.md, shipped as an asset so What's new matches the installed version, offline. */
abstract class BundleChangelog : DefaultTask() {
    @get:InputFile abstract val changelog: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun copy() {
        val out = outputDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        changelog.get().asFile.copyTo(out.resolve("CHANGELOG.md"))
    }
}

val bundleChangelog by tasks.registering(BundleChangelog::class) {
    changelog = rootProject.layout.projectDirectory.file("CHANGELOG.md")
    outputDir = layout.buildDirectory.dir("generated/changelog/assets")
}

/** Fails the build when a release APK grows past the 3.5 MB budget. CI's `build` and release.yml both assemble release. */
abstract class CheckApkSize : DefaultTask() {
    @get:InputFiles abstract val apkDir: DirectoryProperty
    @get:Input abstract val maxBytes: Property<Long>

    @TaskAction fun check() {
        val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.isNotEmpty()) { "no APK in ${apkDir.get().asFile}" }
        for (apk in apks) {
            check(apk.length() <= maxBytes.get()) {
                "${apk.name} is ${apk.length()} bytes, over the ${maxBytes.get()}-byte budget"
            }
            logger.lifecycle("${apk.name}: ${apk.length()} of ${maxBytes.get()} bytes")
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(dexLabelHelper, DexLabelHelper::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(bundleChangelog, BundleChangelog::outputDir)
    }
    onVariants(selector().withBuildType("release")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val sizeCheck = tasks.register<CheckApkSize>("check${name}ApkSize") {
            apkDir = variant.artifacts.get(SingleArtifact.APK)
            maxBytes = 3_500_000L
        }
        afterEvaluate { tasks.named("assemble$name") { finalizedBy(sizeCheck) } }
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
