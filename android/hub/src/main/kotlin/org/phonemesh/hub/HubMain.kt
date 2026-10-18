package org.phonemesh.hub

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** `./gradlew :hub:run --args="/path/to/hub.conf"`: the hub prototype on the Mac. */
fun main(args: Array<String>) {
    val config = HubConfig.read(File(args.firstOrNull() ?: "hub.conf"))
    val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    val log: (String) -> Unit = { println("${clock.format(Date())} $it") }
    val mailbox = Mailbox(File(config.dir, "mail"), config.days)
    val tls = Tls(File(config.dir, "keys"))
    require(config.serial.isNotEmpty()) { "set serial = /dev/cu.<the radio> in the config" }
    val (input, output) = SerialRadio.openTty(config.serial)
    lateinit var hub: Hub
    val radio = SerialRadio(input, output, object : RadioListener {
        override fun onData(port: Int, payload: ByteArray, signal: String) = hub.onData(port, payload, signal)
        override fun onRebroadcastMode(mode: org.meshtastic.proto.ConfigProtos.Config.DeviceConfig.RebroadcastMode) = hub.onRebroadcastMode(mode)
        override fun onReady(nodeNum: Long) = log("radio ready: !%08x".format(nodeNum))
        override fun onLog(line: String) = hub.onLog(line)
    })
    hub = Hub(mailbox, radio, duty = DutyCycle(config.duty), log = log)
    radio.start()
    val server = HubServer(hub, mailbox, config.name, tls, config.port)
    server.start()
    log("hub '${config.name}' on port ${server.port}, ${mailbox.size()} letters kept for ${config.days} days")
    log("hub card: ${HubCard(config.wifi, config.wifiPassword, config.port, tls.pin).encode()}")
    thread(name = "hub-tick", isDaemon = false) {
        var lastExpiry = 0L
        while (true) {
            hub.tick()
            val now = System.currentTimeMillis()
            if (now - lastExpiry > 3_600_000) { mailbox.expire(now); lastExpiry = now }
            Thread.sleep(250)
        }
    }
}

/**
 * What the hub QR will carry (step 16 draws it): the Wi-Fi to join, the hub's port on it (the address is the Wi-Fi's
 * gateway) and the TLS pin.
 */
data class HubCard(val wifi: String, val password: String, val port: Int, val pin: String) {
    fun encode(): String = "phonemesh-hub:1;wifi=${esc(wifi)};pass=${esc(password)};port=$port;pin=$pin"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;")
}
