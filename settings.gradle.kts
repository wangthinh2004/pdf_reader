pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (open-source offline OCR fallback) is published via JitPack.
        maven { url = uri("https://jitpack.io") }
        // Ad mediation networks pulled in by sdk-ads-boong.
        maven { url = uri("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea") }
        maven { url = uri("https://artifact.bytedance.com/repository/pangle") }
        maven {
            url = uri("https://maven.pkg.github.com/UniSoft-Android/SDK_ADS_BOONG")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
}

rootProject.name = "ClearPDF"
include(":pdf-core")
include(":ocr-core")
include(":app")
// The Play Feature Delivery module only makes sense inside an app bundle, and AGP can't build it
// next to the app's ABI-split APKs — so it joins the build only for bundle tasks.
if (gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }) {
    include(":office_engine")
}
