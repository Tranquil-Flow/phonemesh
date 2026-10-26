#!/bin/sh
# In a Debian/Raspberry Pi OS-like container: docker run --rm -v "$PWD":/work debian:bookworm-slim sh /work/tests/run_fake_bluez.sh
set -e
apt-get update -qq >/dev/null && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 python3-dbus python3-gi dbus >/dev/null
cd /work
PM_GATT_VECTORS="${PM_GATT_VECTORS:-}" python3 -m unittest discover -s tests 2>&1 | tail -3
export DBUS_SYSTEM_BUS_ADDRESS="$(dbus-daemon --session --fork --print-address | head -1)"
PIN=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE
python3 tests/fake_bluez.py "$PIN" 18443 /work > /tmp/fake.log 2>&1 &
FAKE=$!
sleep 1
python3 -m phonemesh_bridge --pin "$PIN" --port 18443 2> /tmp/bridge.log &
BRIDGE=$!
wait $FAKE || true
kill -TERM $BRIDGE; sleep 1
cat /tmp/fake.log
echo "--- bridge log"; cat /tmp/bridge.log
python3 -m phonemesh_bridge --pin short --port 1 2>/dev/null || echo "bad pin exit: $?"
