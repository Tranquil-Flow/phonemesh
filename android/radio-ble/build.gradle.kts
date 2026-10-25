plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace="org.phonemesh.ble"
    compileSdk=35
    defaultConfig { minSdk=26 }
    compileOptions {
        sourceCompatibility=JavaVersion.VERSION_17
        targetCompatibility=JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget="17" }
    testOptions { unitTests.isReturnDefaultValues=true }
}
dependencies {
    // Meshtastic protobufs and MeshtasticProtocol, shared with the hub.
    api(project(":mesh-protocol"))
    // The Bluetooth hub link speaks core's GattStreams/HubGatt framing (plain JVM, no Android types).
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
