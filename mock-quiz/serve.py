"""提供 mock 題目頁，並把作答紀錄（POST /result）寫到 result.json。"""
import http.server
import pathlib

ROOT = pathlib.Path(__file__).parent


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        (ROOT / "result.json").write_bytes(body)
        self.send_response(204)
        self.end_headers()


http.server.ThreadingHTTPServer(("127.0.0.1", 8766), Handler).serve_forever()
