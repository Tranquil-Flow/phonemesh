plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace="org.phonemesh.smoke"
    compileSdk=35
    defaultConfig {
        applicationId="org.phonemesh.smoke"
        minSdk=26
        targetSdk=35
        testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility=JavaVersion.VERSION_17
        targetCompatibility=JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget="17" }
}
kotlin.sourceSets.getByName("androidTest").kotlin.apply {
    srcDir("../app/src/androidTest/java")
    exclude("org/phonemesh/app/AppBoundaryTest.kt")
}
kotlin.sourceSets.getByName("main").kotlin.apply {
    srcDir("../app/src/main/java")
    exclude("org/phonemesh/app/MainActivity.kt")
}
dependencies {
    implementation(project(":core"))
    implementation(files("../libs/sherpa-onnx-1.13.8.aar"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
