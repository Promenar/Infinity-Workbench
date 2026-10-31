#!/usr/bin/env python3
"""验证构建输入的 HTTP 短暂错误重试，不访问外部网络。"""
import http.server
import pathlib
import subprocess
import tempfile
import threading
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
SOURCE = ROOT / "native-ui/buildSrc/src/main/java/com/promenar/infinity/buildlogic/RetryingHttpFetch.java"
HARNESS = r"""
package com.promenar.infinity.buildlogic;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
public class RetryHarness {
    public static void main(String[] args) throws Exception {
        var response = RetryingHttpFetch.send(HttpClient.newHttpClient(),
                HttpRequest.newBuilder(URI.create(args[0])).build(), 3, Duration.ZERO, message -> {});
        try (var body = response.body()) {
            System.out.print(response.statusCode() + ":" + new String(body.readAllBytes()));
        }
    }
}
"""

class RetryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        harness = pathlib.Path(cls.temp.name) / "RetryHarness.java"
        harness.write_text(HARNESS, encoding="utf-8")
        subprocess.run(["javac", "--release", "17", "-d", cls.temp.name, str(SOURCE), str(harness)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_server(self, statuses):
        requests = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                status = statuses[min(len(requests), len(statuses) - 1)]
                requests.append(status)
                if status == 0:
                    self.close_connection = True
                    return
                self.send_response(status)
                self.end_headers()
                self.wfile.write(b"payload")
            def log_message(self, *_):
                pass
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = subprocess.run(["java", "-cp", self.temp.name,
                "com.promenar.infinity.buildlogic.RetryHarness",
                f"http://127.0.0.1:{server.server_port}/input"],
                check=True, capture_output=True, text=True, timeout=15)
            return requests, result.stdout
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_transient_504_then_success(self):
        requests, result = self.run_server([504, 504, 200])
        self.assertEqual(requests, [504, 504, 200])
        self.assertEqual(result, "200:payload")

    def test_permanent_client_error_is_not_retried(self):
        requests, result = self.run_server([404])
        self.assertEqual(requests, [404])
        self.assertEqual(result, "404:payload")

    def test_retries_are_bounded(self):
        requests, result = self.run_server([503])
        self.assertEqual(requests, [503, 503, 503])
        self.assertEqual(result, "503:payload")

    def test_rate_limit_and_timeout_are_retryable(self):
        requests, result = self.run_server([429, 408, 200])
        self.assertEqual(requests, [429, 408, 200])
        self.assertEqual(result, "200:payload")

    def test_connection_failure_then_success(self):
        requests, result = self.run_server([0, 0, 200])
        self.assertEqual(requests, [0, 0, 200])
        self.assertEqual(result, "200:payload")

if __name__ == "__main__":
    unittest.main()
