package org.phonemesh.hub

import java.io.File

/**
 * The hub admin's settings (accounts spec, "Admin settings"), from a small `key = value` file read at start:
 * `name`, `days` (mail kept, default 30), `wifi` and `wifi_password` (for the hub QR), `serial` (the radio's device),
 * `port` (HTTPS, default 8443), `duty` (radio-time percent, default 10), `dir` (mail and keys). `#` starts a comment.
 */
data class HubConfig(
    val name: String = "Phonemesh hub",
    val days: Int = 30,
    val wifi: String = "",
    val wifiPassword: String = "",
    val serial: String = "",
    val port: Int = 8443,
    val duty: Int = 10,
    val dir: File = File("hub-data"),
) {
    companion object {
        fun read(file: File): HubConfig {
            val values = file.readLines().map { it.substringBefore('#').trim() }.filter { '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            val unknown = values.keys - setOf("name", "days", "wifi", "wifi_password", "serial", "port", "duty", "dir")
            require(unknown.isEmpty()) { "unknown settings in ${file.name}: $unknown" }
            fun int(key: String, default: Int, range: IntRange): Int {
                val v = values[key] ?: return default
                return v.toIntOrNull()?.takeIf { it in range } ?: throw IllegalArgumentException("$key must be a number in $range, not '$v'")
            }
            return HubConfig(
                name = values["name"] ?: "Phonemesh hub",
                days = int("days", 30, 1..3650),
                wifi = values["wifi"].orEmpty(),
                wifiPassword = values["wifi_password"].orEmpty(),
                serial = values["serial"].orEmpty(),
                port = int("port", 8443, 1..65535),
                duty = int("duty", 10, 1..100),
                dir = values["dir"]?.let { File(it) } ?: File(file.absoluteFile.parentFile, "hub-data"),
            )
        }
    }
}
