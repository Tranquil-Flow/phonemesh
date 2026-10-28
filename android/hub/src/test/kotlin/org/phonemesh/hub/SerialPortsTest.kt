package org.phonemesh.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The hub on macOS, Linux and Windows: how it opens the radio and finds keytool on each. */
class SerialPortsTest {
    @Test fun macKeepsSttyAndLinuxAndWindowsUseThePortableDriver() {
        assertEquals(SerialPorts.Driver.STTY, SerialPorts.driverFor("Mac OS X"))
        assertEquals(SerialPorts.Driver.PORTABLE, SerialPorts.driverFor("Linux"))
        assertEquals(SerialPorts.Driver.PORTABLE, SerialPorts.driverFor("Windows 11"))
        assertEquals(SerialPorts.Driver.PORTABLE, SerialPorts.driverFor("FreeBSD"))
    }

    @Test fun theStorePasswordNeverLooksLikeAKeytoolOption() {
        // keytool hands any argument starting with -J to its JVM, so a random password like "-JWS..." broke a first start.
        val dashJ = byteArrayOf(0xF8.toByte(), 0x95.toByte()) + ByteArray(22)
        assertTrue(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(dashJ).startsWith("-J"))
        for (bytes in listOf(dashJ, ByteArray(24) { 0xFF.toByte() }, ByteArray(24))) {
            val pass = Tls.storePassword(bytes)
            assertTrue(pass, pass.matches(Regex("[0-9a-f]{48}")))
        }
    }

    @Test fun keytoolHasItsWindowsName() {
        assertEquals("keytool.exe", Tls.keytoolName("Windows 10"))
        assertEquals("keytool", Tls.keytoolName("Linux"))
        assertEquals("keytool", Tls.keytoolName("Mac OS X"))
    }

    @Test fun theExampleNamesEachSystemsPort() {
        assertTrue(SerialPorts.example("Windows 11").startsWith("COM"))
        assertTrue(SerialPorts.example("Linux").startsWith("/dev/tty"))
        assertTrue(SerialPorts.example("Mac OS X").startsWith("/dev/cu."))
    }

    @Test fun aMissingPortSaysHowToFindTheRightOne() {
        try {
            SerialPorts.openPortable("/dev/phonemesh-no-such-port")
            fail("opened a port that does not exist")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("--ports"))
        }
    }

    @Test fun portListingIsOneReadableLinePerPort() {
        val lines = SerialPorts.describe(listOf(SerialPorts.Found("COM4", "USB Serial Device", "Silicon Labs CP210x")))
        assertEquals(listOf("COM4  USB Serial Device (Silicon Labs CP210x)"), lines)
        assertEquals(listOf("No serial ports found. Plug in the radio's USB cable and try again."), SerialPorts.describe(emptyList()))
    }
}
