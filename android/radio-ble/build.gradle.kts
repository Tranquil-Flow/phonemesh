plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf") version "0.9.5"
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
protobuf {
    protoc { artifact="com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks {
        all().configureEach {
            // nanopb.proto only defines options; keep it import-only (its extensions need the full runtime).
            addIncludeDir(files("src/proto-include"))
            builtins { create("java") { option("lite") } }
        }
    }
}
dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    // Well-known protos (descriptor.proto) for import resolution only; compile-only, never packaged.
    compileOnly("com.google.protobuf:protobuf-java:3.25.5")
    testImplementation("junit:junit:4.13.2")
}
