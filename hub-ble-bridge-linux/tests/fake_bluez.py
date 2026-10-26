"""Integration check of the BlueZ glue with no Bluetooth hardware: a fake org.bluez on a private
D-Bus (pointed to by
DBUS_SYSTEM_BUS_ADDRESS) registers the bridge, then plays two phones against it, with a TCP server
as the hub.
It checks the D-Bus contract as this file understands BlueZ's API; it does not prove real BlueZ or
radio behaviour.
Exit 0 when every step passed. Run by tests/run_fake_bluez.sh in a Linux container."""

import socket
import sys
import threading
import time

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

sys.path.insert(0, sys.argv[3])
from phonemesh_bridge import core  # noqa: E402

PIN, PORT = sys.argv[1], int(sys.argv[2])
ADAPTER, DEV_A, DEV_B = "/org/bluez/hci0", "/org/bluez/hci0/dev_AA", "/org/bluez/hci0/dev_BB"
dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
bus = dbus.SystemBus()
name = dbus.service.BusName("org.bluez", bus)
loop = GLib.MainLoop()
results = []


def check(label, ok):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + label, flush=True)


class Hub:
    """Stands in for the JVM hub's HTTPS port: greets each connection and records what arrives."""

    def __init__(self):
        self.srv = socket.socket()
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", PORT))
        self.srv.listen(4)
        self.conns, self.got, self.eof = [], [], []
        threading.Thread(target=self.accept, daemon=True).start()

    def accept(self):
        while True:
            c, _ = self.srv.accept()
            self.conns.append(c)
            c.sendall(b"HELLO-FROM-HUB")
            threading.Thread(target=self.read, args=(c, len(self.conns) - 1), daemon=True).start()

    def read(self, c, i):
        self.got.append(b"")
        self.eof.append(False)
        while True:
            d = c.recv(4096)
            if not d:
                self.eof[i] = True
                return
            self.got[i] += d


hub = Hub()


class Root(dbus.service.Object):
    @dbus.service.method("org.freedesktop.DBus.ObjectManager", out_signature="a{oa{sa{sv}}}")
    def GetManagedObjects(self):
        return {
            dbus.ObjectPath(ADAPTER): {
                "org.bluez.Adapter1": {"Powered": dbus.Boolean(True)},
                "org.bluez.GattManager1": {},
                "org.bluez.LEAdvertisingManager1": {},
            }
        }


class Adapter(dbus.service.Object):
    unregistered = []

    @dbus.service.method("org.bluez.GattManager1", in_signature="oa{sv}", sender_keyword="sender")
    def RegisterApplication(self, app, options, sender=None):
        GLib.timeout_add(200, lambda: scenario(sender, str(app)) and False)

    @dbus.service.method("org.bluez.GattManager1", in_signature="o")
    def UnregisterApplication(self, app):
        Adapter.unregistered.append("app")

    @dbus.service.method(
        "org.bluez.LEAdvertisingManager1", in_signature="oa{sv}", sender_keyword="sender"
    )
    def RegisterAdvertisement(self, path, options, sender=None):
        props = dbus.Interface(
            bus.get_object(sender, path), "org.freedesktop.DBus.Properties"
        ).GetAll("org.bluez.LEAdvertisement1")
        check(
            "advertises the pin's service UUID",
            [str(u) for u in props["ServiceUUIDs"]] == [core.service_uuid(PIN)],
        )

    @dbus.service.method("org.bluez.LEAdvertisingManager1", in_signature="o")
    def UnregisterAdvertisement(self, path):
        Adapter.unregistered.append("advert")


class Device(dbus.service.Object):
    @dbus.service.signal("org.freedesktop.DBus.Properties", signature="sa{sv}as")
    def PropertiesChanged(self, interface, changed, invalidated):
        pass


Root(bus, "/")
Adapter(bus, ADAPTER)
dev_a = Device(bus, DEV_A)


def frames(sock, wait=1.5):
    out, end = [], time.time() + wait
    sock.settimeout(0.2)
    while time.time() < end:
        try:
            out.append(core.Frame.decode(sock.recv(600)))
        except TimeoutError:
            pass
    return out


def scenario(sender, app):
    try:
        objects = dbus.Interface(
            bus.get_object(sender, app), "org.freedesktop.DBus.ObjectManager"
        ).GetManagedObjects()
        by_uuid = {}
        for path, ifaces in objects.items():
            for _iface, props in ifaces.items():
                by_uuid[str(props["UUID"])] = (str(path), props)
        service = by_uuid.get(core.service_uuid(PIN))
        check(
            "primary service named after the pin",
            service is not None and bool(service[1]["Primary"]),
        )
        rx_path, rx_props = by_uuid[core.RX_UUID]
        tx_path, tx_props = by_uuid[core.TX_UUID]
        check(
            "rx is write, tx is notify",
            list(rx_props["Flags"]) == ["write"] and list(tx_props["Flags"]) == ["notify"],
        )
        rx = dbus.Interface(bus.get_object(sender, rx_path), "org.bluez.GattCharacteristic1")
        tx = dbus.Interface(bus.get_object(sender, tx_path), "org.bluez.GattCharacteristic1")
        fd, mtu = tx.AcquireNotify({"device": dbus.ObjectPath(DEV_A), "mtu": dbus.UInt16(185)})
        notes = socket.socket(fileno=fd.take())
        a = {"device": dbus.ObjectPath(DEV_A), "mtu": dbus.UInt16(185)}
        rx.WriteValue(dbus.ByteArray(bytes([1, 1, 0, 1])), a)
        got = frames(notes)
        check(
            "phone A: OPENED, then the hub's bytes",
            got[:2] == [core.Frame.opened(1), core.Frame.data(1, 0, b"HELLO-FROM-HUB")],
        )
        rx.WriteValue(dbus.ByteArray(core.Frame.data(1, 0, b"ping").encode()), a)
        got = frames(notes)
        check(
            "phone A's bytes reach the hub and are credited",
            hub.got[0] == b"ping" and core.Frame.credit(1, 4) in got,
        )
        rx.WriteValue(
            dbus.ByteArray(bytes([1, 1, 0, 1])),
            {"device": dbus.ObjectPath(DEV_B), "mtu": dbus.UInt16(185)},
        )
        got = frames(notes)
        check("phone B is not served while A is", got == [] and len(hub.conns) == 1)
        dev_a.PropertiesChanged("org.bluez.Device1", {"Connected": dbus.Boolean(False)}, [])
        time.sleep(0.5)
        check("A disconnecting closes its hub connection", hub.eof[0])
        rx.WriteValue(
            dbus.ByteArray(bytes([1, 1, 0, 1])),
            {"device": dbus.ObjectPath(DEV_B), "mtu": dbus.UInt16(185)},
        )
        got = frames(notes)
        check("then phone B is served", len(hub.conns) == 2 and got[:1] == [core.Frame.opened(1)])
        try:
            rx.WriteValue(dbus.ByteArray(bytes(600)), {"device": dbus.ObjectPath(DEV_B)})
            check("an oversized write is refused", False)
        except dbus.exceptions.DBusException as e:
            check(
                "an oversized write is refused",
                e.get_dbus_name() == "org.bluez.Error.InvalidValueLength",
            )
    except Exception as e:  # noqa: BLE001
        check(f"scenario raised {e!r}", False)
    loop.quit()


GLib.timeout_add_seconds(40, lambda: (check("finished in time", False), loop.quit()))
loop.run()
print(f"RESULT {sum(results)}/{len(results)}", flush=True)
sys.exit(0 if results and all(results) else 1)
