import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf") version "0.9.5"
}

val meshtasticProtobufSha = "8017acbb0828703e980323bee3dd605fb7366058"
val meshtasticProtoZip = layout.buildDirectory.file("downloads/meshtastic-protobufs-$meshtasticProtobufSha.zip")
val meshtasticProtoDir = layout.projectDirectory.dir("src/main/proto")

val downloadMeshtasticProtos by tasks.registering {
    outputs.file(meshtasticProtoZip)
    doLast {
        val out = meshtasticProtoZip.get().asFile
        if (!out.exists()) {
            out.parentFile.mkdirs()
            URI("https://github.com/meshtastic/protobufs/archive/$meshtasticProtobufSha.zip")
                .toURL()
                .openStream()
                .use { input -> Files.copy(input, out.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}

val syncMeshtasticProtos by tasks.registering(Sync::class) {
    dependsOn(downloadMeshtasticProtos)
    from(zipTree(meshtasticProtoZip)) {
        include("protobufs-$meshtasticProtobufSha/meshtastic/**/*.proto")
        include("protobufs-$meshtasticProtobufSha/*.proto")
        eachFile { path = path.removePrefix("protobufs-$meshtasticProtobufSha/") }
        includeEmptyDirs = false
    }
    into(meshtasticProtoDir)
}

android {
    namespace = "org.phonemesh.ble"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks {
        all().configureEach {
            dependsOn(syncMeshtasticProtos)
            builtins {
                create("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    protobuf("com.google.protobuf:protobuf-java:3.25.5")
    testImplementation("junit:junit:4.13.2")
}
