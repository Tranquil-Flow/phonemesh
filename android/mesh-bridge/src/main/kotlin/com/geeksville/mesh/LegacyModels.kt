// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Meshtastic-Android d4879ceea9322bcbc1568ef27e461d34c1795887
// DataPacket.kt, NodeInfo.kt, MyNodeInfo.kt. Kept in the original package because
// the legacy AIDL binder marshals these class names across processes.
package com.geeksville.mesh

import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

const val MESHTASTIC_TEXT_MESSAGE_APP_PORT = 1

@Parcelize
enum class MessageStatus : Parcelable {
    UNKNOWN,
    RECEIVED,
    QUEUED,
    ENROUTE,
    DELIVERED,
    ERROR,
}

/** Parcelable version of Meshtastic's legacy DataPacket. */
data class DataPacket(
    var to: String? = ID_BROADCAST,
    var bytes: ByteArray?,
    var dataType: Int,
    var from: String? = ID_LOCAL,
    var time: Long = System.currentTimeMillis(),
    var id: Int = 0,
    var status: MessageStatus? = MessageStatus.UNKNOWN,
    var hopLimit: Int = 0,
    var channel: Int = 0,
) : Parcelable {
    var errorMessage: String? = null

    constructor(to: String?, channel: Int, text: String) : this(
        to = to,
        bytes = text.encodeToByteArray(),
        dataType = MESHTASTIC_TEXT_MESSAGE_APP_PORT,
        channel = channel,
    )

    val text: String?
        get() = if (dataType == MESHTASTIC_TEXT_MESSAGE_APP_PORT) bytes?.decodeToString() else null

    constructor(parcel: Parcel) : this(
        parcel.readString(),
        parcel.createByteArray(),
        parcel.readInt(),
        parcel.readString(),
        parcel.readLong(),
        parcel.readInt(),
        parcel.readMessageStatus(),
        parcel.readInt(),
        parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(to)
        parcel.writeByteArray(bytes)
        parcel.writeInt(dataType)
        parcel.writeString(from)
        parcel.writeLong(time)
        parcel.writeInt(id)
        parcel.writeParcelable(status, flags)
        parcel.writeInt(hopLimit)
        parcel.writeInt(channel)
    }

    override fun describeContents(): Int = 0

    /** Called by generated AIDL code for inout DataPacket after IMeshService.send(). */
    fun readFromParcel(parcel: Parcel) {
        to = parcel.readString()
        bytes = parcel.createByteArray()
        dataType = parcel.readInt()
        from = parcel.readString()
        time = parcel.readLong()
        id = parcel.readInt()
        status = parcel.readMessageStatus()
        hopLimit = parcel.readInt()
        channel = parcel.readInt()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataPacket) return false
        return to == other.to &&
            from == other.from &&
            time == other.time &&
            id == other.id &&
            dataType == other.dataType &&
            bytes.contentEqualsNullable(other.bytes) &&
            status == other.status &&
            hopLimit == other.hopLimit &&
            channel == other.channel
    }

    override fun hashCode(): Int {
        var result = to.hashCode()
        result = 31 * result + bytes.contentHashCodeNullable()
        result = 31 * result + dataType
        result = 31 * result + from.hashCode()
        result = 31 * result + time.hashCode()
        result = 31 * result + id
        result = 31 * result + status.hashCode()
        result = 31 * result + hopLimit
        result = 31 * result + channel
        return result
    }

    companion object CREATOR : Parcelable.Creator<DataPacket> {
        const val ID_BROADCAST = "^all"
        const val ID_LOCAL = "^local"
        const val NODENUM_BROADCAST = -1

        fun nodeNumToDefaultId(n: Int): String = "!%08x".format(n)

        override fun createFromParcel(parcel: Parcel): DataPacket = DataPacket(parcel)
        override fun newArray(size: Int): Array<DataPacket?> = arrayOfNulls(size)
    }
}

@Parcelize
data class MeshUser(
    val id: String,
    val longName: String,
    val shortName: String,
    val hwModel: MeshProtos.HardwareModel,
    val isLicensed: Boolean = false,
) : Parcelable

@Parcelize
data class Position(
    val latitude: Double,
    val longitude: Double,
    val altitude: Int,
    val time: Int = currentTime(),
) : Parcelable {
    companion object {
        fun currentTime(): Int = (System.currentTimeMillis() / 1000).toInt()
    }
}

