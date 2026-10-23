"""Negative and positive controls for the hub network boundary (accounts step 16).

Run: python3 -I android/scripts/test_check_network_boundary_hub.py
"""

import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check_network_boundary.py"
spec = importlib.util.spec_from_file_location("check_network_boundary", SCRIPT)
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)

HUB_CLIENT = "Lorg/phonemesh/core/hub/HubClient;"
TLS_ENGINE = "Lorg/phonemesh/core/hub/HubEngineTls;"
WIFI_LINK = "Lorg/phonemesh/app/WifiHubLink;"
SOCKET = "Ljava/net/Socket;"
SSL = "Ljavax/net/ssl/SSLSocket;"
FACTORY = "Ljavax/net/SocketFactory;"


def dump(*uses):
    lines = []
    for descriptor, api in uses:
        lines.append(f"  Class descriptor  : '{descriptor}'")
        lines.append(f"      invoke-virtual {{v0}}, {api}.call:()V")
    return "\n".join(lines)


class HubBoundaryTest(unittest.TestCase):
    def found(self, *uses):
        return boundary.violations(dump(*uses), boundary.ALLOWED_CALLERS, set())

    def test_exactly_four_callers_may_use_the_network(self):
        self.assertEqual(
            {"Lorg/phonemesh/app/PackDownloads;", HUB_CLIENT, TLS_ENGINE, WIFI_LINK},
            set(boundary.ALLOWED_CALLERS),
        )

    def test_the_hub_client_its_inner_classes_and_the_wifi_link_are_allowed(self):
        self.assertEqual(
            [],
            self.found(
                (TLS_ENGINE, SSL),
                (HUB_CLIENT, SOCKET),
                (HUB_CLIENT, SSL),
                (HUB_CLIENT, FACTORY),
                ("Lorg/phonemesh/core/hub/HubClient$Exchange;", SSL),
                ("Lorg/phonemesh/core/hub/HubClient$Companion;", SOCKET),
                (WIFI_LINK, FACTORY),
                ("Lorg/phonemesh/app/WifiHubLink$request$next$1;", FACTORY),
                ("Lorg/phonemesh/app/PackDownloads;", SOCKET),
            ),
        )

    def test_everything_else_is_still_a_violation(self):
        for caller in [
            "Lorg/phonemesh/core/hub/HubClientEvil;",  # a name that merely starts the same
            "Lorg/phonemesh/core/hub/HubEngineTlsEvil;",
            "Lorg/phonemesh/core/hub/HubDelivery;",
            "Lorg/phonemesh/core/hub/HubCard;",
            "Lorg/phonemesh/core/hub/HubSession;",
            "Lorg/phonemesh/app/HubLink;",
            "Lorg/phonemesh/app/HubCursor;",
            "Lorg/phonemesh/app/AppCore;",
            "Lorg/phonemesh/app/WifiHubLinkTwo;",
            "Lorg/evil/core/hub/HubClient;",
        ]:
            for api in (SOCKET, SSL, FACTORY):
                pattern = "Ljavax/net/ssl/" if api == SSL else api
                self.assertEqual([(caller, pattern)], self.found((caller, api)), caller)

    def test_the_scan_is_not_blind_to_the_hub_classes(self):
        seen = dump((HUB_CLIENT, SOCKET), (WIFI_LINK, FACTORY))
        boundary.check_scanned(seen, [HUB_CLIENT, WIFI_LINK])
        boundary.check_positive_control(seen, [HUB_CLIENT, WIFI_LINK])

    def test_a_hub_class_with_no_network_call_fails_the_positive_control(self):
        quiet = f"  Class descriptor  : '{WIFI_LINK}'\n      return-void"
        with self.assertRaises(boundary.ScanError):
            boundary.check_positive_control(quiet, [WIFI_LINK])

    def test_a_missing_hub_class_fails_the_scan(self):
        with self.assertRaises(boundary.ScanError):
            boundary.check_scanned(dump((HUB_CLIENT, SOCKET)), [HUB_CLIENT, WIFI_LINK])


if __name__ == "__main__":
    unittest.main()
