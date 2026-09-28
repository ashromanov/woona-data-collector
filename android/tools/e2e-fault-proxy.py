"""Loopback-only fault proxy for the isolated physical-phone sync tests."""
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time


class Proxy(BaseHTTPRequestHandler):
    fault = None

    def log_message(self, *args):
        pass

    def handle_request(self):
        if self.path.startswith('/__fault/'):
            Proxy.fault = self.path.rsplit('/', 1)[-1]
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'OK')
            return
        fault, Proxy.fault = Proxy.fault, None
        if fault == '503':
            self.send_response(503)
            self.end_headers()
            self.wfile.write(b'{"code":"intentional_e2e_fault"}')
            return
        if fault == 'delay':
            time.sleep(2)
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        connection = HTTPConnection('127.0.0.1', 18088, timeout=30)
        try:
            connection.request(self.command, self.path, body, dict(self.headers))
            result = connection.getresponse()
            self.send_response(result.status)
            for key, value in result.getheaders():
                if (key.lower() == 'etag' and self.command == 'GET'
                        and '/artifacts/' in self.path
                        and self.headers.get('Accept-Encoding') != 'identity'):
                    value = '"' + value.strip('"') + '-gzip"'
                if key.lower() not in ('connection', 'transfer-encoding'):
                    self.send_header(key, value)
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(result.read())
        except (BrokenPipeError, ConnectionResetError):
            pass  # Expected when the phone's read timeout closes the connection.
        finally:
            connection.close()

    do_GET = do_HEAD = do_PUT = do_PATCH = do_POST = handle_request


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 18089), Proxy).serve_forever()
