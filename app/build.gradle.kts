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

// Release signing is driven by environment variables (CI) or by a local
// keystore.properties (gitignored). No key material is ever committed, and a
// machine without either simply produces an unsigned release build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = signingValue("KEYSTORE_FILE", "storeFile")
val releaseStorePassword: String? = signingValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias: String? = signingValue("KEY_ALIAS", "keyAlias")
val releaseKeyPassword: String? = signingValue("KEY_PASSWORD", "keyPassword")
val hasReleaseKeystore: Boolean = releaseStoreFile != null && file(releaseStoreFile).exists()

android {
    namespace = "nz.mckenzie.sprayday"
    compileSdk = 36

    defaultConfig {
        applicationId = "nz.mckenzie.sprayday"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Overridable from the command line so the release workflow can stamp a
        // version from the git tag and the run number.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"

        buildConfigField("String", "LINZ_API_KEY", "\"$linzApiKey\"")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
