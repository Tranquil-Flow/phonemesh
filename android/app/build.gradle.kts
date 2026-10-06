plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Test-only screenshot build: `-Pphonemesh.screenshots=true` drops FLAG_SECURE from debug builds so
// ScreenshotTour can capture the screens. Release always keeps it (ReleaseFlagsTest).
val screenshotBuild = project.findProperty("phonemesh.screenshots") == "true"
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
        buildConfigField("boolean", "SECURE_SCREENS", "true")
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
            buildConfigField("boolean", "SECURE_SCREENS", (!screenshotBuild).toString())
            buildConfigField("String", "PACK_BASE_URL", "\"http://10.0.2.2:8777/\"")
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

// Networking APIs may appear in the release dex only inside PackDownloads (see scripts/check_network_boundary.py).
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

val checkNetworkBoundary by tasks.registering(Exec::class) {
    dependsOn("assembleRelease")
    val sdkDir = android.sdkDirectory
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val rootPath = rootDir
    doFirst {
        val dexdump = File(sdkDir, "build-tools").listFiles().orEmpty()
            .filter { File(it, "dexdump").canExecute() }
            .maxWithOrNull { a, b -> compareVersions(a.name, b.name) }
            ?.let { File(it, "dexdump") }
            ?: error("No dexdump found under ${File(sdkDir, "build-tools")}; install Android build-tools")
        val apk = apkDir.get().asFile.listFiles().orEmpty()
            .singleOrNull { it.name.contains("arm64-v8a") && it.extension == "apk" }
            ?: error("No arm64-v8a release APK in ${apkDir.get().asFile}")
        commandLine(
            "python3", "$rootPath/scripts/check_network_boundary.py", apk.absolutePath,
            "--allowlist", "$rootPath/network-boundary-allowlist.txt",
            "--dexdump", dexdump.absolutePath,
            "--require-class", "Lorg/phonemesh/app/PackDownloads;",
            "--require-api-in", "Lorg/phonemesh/app/PackDownloads;",
        )
    }
}
