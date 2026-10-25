plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Test-only screenshot build: `-Pphonemesh.screenshots=true` drops FLAG_SECURE from debug builds so
// ScreenshotTour can capture the screens. Release always keeps it (ReleaseFlagsTest).
val screenshotBuild = project.findProperty("phonemesh.screenshots") == "true"
// Test-only: -Pphonemesh.fakeCountry=US makes a debug build guess that country (real-radio Change and helper tests).
// Release always uses the phone's own country (ReleaseFlagsTest).
val fakeCountry = (project.findProperty("phonemesh.fakeCountry") as String?).orEmpty()
require(fakeCountry.isEmpty() || Regex("[A-Z]{2}").matches(fakeCountry)) { "phonemesh.fakeCountry must be two capital letters" }
// Where the app downloads its packs: the address plus a catalog file name. A release build installs a ZIP only if it
// has the hash in the app's catalog (debug builds skip that, see ALLOW_UNKNOWN_PACKS).
// -Pphonemesh.packBaseUrl=https://.../ replaces the address in every build type. Without it, release uses the
// published packs and debug builds use the test pack server on the development machine as the emulator sees it
// (scripts/test_pack_server.py), which a real phone cannot reach.
// Pictures over the radio (branch feat/image-messages). -Pphonemesh.pictures=false builds the voice-only app from this branch.
val picturesBuild = project.findProperty("phonemesh.pictures") != "false"
// Hardware test T1 of the accounts spec: -Pphonemesh.t1probe=true adds a probe that adb starts (T1ProbeRun). Debug only.
val t1Probe = project.findProperty("phonemesh.t1probe") == "true"
// Hardware test T2 (joining a hub's Wi-Fi with no internet): -Pphonemesh.t2probe=true adds src/probe/java (T2ProbeRun)
// and, through src/probe/AndroidManifest.xml, the Wi-Fi permissions it needs. Debug only; its code is not even compiled
// into a normal build.
val t2Probe = project.findProperty("phonemesh.t2probe") == "true"
val publishedPackBaseUrl = "https://github.com/Tranquil-Flow/phonemesh-packs/releases/download/packs-1/"
val emulatorPackBaseUrl = "http://10.0.2.2:8777/"
val packBaseUrl = project.findProperty("phonemesh.packBaseUrl") as String?
require(packBaseUrl == null || Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?/([A-Za-z0-9._~%-]+/)*").matches(packBaseUrl)) {
    "phonemesh.packBaseUrl must be an https:// address that ends with / (got \"$packBaseUrl\")"
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
        buildConfigField("boolean", "SECURE_SCREENS", "true")
        buildConfigField("boolean", "PICTURES", picturesBuild.toString())
        buildConfigField("String", "FAKE_COUNTRY", "\"\"")
        buildConfigField("String", "PACK_BASE_URL", "\"${packBaseUrl ?: publishedPackBaseUrl}\"")
        buildConfigField("boolean", "T1_PROBE", "false")
        buildConfigField("boolean", "T2_PROBE", "false")
        // The picture upscaling bridge (src/main/cpp) is built for the two phone ABIs the APKs carry.
        externalNativeBuild { cmake { abiFilters("arm64-v8a", "armeabi-v7a") } }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
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
            buildConfigField("String", "FAKE_COUNTRY", "\"$fakeCountry\"")
            buildConfigField("String", "PACK_BASE_URL", "\"${packBaseUrl ?: emulatorPackBaseUrl}\"")
            buildConfigField("boolean", "T1_PROBE", t1Probe.toString())
            buildConfigField("boolean", "T2_PROBE", t2Probe.toString())
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
        if (t1Probe || t2Probe) getByName("debug") { java.srcDir("src/probe/java") }
        if (t2Probe) getByName("debug") { manifest.srcFile("src/probe/AndroidManifest.xml") }
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
    // QR codes for adding people (accounts plan step 11; the one new library, Apache-2.0). Camera through Camera2.
    implementation("com.google.zxing:core:3.5.4")
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

// Networking APIs may appear in the release dex only inside PackDownloads, HubClient and WifiHubLink (see scripts/check_network_boundary.py).
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
            "--require-class", "Lorg/phonemesh/core/hub/HubClient;",
            "--require-api-in", "Lorg/phonemesh/core/hub/HubClient;",
            "--require-class", "Lorg/phonemesh/core/hub/HubEngineTls;",
            "--require-api-in", "Lorg/phonemesh/core/hub/HubEngineTls;",
            "--require-class", "Lorg/phonemesh/app/WifiHubLink;",
            "--require-api-in", "Lorg/phonemesh/app/WifiHubLink;",
        )
    }
}
