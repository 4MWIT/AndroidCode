import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localSecrets = Properties().apply {
    val secretsFile = rootProject.file("local.properties")
    if (secretsFile.isFile) secretsFile.inputStream().use(::load)
}

fun localSecret(name: String): String = localSecrets.getProperty(name, "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.tom.rv2ide"
    compileSdk = 36

    defaultConfig {
        // Termux/bootstrap/idesetup artifacts we borrow from the donor app are
        // compiled against this package path. Until we rebuild that toolchain
        // for a new package, we keep a donor-compatible applicationId so the
        // runtime lives under the path those binaries expect.
        applicationId = "com.tom.rv2ide"
        minSdk = 27
        // The donor runtime/toolchain works under the legacy Termux-style exec model
        // and is known-good with targetSdk 28. Higher targetSdk levels trigger
        // platform exec restrictions for binaries from app-private storage.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"

        // Values come from ignored local.properties and are embedded only into the APK.
        buildConfigField("String", "OPENCODE_API_KEY", "\"${localSecret("aicode.opencodelKey")}\"")
        buildConfigField("String", "NVIDIA_API_KEY", "\"${localSecret("aicode.nvidiaKey")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += ""
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            // Keep release behavior identical to debug for the embedded Node/Pi runtime.
            // The output is installable, but R8/resource shrinking stay deliberately disabled.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // targetSdk 28 is intentional: the bundled native toolchain relies on the
        // legacy private executable model. Keep all other release lint checks on.
        disable += "ExpiredTargetSdkVersion"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.commons.compress)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
