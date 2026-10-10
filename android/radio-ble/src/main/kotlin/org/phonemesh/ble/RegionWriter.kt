package org.phonemesh.ble

import com.google.protobuf.ByteString
import org.meshtastic.proto.AdminProtos.AdminMessage
import org.meshtastic.proto.ConfigProtos.Config
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode

/** Builds the admin messages that change only the region. Pure, no Android. */
internal object RegionWriter {
    /** The licensed amateur bands of the bundled config.proto. Phonemesh never offers or writes them. */
    private val amateur = setOf(
        RegionCode.ITU1_2M, RegionCode.ITU2_2M, RegionCode.ITU3_2M,
        RegionCode.ITU1_70CM, RegionCode.ITU2_70CM, RegionCode.ITU3_70CM,
        RegionCode.ITU2_125CM,
    )

    /** A region Phonemesh may write: a named region that is not UNSET and not a licensed amateur band. */
    fun allowed(region: RegionCode): Boolean =
        region != RegionCode.UNSET && region != RegionCode.UNRECOGNIZED && region !in amateur

    /** The allowed region with exactly this name, or null. */
    fun code(name: String): RegionCode? = RegionCode.values().firstOrNull { it.name == name && allowed(it) }

    fun getLora(passkey: ByteString = ByteString.EMPTY): AdminMessage =
        AdminMessage.newBuilder().setGetConfigRequest(AdminMessage.ConfigType.LORA_CONFIG).setSessionPasskey(passkey).build()

    /** The settings to copy: the local radio's reply if it carried LoRa, else the handshake's, else null (write nothing). */
    fun base(reply: RadioEvent.AdminLora?, local: Long, handshake: LoRaConfig?): LoRaConfig? =
        reply?.takeIf { it.from == local }?.lora ?: handshake

    /** The session passkey to send back: the one in the local radio's own reply, never another node's. */
    fun passkey(reply: RadioEvent.AdminLora?, local: Long): ByteString =
        reply?.takeIf { it.from == local }?.passkey ?: ByteString.EMPTY

    /** begin_edit_settings, set_config (base with only the region changed), commit_edit_settings. */
    fun messages(base: LoRaConfig, region: RegionCode, passkey: ByteString): List<AdminMessage> {
        require(allowed(region)) { "not a writable region: ${region.name}" }
        fun admin() = AdminMessage.newBuilder().setSessionPasskey(passkey)
        val lora = base.toBuilder().setRegion(region).build()
        return listOf(
            admin().setBeginEditSettings(true).build(),
            admin().setSetConfig(Config.newBuilder().setLora(lora)).build(),
            admin().setCommitEditSettings(true).build(),
        )
    }
}
