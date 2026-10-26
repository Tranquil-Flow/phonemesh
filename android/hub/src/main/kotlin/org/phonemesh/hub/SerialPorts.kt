package org.phonemesh.hub

import com.fazecast.jSerialComm.SerialPort
import java.io.InputStream
import java.io.OutputStream

/**
 * Opens the radio's USB serial port on the hub's computer. macOS keeps the stty path the hub was qualified with
 * ([SerialRadio.openTty]). Linux and Windows use jSerialComm: plain Java cannot set a port's speed there, and on
 * Windows a port opened as a file cannot be read and written at the same time.
 */
object SerialPorts {
    enum class Driver { STTY, PORTABLE }

    /** A port the computer can see, for `--ports`. */
    data class Found(val name: String, val description: String, val detail: String)

    fun driverFor(os: String): Driver = if (os.startsWith("Mac")) Driver.STTY else Driver.PORTABLE

    /** What a radio's port is usually called on [os], for messages. */
    fun example(os: String): String = when {
        os.startsWith("Windows") -> "COM3"
        os.startsWith("Mac") -> "/dev/cu.usbmodem1101"
        else -> "/dev/ttyACM0"
    }

    fun open(path: String, os: String = System.getProperty("os.name")): Pair<InputStream, OutputStream> =
        when (driverFor(os)) {
            Driver.STTY -> SerialRadio.openTty(path)
            Driver.PORTABLE -> openPortable(path)
        }

    /** 115200 baud, 8N1, no flow control; a read waits for at least one byte, as [SerialRadio]'s read loop expects. */
    fun openPortable(path: String): Pair<InputStream, OutputStream> {
        val port = try {
            SerialPort.getCommPort(path)
        } catch (e: Exception) {
            throw IllegalArgumentException("no serial port called $path. Run the hub with --ports to list them.", e)
        }
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 0)
        require(port.openPort()) {
            "cannot open the serial port $path (error ${port.lastErrorCode}). Check the name with --ports; on Linux, " +
                "add yourself to the dialout group; close any other program using the radio."
        }
        return port.inputStream to port.outputStream
    }

    fun list(): List<Found> = SerialPort.getCommPorts().map {
        Found(it.systemPortPath.takeUnless { p -> p.isNullOrEmpty() || p.startsWith("\\\\.\\") } ?: it.systemPortName,
            it.descriptivePortName.orEmpty(), it.portDescription.orEmpty())
    }

    fun describe(found: List<Found>): List<String> =
        if (found.isEmpty()) listOf("No serial ports found. Plug in the radio's USB cable and try again.")
        else found.map { f ->
            val detail = f.detail.takeIf { it.isNotBlank() && it != f.description }?.let { " ($it)" }.orEmpty()
            "${f.name}  ${f.description}$detail"
        }
}
