package org.phonemesh.hub

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import org.phonemesh.core.hub.HubCard
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * `phonemesh-hub hub.conf` (or `./gradlew :hub:run --args="/path/to/hub.conf"`): the local hub on macOS, Linux or
 * Windows. `phonemesh-hub --ports` lists the serial ports, to find the radio's.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() in UpdateCli.COMMANDS) {
        val console = System.console()
        // With a terminal the passphrase is typed unseen; without one it is the first line of standard input.
        val passphrase = { if (console != null) console.readPassword("Key passphrase: ") else System.`in`.bufferedReader().readLine()?.toCharArray() }
        System.out.flush()
        exitProcess(UpdateCli.run(args.toList(), UpdateCli.Io(System.out, System.err, passphrase)))
    }
    if (args.firstOrNull() == "--ports") {
        SerialPorts.describe(SerialPorts.list()).forEach(::println)
        return
    }
    val config = HubConfig.read(File(args.firstOrNull() ?: "hub.conf"))
    val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    val log: (String) -> Unit = { println("${clock.format(Date())} $it") }
    val mailbox = Mailbox(File(config.dir, "mail"), config.days)
    val outbox = Outbox(File(config.dir, "outbox.bin"))
    val tls = Tls(File(config.dir, "keys"))
    val os = System.getProperty("os.name")
    require(config.serial.isNotEmpty()) { "set serial = <the radio's port> in the config, for example ${SerialPorts.example(os)} (list them with --ports)" }
    val (input, output) = SerialPorts.open(config.serial, os)
    lateinit var hub: Hub
    val radio = SerialRadio(input, output, object : RadioListener {
        override fun onData(port: Int, payload: ByteArray, signal: String) = hub.onData(port, payload, signal)
        override fun onRebroadcastMode(mode: org.meshtastic.proto.ConfigProtos.Config.DeviceConfig.RebroadcastMode) = hub.onRebroadcastMode(mode)
        override fun onReady(nodeNum: Long) = log("radio ready: !%08x".format(nodeNum))
        override fun onLog(line: String) = hub.onLog(line)
    })
    hub = Hub(mailbox, radio, duty = DutyCycle(config.duty), log = log, outbox = outbox)
    radio.start()
    val server = HubServer(hub, mailbox, config.name, tls, config.port, updatesDir = config.dir)
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

// The hub QR text is HubCard in core (org.phonemesh.core.hub), so the phone parses exactly what the hub prints.
