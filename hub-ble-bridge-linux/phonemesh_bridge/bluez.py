"""BlueZ glue for the Linux bridge: one GATT service (rx write, tx notify) named after the hub's
pin, an LE
advertisement, and one loopback TCP connection to the hub's HTTPS port per stream. Uses the system's
dbus-python and
PyGObject (Debian/Raspberry Pi OS: python3-dbus, python3-gi), the stack of BlueZ's own examples.

BlueZ sends a notification to every subscribed phone, not to one, so this host serves one phone at a
time
(Bridge(max_centrals=1)); another phone's writes are dropped until the first one disconnects.
"""

import errno
import socket
import sys

import dbus
import dbus.exceptions
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

from . import core

BLUEZ = "org.bluez"
OM = "org.freedesktop.DBus.ObjectManager"
PROPS = "org.freedesktop.DBus.Properties"
ADAPTER = "org.bluez.Adapter1"
DEVICE = "org.bluez.Device1"
GATT_MANAGER = "org.bluez.GattManager1"
AD_MANAGER = "org.bluez.LEAdvertisingManager1"
SERVICE = "org.bluez.GattService1"
CHRC = "org.bluez.GattCharacteristic1"
ADVERT = "org.bluez.LEAdvertisement1"
ROOT = "/org/phonemesh/bridge"

EXIT_USAGE, EXIT_UNAVAILABLE, EXIT_NOPERM = 64, 69, 77


def log(text):
    print("phonemesh-ble-bridge: " + text, file=sys.stderr, flush=True)


