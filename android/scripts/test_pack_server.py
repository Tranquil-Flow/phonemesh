"""Local pack server for emulator download tests (reached from the emulator at 10.0.2.2:8777).

Serves files from one directory with ETag and `Range: bytes=N-` support. With --drop-after N the
first full (non-Range) GET of each file closes the connection after N body bytes, so a client must
resume. Every request is logged to --log as one JSON line.
"""

import argparse
import hashlib
import json
import re
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

RANGE = re.compile(r"^bytes=(\d+)-$")
CHUNK = 64 * 1024


def make_server(directory, port, drop_after=None, log_path=None, host="0.0.0.0"):
    root = Path(directory).resolve()
    lock = threading.Lock()
    dropped: set[str] = set()
    etags: dict[str, str] = {}

    def log(entry):
        if log_path is None:
            return
        with lock, open(log_path, "a") as out:
            out.write(json.dumps(entry) + "\n")

    def etag(path: Path) -> str:
        with lock:
            if str(path) not in etags:
                digest = hashlib.sha256()
                with open(path, "rb") as f:
                    while block := f.read(1 << 20):
                        digest.update(block)
                etags[str(path)] = f'"{digest.hexdigest()}"'
            return etags[str(path)]

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format, *args):  # noqa: A002 - BaseHTTPRequestHandler signature
            pass

        def resolve(self):
            name = self.path.split("?", 1)[0].lstrip("/")
            if not name or "/" in name or name.startswith("."):
                return None
            path = root / name
            return path if path.is_file() else None

        def serve(self, body: bool):
            requested = self.headers.get("Range")
            path = self.resolve()
            entry = {"time": time.time(), "method": self.command, "path": self.path}
            entry["range"] = requested
            if path is None:
                log({**entry, "status": 404})
                self.send_error(404)
                return
            size = path.stat().st_size
            start = 0
            if requested is not None:
                match = RANGE.match(requested)
                if not match or int(match.group(1)) >= size:
                    log({**entry, "status": 416})
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                start = int(match.group(1))
            drop = None
            if body and requested is None and drop_after is not None:
                with lock:
                    if path.name not in dropped:
                        dropped.add(path.name)
                        drop = drop_after
            status = 206 if requested is not None else 200
            log({**entry, "status": status, "drop_after": drop})
            self.send_response(status)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(size - start))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("ETag", etag(path))
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{size - 1}/{size}")
            self.end_headers()
            if not body:
                return
            sent = 0
            with open(path, "rb") as f:
                f.seek(start)
                while block := f.read(CHUNK):
                    if drop is not None and sent + len(block) > drop:
                        self.wfile.write(block[: drop - sent])
                        self.wfile.flush()
                        self.close_connection = True
                        self.connection.shutdown(socket.SHUT_RDWR)
                        return
                    self.wfile.write(block)
                    sent += len(block)

        def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler naming
            self.serve(body=True)

        def do_HEAD(self):  # noqa: N802 - BaseHTTPRequestHandler naming
            self.serve(body=False)

    return ThreadingHTTPServer((host, port), Handler)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dir", required=True, type=Path)
    # 8777 matches the debug PACK_BASE_URL (8765 is taken by another service on the dev host).
    p.add_argument("--port", default=8777, type=int)
    p.add_argument("--drop-after", type=int)
    p.add_argument("--log", type=Path)
    a = p.parse_args()
    server = make_server(a.dir, a.port, a.drop_after, a.log)
    print(f"serving {a.dir} on port {server.server_address[1]}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
