plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.infraxcoders.bmpcc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.infraxcoders.bmpcccontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A fixed key for test builds, so a new APK installs over the previous one wherever it was built
        // (GitHub or a Mac). Not for the Play Store: make a private key for that.
        create("testing") {
            storeFile = rootProject.file("keystore/testing.keystore")
            storePassword = "bmpcctest"
            keyAlias = "bmpcctest"
            keyPassword = "bmpcctest"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("testing")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("testing")
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.androidx.exifinterface)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
