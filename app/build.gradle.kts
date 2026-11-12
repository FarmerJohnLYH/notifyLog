import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Release signing is configured from an untracked keystore.properties so the
// build still works (unsigned release) on a machine that doesn't have the key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.notifylog.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.notifylog.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Only ship the languages we actually translate; drops ~1 MB of AndroidX
    // string resources for locales the app never uses.
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    packaging {
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "META-INF/*.version",
                "META-INF/com/android/build/gradle/app-metadata.properties",
                "kotlin/**"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Lint is a gate for real defects; a dependency being one patch behind
        // is not one, and the versions here are deliberately pinned.
        warningsAsErrors = false
        abortOnError = true
        disable += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable"
        )
    }
}

// Room schema export: lets us add migrations later without guessing at the
// previous shape of the table.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
}
