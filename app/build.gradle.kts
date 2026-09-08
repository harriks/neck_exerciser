import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spineexercise.timer"

    // Build outside the non-ASCII project path: avoids file-lock/dex/cache issues
    layout.buildDirectory.set(File(System.getenv("USERPROFILE") + "/SpineTimerBuild/app"))
    compileSdk = 34

    defaultConfig {
        applicationId = "com.spineexercise.timer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("release") {
            val lp = rootProject.file("local.properties")
            val props = Properties().apply { lp.inputStream().use { load(it) } }
            storeFile = file(props.getProperty("SPINE_STORE_FILE", ""))
            storePassword = props.getProperty("SPINE_STORE_PWD", "")
            keyAlias = props.getProperty("SPINE_KEY_ALIAS", "")
            keyPassword = props.getProperty("SPINE_KEY_PWD", "")
        }
    }


    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

