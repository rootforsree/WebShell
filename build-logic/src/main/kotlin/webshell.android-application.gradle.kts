import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Accessor codegen does not provide `libs` inside precompiled script
// plugins even with the catalog imported in build-logic settings (proven
// by two failed CI runs), so look the catalog up from the consuming
// project's extensions at apply time. Same `findLibrary` call shape.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    namespace = "com.rootforsree.webshell"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rootforsree.webshell"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // AGP 9 built-in Kotlin takes jvmTarget from targetCompatibility.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        debug {
            // v1 ships debug only; no signing, no minify.
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.findLibrary("desugar").get())
    implementation(platform(libs.findLibrary("compose-bom").get()))
    implementation(libs.findLibrary("androidx-compose-ui").get())
    implementation(libs.findLibrary("androidx-compose-ui-graphics").get())
    implementation(libs.findLibrary("androidx-compose-foundation").get())
    implementation(libs.findLibrary("androidx-compose-material3").get())
    implementation(libs.findLibrary("androidx-compose-material-icons-core").get())
    implementation(libs.findLibrary("androidx-activity-compose").get())
    implementation(libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
    implementation(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
    implementation(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
    implementation(libs.findLibrary("androidx-navigation-compose").get())
    implementation(libs.findLibrary("kotlinx-coroutines-android").get())
    debugImplementation(libs.findLibrary("androidx-compose-ui-tooling").get())
}
