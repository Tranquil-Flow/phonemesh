// SPDX-License-Identifier: GPL-3.0-or-later
// Source: meshtastic/Meshtastic-Android d4879ceea9322bcbc1568ef27e461d34c1795887
// app/src/main/aidl/com/geeksville/mesh/IMeshService.aidl
package com.geeksville.mesh;

parcelable DataPacket;
parcelable NodeInfo;
parcelable MeshUser;
parcelable Position;
parcelable MyNodeInfo;

/**
 * Legacy public Android API for talking to Meshtastic radios.
 * Current Meshtastic Android main no longer exports this service; see README.
 */
interface IMeshService {
    void subscribeReceiver(String packageName, String receiverName);
    void setOwner(in MeshUser user);
    void setRemoteOwner(in int destNum, in byte[] payload);
    void getRemoteOwner(in int requestId, in int destNum);
    String getMyId();
    int getPacketId();
    void send(inout DataPacket packet);
    List getNodes();
    byte[] getConfig();
    void setConfig(in byte[] payload);
    void setRemoteConfig(in int destNum, in byte[] payload);
    void getRemoteConfig(in int requestId, in int destNum, in int configTypeValue);
    void setModuleConfig(in int destNum, in byte[] payload);
    void getModuleConfig(in int requestId, in int destNum, in int moduleConfigTypeValue);
    void setRingtone(in int destNum, in String ringtone);
    void getRingtone(in int requestId, in int destNum);
    void setCannedMessages(in int destNum, in String messages);
    void getCannedMessages(in int requestId, in int destNum);
    void setChannel(in byte[] payload);
    void setRemoteChannel(in int destNum, in byte[] payload);
    void getRemoteChannel(in int requestId, in int destNum, in int channelIndex);
    void beginEditSettings();
    void commitEditSettings();
    void requestPosition(in int destNum, in Position position);
    void requestTraceroute(in int requestId, in int destNum);
    void requestShutdown(in int requestId, in int destNum);
    void requestReboot(in int requestId, in int destNum);
    void requestFactoryReset(in int requestId, in int destNum);
    void requestNodedbReset(in int requestId, in int destNum);
    byte[] getChannelSet();
    String connectionState();
    boolean setDeviceAddress(String deviceAddr);
    MyNodeInfo getMyNodeInfo();
    void startFirmwareUpdate();
    int getUpdateStatus();
    void startProvideLocation();
    void stopProvideLocation();
}
