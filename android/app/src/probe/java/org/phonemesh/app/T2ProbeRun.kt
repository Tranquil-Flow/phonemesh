package org.phonemesh.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Hardware test T2 of the accounts plan, in probe builds only (BuildConfig.T2_PROBE): can a phone join a hub's Wi-Fi
 * that has no internet, reach the hub, and keep mobile data for the internet? Started from the Mac:
 * - on the phone sharing its connection (hotspot on, mobile data off):
 *   `am broadcast -p org.phonemesh.app -a org.phonemesh.app.T2_SERVE`
 * - on the other phone: `am broadcast -p org.phonemesh.app -a org.phonemesh.app.T2_JOIN --es ssid NAME --es pass WORD`
 *   then `... -a org.phonemesh.app.T2_LEAVE`.
 * Everything goes to logcat (tag PhonemeshT2) and files/t2-probe.log.
 */
// Its permissions exist only in probe builds (src/probe/AndroidManifest.xml); it never runs otherwise.
@SuppressLint("MissingPermission")
internal class T2ProbeRun(private val app: Context) {
    private val file = File(app.filesDir, "t2-probe.log")
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var server: ServerSocket? = null

    fun start() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    SERVE -> serve()
                    JOIN -> join(intent.getStringExtra("ssid").orEmpty(), intent.getStringExtra("pass").orEmpty())
                    LEAVE -> leave()
                }
            }
        }
        val filter = IntentFilter().apply { addAction(SERVE); addAction(JOIN); addAction(LEAVE) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        note("probe ready, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    private fun serve() {
        if (server != null) { note("already serving"); return }
        thread(name = "t2-serve", isDaemon = true) {
            runCatching {
                val socket = ServerSocket(PORT).also { server = it }
                note("serving on port $PORT")
                while (true) socket.accept().use { client ->
                    client.getInputStream().bufferedReader().readLine()
                    client.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\npong ${Build.MODEL}\n".toByteArray())
                    note("answered ${client.inetAddress.hostAddress}")
                }
            }.onFailure { note("serving stopped: ${it.javaClass.simpleName} ${it.message}") }
            server = null
        }
    }

    private fun join(ssid: String, pass: String) {
        leave()
        note("join ssid=$ssid; default before: ${describe(connectivity.activeNetwork)}")
        val specifier = runCatching {
            WifiNetworkSpecifier.Builder().setSsid(ssid).apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }.build()
        }.getOrElse { note("specifier refused: ${it.message}"); return }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val asked = SystemClock.elapsedRealtime()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                note("available after ${SystemClock.elapsedRealtime() - asked} ms: ${describe(network)}")
                thread(name = "t2-check") { check(network) }
            }
            override fun onUnavailable() = note("unavailable after ${SystemClock.elapsedRealtime() - asked} ms (refused, cancelled or not found)")
            override fun onLost(network: Network) = note("lost")
        }
        callback = cb
        runCatching { connectivity.requestNetwork(request, cb, 120_000) }.onFailure { note("request failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun check(network: Network) {
        val gateway = connectivity.getLinkProperties(network)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
        note("gateway ${gateway?.hostAddress}")
        if (gateway != null) note("hub ping: ${ping(network, gateway)}")
        note("default network now: ${describe(connectivity.activeNetwork)}")
        note("internet on the default network: ${fetch(null, "https://connectivitycheck.gstatic.com/generate_204")}")
    }

    /** A plain socket on [network]: the debug network security config refuses cleartext HTTP to any address but 10.0.2.2. */
    private fun ping(network: Network, hub: java.net.InetAddress): String {
        val start = SystemClock.elapsedRealtime()
        return runCatching {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(java.net.InetSocketAddress(hub, PORT), 8000)
                socket.soTimeout = 8000
                socket.getOutputStream().write("GET /ping HTTP/1.0\r\n\r\n".toByteArray())
                val reply = socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n").trim()
                "'${reply.take(40)}' in ${SystemClock.elapsedRealtime() - start} ms"
            }
        }.getOrElse { "failed in ${SystemClock.elapsedRealtime() - start} ms: ${it.javaClass.simpleName} ${it.message}" }
    }

    private fun fetch(network: Network?, address: String): String {
        val start = SystemClock.elapsedRealtime()
        return runCatching {
            val url = URL(address)
            val connection = (if (network != null) network.openConnection(url) else url.openConnection()) as HttpURLConnection
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            val code = connection.responseCode
            val body = runCatching { connection.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
            connection.disconnect()
            "HTTP $code '${body.take(40)}' in ${SystemClock.elapsedRealtime() - start} ms"
        }.getOrElse { "failed in ${SystemClock.elapsedRealtime() - start} ms: ${it.javaClass.simpleName} ${it.message}" }
    }

    private fun describe(network: Network?): String {
        if (network == null) return "none"
        val caps = connectivity.getNetworkCapabilities(network) ?: return "no capabilities"
        val transport = listOfNotNull(
            "wifi".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
            "cellular".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
        ).joinToString("+").ifEmpty { "other" }
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "$transport internet=$internet validated=$validated"
    }

    private fun leave() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) }; note("left") }
        callback = null
    }

    private fun note(line: String) {
        Log.i(TAG, line)
        runCatching { file.appendText("${clock.format(Date())} $line\n") }
    }

    companion object {
        const val SERVE = "org.phonemesh.app.T2_SERVE"
        const val JOIN = "org.phonemesh.app.T2_JOIN"
        const val LEAVE = "org.phonemesh.app.T2_LEAVE"
        private const val PORT = 8765
        private const val TAG = "PhonemeshT2"
    }
}
