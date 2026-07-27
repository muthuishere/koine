#!/usr/bin/env python3
"""Tiny SSE server for koine.stream's cross-host check (src/stream_check.cljc).

Python, not Clojure, on purpose: the thing under test is a *client*, so the
server must not share a runtime with it.

  /sse   3 `data:` events ~150ms apart, then `data: [DONE]`. Also emits a
         comment line and blank-line separators, so a client that mistakes
         either for data is caught. The GAPS are the point — a client that
         buffers the whole body produces identical events and is only
         distinguishable by arrival times.
  /utf8  one `data:` line of 4000 snowmen (12000 bytes), then `[DONE]`.
         Longer than any sane read buffer, so a multi-byte rune is guaranteed
         to straddle a chunk boundary. A client that decodes each raw chunk to
         text before splitting lines corrupts this; one that splits on the 0x0A
         *byte* and decodes whole lines does not.

  python3 test/sse_server.py [port]      # default 8791
"""
import sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

GAP = 0.15
SNOWMEN = "☃" * 4000


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _drain_request_body(self):
        try:
            length = int(self.headers.get("content-length") or 0)
            if length:
                self.rfile.read(length)
        except Exception:
            pass

    def _open_stream(self):
        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.send_header("cache-control", "no-cache")
        self.send_header("connection", "close")
        self.end_headers()

        def w(s):
            self.wfile.write(s.encode("utf-8"))
            self.wfile.flush()

        return w

    def _handle(self):
        self._drain_request_body()
        w = self._open_stream()
        try:
            if self.path.startswith("/utf8"):
                w("data: " + SNOWMEN + "\n\n")
                time.sleep(GAP)
                w("data: [DONE]\n\n")
            else:
                w(": ping\n\n")
                for i in range(3):
                    time.sleep(GAP)
                    w('event: delta\ndata: {"delta":"tok%d"}\n\n' % i)
                time.sleep(GAP)
                w("data: [DONE]\n\n")
        except Exception:
            pass

    do_GET = do_POST = lambda self: self._handle()

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8791
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
