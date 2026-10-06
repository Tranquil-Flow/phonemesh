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
        testProguardFiles("proguard-rules.pro")
        buildConfigField("boolean", "ALLOW_UNKNOWN_PACKS", "false")
        buildConfigField("String", "PACK_BASE_URL", "\"${project.findProperty("phonemesh.packBaseUrl") ?: "https://packs.invalid/"}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_UNKNOWN_PACKS", "true")
            buildConfigField("String", "PACK_BASE_URL", "\"http://10.0.2.2:8765/\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("minifiedDebug") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-minified-debug.pro")
            matchingFallbacks += listOf("debug")
        }
    }
    testBuildType = "minifiedDebug"
    // minifiedDebug does not inherit src/debug; the minified test build must reach the local test server.
    sourceSets {
        getByName("minifiedDebug") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            res.srcDirs("src/debug/res")
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("**/*.proto") }
    }
    splits {
        abi {
            isEnable=true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
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

val checkApkSize by tasks.registering {
    dependsOn("assembleRelease")
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            val apk = dir.listFiles().orEmpty().single { it.name.contains(abi) && it.extension == "apk" }
            val limit = (project.findProperty("phonemesh.maxApkBytes.$abi") as String).toLong()
            check(apk.length() <= limit) { "${apk.name} is ${apk.length()} B, over the $limit B budget" }
            println("APK_SIZE $abi ${apk.length()}")
        }
    }
}
