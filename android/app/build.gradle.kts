plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.phonemesh.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.phonemesh.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += setOf("**/*.proto") }
    }
    splits {
        abi {
            isEnable=true
            reset()
            include("arm64-v8a","armeabi-v7a","x86","x86_64")
            isUniversalApk=true
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(project(":radio-ble"))
    implementation(project(":core"))
    implementation(files("../libs/sherpa-onnx-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
