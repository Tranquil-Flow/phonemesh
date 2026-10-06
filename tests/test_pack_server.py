import http.client
import importlib.util
import json
import threading
import urllib.request
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "android/scripts/test_pack_server.py"
spec = importlib.util.spec_from_file_location("test_pack_server", SCRIPT)
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)

PAYLOAD = bytes(range(256)) * 40


@pytest.fixture
def serve(tmp_path):
    (tmp_path / "files").mkdir()
    (tmp_path / "files" / "pack.zip").write_bytes(PAYLOAD)
    started = []

    def start(drop_after=None):
        log = tmp_path / "server.jsonl"
        server = srv.make_server(tmp_path / "files", 0, drop_after, log)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        started.append(server)
        return f"http://127.0.0.1:{server.server_address[1]}/pack.zip", log

    yield start
    for server in started:
        server.shutdown()
        server.server_close()


def test_full_get_returns_exact_bytes_and_an_etag(serve):
    url, _ = serve()
    with urllib.request.urlopen(url) as response:
        assert response.status == 200
        assert response.read() == PAYLOAD
        assert response.headers["ETag"].startswith('"') and len(response.headers["ETag"]) == 66
        assert response.headers["Accept-Ranges"] == "bytes"


def test_range_request_returns_206_and_the_tail(serve):
    url, _ = serve()
    request = urllib.request.Request(url, headers={"Range": "bytes=5-"})
    with urllib.request.urlopen(request) as response:
        assert response.status == 206
        assert response.read() == PAYLOAD[5:]
        assert response.headers["Content-Range"] == f"bytes 5-{len(PAYLOAD) - 1}/{len(PAYLOAD)}"


def test_first_get_drops_then_range_resume_completes_and_is_logged(serve):
    url, log = serve(drop_after=10)
    with pytest.raises(http.client.IncompleteRead) as dropped:
        with urllib.request.urlopen(url) as response:
            response.read()
    received = dropped.value.partial
    assert len(received) == 10
    request = urllib.request.Request(url, headers={"Range": "bytes=10-"})
    with urllib.request.urlopen(request) as response:
        assert response.status == 206
        assert received + response.read() == PAYLOAD
    entries = [json.loads(line) for line in log.read_text().splitlines()]
    assert any(entry["range"] == "bytes=10-" for entry in entries)
    assert entries[0]["range"] is None
