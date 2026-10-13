plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties

val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signingValue(key: String, envKey: String): String? {
    val fromProperties = keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv(envKey)?.takeIf { it.isNotBlank() }
    return fromProperties ?: fromEnv
}

val releaseStoreFilePath = signingValue("storeFile", "CLEARPDF_UPLOAD_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "CLEARPDF_UPLOAD_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "CLEARPDF_UPLOAD_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "CLEARPDF_UPLOAD_KEY_PASSWORD")

// AI summary prototype: the Gemini key lives in the untracked local.properties, never in git.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY").orEmpty()
val geminiModel = localProperties.getProperty("GEMINI_MODEL")?.takeIf { it.isNotBlank() } ?: "gemini-3.5-flash-lite"

val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.chethan616.clearpdf"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.chethan616.clearpdf"
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "2.0.0"
        // Keep every locale declared by the app. Filtering this to English
        // removes values-pt-rBR from the packaged APK, so the language picker
        // can appear to work while the app continues to resolve English.
        // AI summary (both flavors). Left blank when local.properties has no key, which hides it.
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GEMINI_MODEL", "\"$geminiModel\"")
    }

    signingConfigs {
        getByName("debug") {
            // Default debug keystore
        }
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
                logger.warn("Release signing key is not configured. Falling back to debug signing for testing release build.")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo.include = false
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        // Plain JVM unit tests (the xlsx model/writer). Android stubs return defaults instead of
        // throwing so incidental framework calls don't fail a pure-logic test.
        unitTests.isReturnDefaultValues = true
    }

    // Two distributions of the same app (identical applicationId):
    //  - play: Google Play. The optional Office engine ships as the on-demand dynamic feature
    //    module :office_engine (Play Feature Delivery); no native code is ever downloaded by the app.
    //  - foss: GitHub/F-Droid-style sideload builds. INTERNET is used for the optional Office
    //    engine download and the user-entered URL-to-PDF flow; see src/foss/AndroidManifest.xml.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
        }
    }
    // AGP can't link a dynamic feature against ABI-split APK outputs, and the feature is only
    // ever delivered through a Play bundle anyway — so register it for bundle builds only.
    // APK builds (sideload/foss) simply don't contain it; the play installer then falls back.
    if (gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }) {
        dynamicFeatures += setOf(":office_engine")
    }
    // Bundling on-device OCR (bundled ML Kit + Tesseract4Android) added native .so libs for
    // 4 CPU architectures; without splitting, every install carries all 4. This produces one
    // APK per ABI (~1/4 the native-lib weight each) plus a universal fallback for sideloading.
    // Play Store distribution via an Android App Bundle (`./gradlew bundleRelease`) already does
    // this automatically and needs no config here — this `splits` block only matters for raw
    // APK builds/installs (`assembleDebug`/`assembleRelease`, `installDebug`, sideloading).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt",
                // PdfBox-Android bundles these and they collide with other deps.
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md"
            )
        }
        jniLibs {
            // GPUImage's YUV/read-back helper is built for 4 KB pages only, which fails Play's 16 KB
            // page-size requirement. The app never uses its camera-YUV path, and the one call that
            // needed it (filtered read-back) is done in Kotlin by GlFilterRenderer instead.
            excludes += "**/libyuv-decoder.so"
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xlambdas=class"
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Dependency injection (see core/di).
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    implementation(libs.androidx.core.ktx)
    // View-based UI: XML layouts + Fragments + Navigation + Material Components.
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.coil)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":pdf-core"))
    implementation(project(":ocr-core"))
    // Apache POI provides legacy .doc/.xls/.ppt text extraction. It is Apache-2.0
    // licensed; see THIRD_PARTY_NOTICES.md for redistribution requirements.
    implementation("org.apache.poi:poi:3.17")
    implementation("org.apache.poi:poi-scratchpad:3.17")
    // Korean Hangul word-processor files: text extraction for .hwp (5.x) and .hwpx.
    // Apache-2.0, pure Java, no transitive deps; see THIRD_PARTY_NOTICES.md.
    implementation(libs.hwplib)
    implementation(libs.hwpxlib)
    
    // PdfBox-Android — needed by PdfViewerViewModel for native PDF overlay export
    implementation(libs.pdfbox.android)

    // ML Kit Document Scanner & Camera
    implementation(libs.play.services.mlkit.scanner)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Image editor: GPUImage (upstream 2.1.0, ~0.2 MB; the T8RIN 8.x fork needs compileSdk 37
    // + desugaring and drags in androidx.core 1.19) for GL adjustments/filters, EXIF read/strip,
    // and ML Kit subject segmentation for auto background removal (API 24+, gated at runtime;
    // isolated behind BackgroundRemover so a FOSS flavor can swap it out).
    implementation(libs.gpuimage)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mlkit.subject.segmentation)
    // A real XmlPullParser for JVM tests; android.jar only carries stubs of it.
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    // Optional Office engine (powered by LibreOffice) — installer differs per distribution.
    "playImplementation"(libs.play.feature.delivery.ktx)
    "fossImplementation"(libs.androidx.work.runtime.ktx)
    "fossImplementation"(libs.xz)
}
