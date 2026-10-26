"""python3 -m phonemesh_bridge --pin <43-character card pin> --port <hub HTTPS port>"""

import signal
import sys

from . import core

USAGE = "usage: python3 -m phonemesh_bridge --pin <hub card pin> --port <hub HTTPS port>"


def parse(argv):
    """(pin, port) or a usage message. Both options are required; there is no host option and no
    default port.
    """
    pin = port = None
    args = list(argv)
    while args:
        name = args.pop(0)
        if name in ("-h", "--help"):
            return None, USAGE
        if name not in ("--pin", "--port") or not args:
            return None, USAGE
        value = args.pop(0)
        if name == "--pin":
            if not core.is_pin(value):
                return None, "the pin must be the 43-character pin from the hub card"
            pin = value
        else:
            if not value.isdigit() or not 1 <= int(value) <= 65535:
                return None, "the port must be a number from 1 to 65535"
            port = int(value)
    if pin is None or port is None:
        return None, USAGE
    return (pin, port), None


def main(argv=None):
    parsed, problem = parse(sys.argv[1:] if argv is None else argv)
    if problem:
        print(problem, file=sys.stderr)
        return 0 if problem == USAGE and argv and argv[0] in ("-h", "--help") else 64
    pin, port = parsed
    try:
        import dbus
        import dbus.mainloop.glib
        from gi.repository import GLib

        from . import bluez
    except ImportError as e:
        print(
            f"needs python3-dbus and python3-gi (apt install python3-dbus python3-gi): {e}",
            file=sys.stderr,
        )
        return 69
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    try:
        bus = dbus.SystemBus()
        host = bluez.Host(bus, pin, port)
        adapter, powered = host.find_adapter()
    except dbus.exceptions.DBusException as e:
        denied = "AccessDenied" in e.get_dbus_name() if e.get_dbus_name() else False
        bluez.log(f"cannot reach BlueZ: {e}")
        return bluez.EXIT_NOPERM if denied else bluez.EXIT_UNAVAILABLE
    if adapter is None:
        bluez.log(
            "no Bluetooth adapter that can advertise (needs BlueZ 5.50 or newer with LE support)"
        )
        return bluez.EXIT_UNAVAILABLE
    loop = GLib.MainLoop()
    host.loop = loop
    if not powered:
        bluez.log("Bluetooth is off; turn it on (for example: bluetoothctl power on)")
    host.register(adapter)
    GLib.timeout_add_seconds(1, host.tick)

    def stop():
        bluez.log("stopping")
        loop.quit()
        return False

    for sig in (signal.SIGINT, signal.SIGTERM):
        GLib.unix_signal_add(GLib.PRIORITY_HIGH, sig, stop)
    loop.run()
    for central in list(host.bridge.centrals):
        host.bridge.central_gone(central)
    host.unregister()
    return host.exit_code


if __name__ == "__main__":
    sys.exit(main())
