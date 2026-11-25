import http.server
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent
REPORTS = ROOT.parents[1] / 'evidence' / 'browser'


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split('?')[0] not in ('/', '/diagnostic.html'):
            self.send_error(404)
            return
        content = (ROOT / 'diagnostic.html').read_bytes()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_POST(self):
        if self.path != '/report':
            self.send_error(404)
            return
        size = int(self.headers.get('Content-Length', 0))
        if size > 131072:
            self.send_error(413)
            return
        report = json.loads(self.rfile.read(size))
        client = report.get('client', 'unknown')
        if not re.fullmatch(r'[a-z0-9_-]{1,24}', client):
            self.send_error(400)
            return
        report['requestAcceptLanguage'] = self.headers.get('Accept-Language')
        (REPORTS / f'browser-{client}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        self.send_response(204)
        self.end_headers()
        print(f'Saved {client} report', flush=True)

    def log_message(self, *_):
        pass


if __name__ == '__main__':
    print('Local diagnostic server on 127.0.0.1:8765', flush=True)
    http.server.ThreadingHTTPServer(('127.0.0.1', 8765), Handler).serve_forever()
