plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}


import java.util.Properties
import java.io.File

// Release signing: active only when keystore/dsh-mobile.properties exists
// (generated locally; never committed). Without it, release builds fall
// back to the debug key so CI/other machines keep working.
val keystorePropsFile = rootProject.file("keystore/dsh-mobile.properties")
val dshSigning = if (keystorePropsFile.exists()) {
    val p = Properties()
    p.load(keystorePropsFile.inputStream())
    mapOf(
        "storeFile" to rootProject.file(p.getProperty("storeFile")) as File,
        "storePassword" to p.getProperty("storePassword"),
        "keyAlias" to p.getProperty("keyAlias"),
        "keyPassword" to p.getProperty("keyPassword"),
    )
} else null

android {

    namespace = "com.dsh.mobile"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.dsh.mobile"
        minSdk = 26
        targetSdk = 36
        // Release CI overrides these from the pushed git tag (vX.Y.Z).
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = dshSigning?.let { s ->
                val sf: File = s["storeFile"] as File
                val sp: String = s["storePassword"] as String
                val ka: String = s["keyAlias"] as String
                val kp: String = s["keyPassword"] as String
                signingConfigs.create("dsh") {
                    storeFile = sf
                    storePassword = sp
                    keyAlias = ka
                    keyPassword = kp
                }
            } ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
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
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.window)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
        androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