class Failed(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.Failed"


class NotPermitted(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.NotPermitted"


class InvalidLength(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.InvalidValueLength"


class NotSupported(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.NotSupported"


class Loopback:
    """One non-blocking TCP connection to 127.0.0.1:<port>, driven by GLib watches. Carries opaque
    bytes only.
    """

    def __init__(self, port, events):
        self.events, self.sock, self.watches, self.out, self.dones = events, None, [], b"", []
        self.want, self.connected, self.closed = 0, False, False
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.setblocking(False)
            err = self.sock.connect_ex(("127.0.0.1", port))
        except OSError:
            err = errno.ECONNREFUSED
        if err not in (0, errno.EINPROGRESS, errno.EWOULDBLOCK):
            self._fail_soon()
            return
        self._watch(GLib.IO_OUT | GLib.IO_ERR | GLib.IO_HUP, self._on_connect)

    def _fail_soon(self):
        def fire():
            self._finish(error=True)
            return False

        GLib.idle_add(fire)

    def _watch(self, cond, fn):
        self.watches.append(GLib.io_add_watch(self.sock.fileno(), cond, fn))

    def _on_connect(self, fd, cond):
        if self.closed:
            return False
        if self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR) != 0 or cond & (
            GLib.IO_ERR | GLib.IO_HUP
        ):
            self._finish(error=True)
            return False
        self.connected = True
        self.events.upstream_connected()
        return False

    def read(self, n):
        if self.closed:
            return
        self.want = n
        self._watch(GLib.IO_IN | GLib.IO_ERR | GLib.IO_HUP, self._on_readable)

    def _on_readable(self, fd, cond):
        if self.closed or not self.want:
            return False
        try:
            data = self.sock.recv(self.want)
        except BlockingIOError:
            return True
        except OSError:
            self._finish(error=True)
            return False
        self.want = 0
        if not data:
            self._finish(error=False)
        else:
            self.events.upstream_data(data)
        return False

    def write(self, data, done):
        if self.closed:
            done(False)
            return
        start = not self.out
        self.out += bytes(data)
        self.dones.append((len(self.out), done))
        if start:
            self._watch(GLib.IO_OUT | GLib.IO_ERR | GLib.IO_HUP, self._on_writable)

    def _on_writable(self, fd, cond):
        if self.closed:
            return False
        try:
            sent = self.sock.send(self.out)
        except BlockingIOError:
            return True
        except OSError:
            self._finish(error=True)
            return False
        self.out = self.out[sent:]
        self.dones = [(end - sent, d) for end, d in self.dones]
        while self.dones and self.dones[0][0] <= 0:
            self.dones.pop(0)[1](True)
        return bool(self.out) and not self.closed

    def _finish(self, error):
        if self.closed:
            return
        self.close()
        self.events.upstream_closed(error)

    def close(self):
        if self.closed:
            return
        self.closed = True
        for w in self.watches:
            GLib.source_remove(w) if GLib.MainContext.default().find_source_by_id(w) else None
        self.watches = []
        pending, self.dones = self.dones, []
        if self.sock is not None:
            self.sock.close()
        for _, done in pending:
            done(False)


class Application(dbus.service.Object):
    def __init__(self, bus, host):
        self.host = host
        super().__init__(bus, ROOT)

    @dbus.service.method(OM, out_signature="a{oa{sa{sv}}}")
    def GetManagedObjects(self):
        return self.host.managed_objects()


class Service(dbus.service.Object):
    def __init__(self, bus, path, uuid):
        self.uuid = uuid
        super().__init__(bus, path)

    def props(self):
        return {SERVICE: {"UUID": self.uuid, "Primary": dbus.Boolean(True)}}

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        return self.props().get(interface, {})


class Rx(dbus.service.Object):
    """The phone writes one frame per write (with response)."""

    def __init__(self, bus, path, service, host):
        self.service, self.host = service, host
        super().__init__(bus, path)

    def props(self):
        return {
            CHRC: {
                "UUID": core.RX_UUID,
                "Service": dbus.ObjectPath(self.service),
                "Flags": dbus.Array(["write"], signature="s"),
            }
        }

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        return self.props().get(interface, {})

    @dbus.service.method(CHRC, in_signature="aya{sv}")
    def WriteValue(self, value, options):
        if int(options.get("offset", 0)) != 0:
            raise Failed("offset")
        if len(value) > core.MAX_FRAME:
            raise InvalidLength("frame too long")
        device = str(options.get("device", ""))
        if not device:
            raise NotPermitted("no device")
        self.host.phone_wrote(device, bytes(value), int(options.get("mtu", 0)))

    @dbus.service.method(CHRC, in_signature="a{sv}", out_signature="ay")
    def ReadValue(self, options):
        raise NotPermitted("not readable")


class Tx(dbus.service.Object):
    """Notifications go out through the socket BlueZ hands over in AcquireNotify: a full socket is a
    full radio queue.
    """

    def __init__(self, bus, path, service, host):
        self.service, self.host = service, host
        super().__init__(bus, path)

    def props(self):
        return {
            CHRC: {
                "UUID": core.TX_UUID,
                "Service": dbus.ObjectPath(self.service),
                "Flags": dbus.Array(["notify"], signature="s"),
                "NotifyAcquired": dbus.Boolean(self.host.notify_fd is not None),
            }
        }

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        return self.props().get(interface, {})

    @dbus.service.method(CHRC, in_signature="a{sv}", out_signature="hq")
    def AcquireNotify(self, options):
        mtu = int(options.get("mtu", 23))
        a, b = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        self.host.notify_acquired(a, str(options.get("device", "")), mtu)
        fd = dbus.types.UnixFd(b)
        b.close()
        return fd, dbus.UInt16(mtu)

    @dbus.service.method(CHRC)
    def StartNotify(self):
        raise NotSupported("use AcquireNotify")

    @dbus.service.method(CHRC)
    def StopNotify(self):
        pass

    @dbus.service.method(CHRC, in_signature="a{sv}", out_signature="ay")
    def ReadValue(self, options):
        raise NotPermitted("not readable")


class Advertisement(dbus.service.Object):
    def __init__(self, bus, path, uuid):
        self.uuid = uuid
        super().__init__(bus, path)

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != ADVERT:
            return {}
        return {"Type": "peripheral", "ServiceUUIDs": dbus.Array([self.uuid], signature="s")}

    @dbus.service.method(ADVERT)
    def Release(self):
        log("BlueZ released the advertisement")


class Host:
    """BridgeHost for BlueZ: the bridge core plus the objects, sockets and signals around it."""

    def __init__(self, bus, pin, port):
        self.bus, self.port = bus, port
        self.uuid = core.service_uuid(pin)
        self.bridge = core.Bridge(self, max_centrals=1)
        self.notify_sock, self.notify_fd, self.notify_mtu, self.mtus = None, None, 23, {}
        self.out_watch = None
        self.adapter = None
        self.app = Application(bus, self)
        self.service = Service(bus, ROOT + "/service0", self.uuid)
        self.rx = Rx(bus, ROOT + "/service0/char0", ROOT + "/service0", self)
        self.tx = Tx(bus, ROOT + "/service0/char1", ROOT + "/service0", self)
        self.advert = Advertisement(bus, ROOT + "/advert0", self.uuid)
        bus.add_signal_receiver(
            self._props_changed,
            dbus_interface=PROPS,
            signal_name="PropertiesChanged",
            bus_name=BLUEZ,
            path_keyword="path",
        )

    def managed_objects(self):
        out = {}
        for obj in (self.service, self.rx, self.tx):
            out[dbus.ObjectPath(obj.__dbus_object_path__)] = obj.props()
        return out

    # BlueZ setup

    def find_adapter(self):
        objects = dbus.Interface(self.bus.get_object(BLUEZ, "/"), OM).GetManagedObjects()
        for path, ifaces in objects.items():
            if GATT_MANAGER in ifaces and AD_MANAGER in ifaces:
                return str(path), bool(ifaces.get(ADAPTER, {}).get("Powered", False))
        return None, False

    def register(self, adapter):
        self.adapter = adapter
        obj = self.bus.get_object(BLUEZ, adapter)
        dbus.Interface(obj, GATT_MANAGER).RegisterApplication(
            ROOT,
            {},
            reply_handler=lambda: log(f"GATT service {self.uuid} registered"),
            error_handler=lambda e: self._fatal(f"could not register the GATT service: {e}"),
        )
        dbus.Interface(obj, AD_MANAGER).RegisterAdvertisement(
            ROOT + "/advert0",
            {},
            reply_handler=lambda: log(f"advertising; hub at 127.0.0.1:{self.port}"),
            error_handler=lambda e: self._fatal(f"could not advertise: {e}"),
        )

    def unregister(self):
        """Best effort: BlueZ may already be gone (stopped or restarted), which drops our
        registrations anyway.
        """
        if not self.adapter:
            return
        try:
            obj = self.bus.get_object(BLUEZ, self.adapter, introspect=False)
        except dbus.exceptions.DBusException:
            return
        for iface, method, arg in (
            (AD_MANAGER, "UnregisterAdvertisement", ROOT + "/advert0"),
            (GATT_MANAGER, "UnregisterApplication", ROOT),
        ):
            try:
                getattr(dbus.Interface(obj, iface), method)(arg, timeout=3)
            except dbus.exceptions.DBusException:
                pass

    def _fatal(self, text):
        log(text)
        self.exit_code = EXIT_UNAVAILABLE
        if self.loop is not None:
            self.loop.quit()

    exit_code = 0
    loop = None

    # phones

    def phone_wrote(self, device, value, mtu):
        if mtu:
            self.mtus[device] = mtu
        self.bridge.received(device, value)

    def notify_acquired(self, sock, device, mtu):
        self._drop_notify()
        sock.setblocking(False)
        self.notify_sock, self.notify_fd, self.notify_mtu = sock, sock.fileno(), mtu
        if device:
            self.mtus.setdefault(device, mtu)
        GLib.io_add_watch(self.notify_fd, GLib.IO_HUP | GLib.IO_ERR, self._notify_gone)
        log("a phone subscribed")

    def _notify_gone(self, fd, cond):
        if fd == self.notify_fd:
            self._drop_notify()
            for central in list(self.bridge.centrals):
                self.bridge.central_gone(central)
            log("no phone subscribed")
        return False

    def _drop_notify(self):
        if self.out_watch is not None:
            GLib.source_remove(self.out_watch)
            self.out_watch = None
        if self.notify_sock is not None:
            self.notify_sock.close()
        self.notify_sock, self.notify_fd = None, None

    def _props_changed(self, interface, changed, invalidated, path=None):
        if interface == DEVICE and "Connected" in changed and not changed["Connected"]:
            self.mtus.pop(str(path), None)
            self.bridge.central_gone(str(path))
        if interface == ADAPTER and path == self.adapter and "Powered" in changed:
            log("Bluetooth is " + ("on" if changed["Powered"] else "off"))

    # BridgeHost

    def connect_upstream(self, lane):
        return Loopback(self.port, lane)

    def notify(self, central, frame):
        if self.notify_sock is None:
            return (
                True  # nobody subscribed: as on the Mac, a frame for a phone that left is consumed
            )
        try:
            self.notify_sock.send(frame)
            return True
        except BlockingIOError:
            if self.out_watch is None:
                self.out_watch = GLib.io_add_watch(self.notify_fd, GLib.IO_OUT, self._writable)
            return False
        except OSError:
            self._drop_notify()
            return True

    def _writable(self, fd, cond):
        self.out_watch = None
        for central in list(self.bridge.centrals):
            self.bridge.ready(central)
        return False

    def max_frame(self, central):
        return min(self.mtus.get(central, self.notify_mtu), self.notify_mtu) - 3

    def tick(self):
        self.bridge.tick()
        return True
