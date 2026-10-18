// The village hub prototype on the Mac (accounts plan step 15): a radio on USB serial, a blind mailbox, and an HTTPS
// API for phones on the hub's Wi-Fi. Plain JVM; shares core (wire v2) and mesh-protocol (Meshtastic) with the app.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin { jvmToolchain(17) }
application { mainClass.set("org.phonemesh.hub.HubMainKt") }
dependencies {
    implementation(project(":core"))
    implementation(project(":mesh-protocol"))
    testImplementation("junit:junit:4.13.2")
}
