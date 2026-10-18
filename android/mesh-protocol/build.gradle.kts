// Meshtastic protobufs and the pure protocol helpers, shared by the app's Bluetooth transport and the hub (plain JVM).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.protobuf") version "0.9.5"
}
kotlin { jvmToolchain(17) }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks {
        all().configureEach {
            // nanopb.proto only defines options; keep it import-only (its extensions need the full runtime).
            addIncludeDir(files("src/proto-include"))
            builtins { named("java") { option("lite") } }
        }
    }
}
dependencies {
    api("com.google.protobuf:protobuf-javalite:3.25.5")
    // Well-known protos (descriptor.proto) for import resolution only; compile-only, never packaged.
    compileOnly("com.google.protobuf:protobuf-java:3.25.5")
    testImplementation("junit:junit:4.13.2")
}
