import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

// LINZ Basemaps API key.
// Supplied locally through local.properties (gitignored) for debug builds, or
// through the LINZ_API_KEY environment variable in CI release builds.
// It is only used as a *default*: the app also accepts a key entered at runtime.
val linzApiKey: String = run {
    val propsFile = rootProject.file("local.properties")
    val fromFile = if (propsFile.exists()) {
        Properties().apply { propsFile.inputStream().use { load(it) } }.getProperty("LINZ_API_KEY")
    } else {
        null
    }
    (fromFile ?: System.getenv("LINZ_API_KEY") ?: "").trim()
}

// Release signing is driven entirely by environment variables so that no key
// material is ever committed. Absent locally -> release builds are unsigned.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val hasReleaseKeystore: Boolean = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "nz.mckenzie.sprayday"
    compileSdk = 36

    defaultConfig {
        applicationId = "nz.mckenzie.sprayday"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "LINZ_API_KEY", "\"$linzApiKey\"")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds are cached normally; nothing special required yet.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Makes KSP-generated Kotlin sources visible to the IDE immediately.
    applicationVariants.all {
        addJavaSourceFoldersToModel(
            layout.buildDirectory.dir("generated/ksp/$name/kotlin").get().asFile
        )
    }
}

ksp {
    // Room schema export - committed so future migrations can be diffed/tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.tooling)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Settings storage
    implementation(libs.datastore.preferences)

    // Mapping and location
    implementation(libs.maplibre.android.sdk)
    implementation(libs.play.services.location)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.room.testing)
}