@Parcelize
data class DeviceMetrics(
    val time: Int = currentTime(),
    val batteryLevel: Int = 0,
    val voltage: Float,
    val channelUtilization: Float,
    val airUtilTx: Float,
) : Parcelable {
    companion object {
        fun currentTime(): Int = (System.currentTimeMillis() / 1000).toInt()
    }
}

@Parcelize
data class EnvironmentMetrics(
    val time: Int = currentTime(),
    val temperature: Float,
    val relativeHumidity: Float,
    val barometricPressure: Float,
    val gasResistance: Float,
    val voltage: Float,
    val current: Float,
) : Parcelable {
    companion object {
        fun currentTime(): Int = (System.currentTimeMillis() / 1000).toInt()
    }
}

@Parcelize
data class NodeInfo(
    val num: Int,
    var user: MeshUser? = null,
    var position: Position? = null,
    var snr: Float = Float.MAX_VALUE,
    var rssi: Int = Int.MAX_VALUE,
    var lastHeard: Int = 0,
    var deviceMetrics: DeviceMetrics? = null,
    var environmentMetrics: EnvironmentMetrics? = null,
) : Parcelable

data class MyNodeInfo(
    val myNodeNum: Int,
    val hasGPS: Boolean,
    val model: String?,
    val firmwareVersion: String?,
    val couldUpdate: Boolean,
    val shouldUpdate: Boolean,
    val currentPacketId: Long,
    val messageTimeoutMsec: Int,
    val minAppVersion: Int,
    val maxChannels: Int,
    val hasWifi: Boolean,
    val channelUtilization: Float,
    val airUtilTx: Float,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readString(),
        parcel.readString(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readLong(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readFloat(),
        parcel.readFloat(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(myNodeNum)
        parcel.writeByte(if (hasGPS) 1 else 0)
        parcel.writeString(model)
        parcel.writeString(firmwareVersion)
        parcel.writeByte(if (couldUpdate) 1 else 0)
        parcel.writeByte(if (shouldUpdate) 1 else 0)
        parcel.writeLong(currentPacketId)
        parcel.writeInt(messageTimeoutMsec)
        parcel.writeInt(minAppVersion)
        parcel.writeInt(maxChannels)
        parcel.writeByte(if (hasWifi) 1 else 0)
        parcel.writeFloat(channelUtilization)
        parcel.writeFloat(airUtilTx)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<MyNodeInfo> {
        override fun createFromParcel(parcel: Parcel): MyNodeInfo = MyNodeInfo(parcel)
        override fun newArray(size: Int): Array<MyNodeInfo?> = arrayOfNulls(size)
    }
}

@Suppress("unused")
object MeshProtos {
    enum class HardwareModel {
        UNSET,
        TLORA_V2,
        TLORA_V1,
        TLORA_V2_1_1P6,
        TBEAM,
        HELTEC_V2_0,
        TBEAM_V0P7,
        T_ECHO,
        TLORA_V1_1P3,
        RAK4631,
        HELTEC_V2_1,
        HELTEC_V1,
        LILYGO_TBEAM_S3_CORE,
        RAK11200,
        NANO_G1,
        TLORA_V2_1_1P8,
        TLORA_T3_S3,
        NANO_G1_EXPLORER,
        NANO_G2_ULTRA,
        LORA_TYPE,
        WIPHONE,
        WIO_WM1110,
        RAK2560,
        HELTEC_HRU_3601,
        HELTEC_WIRELESS_BRIDGE,
        STATION_G1,
        RAK11310,
        MAKERFABS_TRACKER,
        MAKERFABS_RESERVED,
        CANARYONE,
        RP2040_LORA,
        STATION_G2,
        LORA_RELAY_V1,
        T_ECHO_PLUS,
        PPR,
        GENIEBLOCKS,
        NRF52_UNKNOWN,
        PORTDUINO,
        ANDROID_SIM,
        DIY_V1,
        NRF52840_PCA10059,
        DR_DEV,
        M5STACK,
        HELTEC_V3,
        HELTEC_WSL_V3,
        BETAFPV_2400_TX,
        BETAFPV_900_NANO_TX,
        RPI_PICO,
        HELTEC_WIRELESS_TRACKER,
        HELTEC_WIRELESS_PAPER,
        T_DECK,
        T_WATCH_S3,
        PICOMPUTER_S3,
        HELTEC_HT62,
        EBYTE_ESP32_S3,
        ESP32_S3_PICO,
        CHATTER_2,
        HELTEC_WIRELESS_PAPER_V1_0,
        HELTEC_WIRELESS_TRACKER_V1_0,
        UNPHONE,
        TD_LORAC,
        CDEBYTE_EORA_S3,
        TWC_MESH_V4,
        NRF52_PROMICRO_DIY,
        RADIOMASTER_900_BANDIT_NANO,
        HELTEC_CAPSULE_SENSOR_V3,
        HELTEC_VISION_MASTER_T190,
        HELTEC_VISION_MASTER_E213,
        HELTEC_VISION_MASTER_E290,
        HELTEC_MESH_NODE_T114,
        SENSECAP_INDICATOR,
        TRACKER_T1000_E,
        RAK3172,
        WIO_E5,
        RADIOMASTER_900_BANDIT,
        ME25LS01_4Y10TD,
        RP2040_FEATHER_RFM95,
        M5STACK_COREBASIC,
        M5STACK_CORE2,
        RPI_PICO2,
        M5STACK_CORES3,
        SEEED_XIAO_S3,
        MS24SF1,
        TLORA_C6,
        WISMESH_TAP,
        ROUTASTIC,
        MESH_TAB,
        MESHLINK,
        XIAO_NRF52_KIT,
        THINKNODE_M1,
        THINKNODE_M2,
        T_ETH_ELITE,
        HELTEC_SENSOR_HUB,
        MUZI_BASE,
        HELTEC_MESH_POCKET,
        SEEED_SOLAR_NODE,
        NOMADSTAR_METEOR_PRO,
        CROWPANEL,
        LINK_32,
        SEEED_WIO_TRACKER_L1,
        SEEED_WIO_TRACKER_L1_EINK,
        MUZI_R1_NEO,
        T_DECK_PRO,
        T_LORA_PAGER,
        M5STACK_RESERVED,
        WISMESH_TAG,
        RAK3312,
        THINKNODE_M5,
        HELTEC_MESH_SOLAR,
        T_ECHO_LITE,
        HELTEC_V4,
        M5STACK_C6L,
        M5STACK_CARDPUTER_ADV,
        HELTEC_WIRELESS_TRACKER_V2,
        T_WATCH_ULTRA,
        THINKNODE_M3,
        WISMESH_TAP_V2,
        RAK3401,
        RAK6421,
        THINKNODE_M4,
        THINKNODE_M6,
        MESHSTICK_1262,
        TBEAM_1_WATT,
        T5_S3_EPAPER_PRO,
        TBEAM_BPF,
        MINI_EPAPER_S3,
        TDISPLAY_S3_PRO,
        HELTEC_MESH_NODE_T096,
        MESH_TRACKER_X1,
        THINKNODE_M7,
        THINKNODE_M8,
        THINKNODE_M9,
        HELTEC_V4_R8,
        HELTEC_MESH_NODE_T1,
        STATION_G3,
        T_IMPULSE_PLUS,
        T_ECHO_CARD,
        SEEED_WIO_TRACKER_L2,
        CROWPANEL_P4,
        HELTEC_MESH_TOWER_V2,
        MESHNOLOGY_W10,
        HELTEC_RC32,
        HELTEC_RC52,
        HELTEC_RCC6,
        SEEED_WIO_TRACKER_L1_PRO_1W,
        MESHNOLOGY_W12,
        MESHPAGER_X2,
        T_CONNECT_PRO,
        AXIOMETA_GENESIS_MINI,
        MAKERFABS_NOMAD_TERMINAL,
        THINKNODE_MX,
        PRIVATE_HW,
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> contentEquals(other)
}

private fun ByteArray?.contentHashCodeNullable(): Int = this?.contentHashCode() ?: 0

@Suppress("DEPRECATION")
private fun Parcel.readMessageStatus(): MessageStatus? =
    if (Build.VERSION.SDK_INT >= 33) {
        readParcelable(MessageStatus::class.java.classLoader, MessageStatus::class.java)
    } else {
        readParcelable(MessageStatus::class.java.classLoader)
    }
