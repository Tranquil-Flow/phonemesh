package org.phonemesh.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager

/** What the phone allows right now. Tests replace it, so no permission is ever granted to the app. */
interface DeviceAccess {
    /** Runtime permissions still needed to scan for and connect to radios. */
    fun radioPermissionsMissing(): List<String>
    fun bluetoothOn(): Boolean
    fun microphoneAllowed(): Boolean

    /** The phone's country from the mobile network, else the SIM; ISO code, any case; null when neither is known. */
    fun countryCode(): String?

    /**
     * True when a voice download could not reach the internet. The app holds no permission to ask the phone about
     * its connection, so a download that failed or is waiting for the network is the only sign there is.
     */
    fun noInternet(): Boolean
}

class PhoneAccess(private val context: Context) : DeviceAccess {
    override fun radioPermissionsMissing(): List<String> {
        val wanted = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return wanted.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    override fun bluetoothOn(): Boolean = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    override fun microphoneAllowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Needs no permission and asks nothing over the network: the phone already knows both values. */
    override fun countryCode(): String? {
        val phone = context.getSystemService(TelephonyManager::class.java) ?: return null
        return listOf(phone.networkCountryIso, phone.simCountryIso).firstOrNull { !it.isNullOrBlank() }
    }

    override fun noInternet(): Boolean = PackDownloads.noInternet(context)
}
