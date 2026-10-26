// The local hub (accounts plan step 15) on macOS, Linux or Windows: a radio on USB serial, a blind mailbox, and an HTTPS
// API for phones on the hub's Wi-Fi. Plain JVM; shares core (wire v2) and mesh-protocol (Meshtastic) with the app.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin { jvmToolchain(17) }
application {
    mainClass.set("org.phonemesh.hub.HubMainKt")
    // `:hub:distZip` makes phonemesh-hub.zip: bin/phonemesh-hub (macOS, Linux) and bin/phonemesh-hub.bat (Windows).
    applicationName = "phonemesh-hub"
}
dependencies {
    implementation(project(":core"))
    implementation(project(":mesh-protocol"))
    // Serial ports on Linux and Windows (macOS keeps stty). Apache-2.0 (dual with LGPL-3.0), no dependencies.
    implementation("com.fazecast:jSerialComm:2.11.4")
    testImplementation("junit:junit:4.13.2")
}
