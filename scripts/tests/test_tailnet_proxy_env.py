"""内置 Tailscale 子进程代理变量的主机侧验证。

App 为 shell_exec / python_exec 注入 https_proxy、http_proxy、all_proxy（凭据写在 URL 中）与 no_proxy
（见 TailnetProxies.kt 的 TailnetProxyConfig.environment）。本测试用 127.0.0.1 上需要 Basic 凭据的假代理确认：
Python urllib 与内置 git 使用的 dulwich（urllib3）都读取这些变量、带上 Proxy-Authorization、以域名形式
把 tailnet 目标交给代理（HTTPS 走 CONNECT），而 no_proxy 中的本机地址直接连接。

dulwich 部分通过环境变量 NEXARA_DULWICH_PATH（解压后的 dulwich 与 urllib3 wheel 所在目录）或已安装的
dulwich 导入，导入失败时跳过。不访问网络。
"""

from __future__ import annotations

import base64
import os
from pathlib import Path
import socket
import sys
import threading
import unittest
from unittest import mock
import urllib.error
import urllib.request

USER = "UserUserUserUserUser0001"
PASSWORD = "PassPassPassPassPass0001"
EXPECTED_AUTH = "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()


class _Recorder:
    """只读请求头的最小 HTTP 服务：proxy=True 时要求 Proxy-Authorization，否则回复 407。"""

    def __init__(self, proxy: bool) -> None:
        self.proxy = proxy
        self.requests: list[tuple[str, str | None]] = []
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(16)
        self.port = self.server.getsockname()[1]
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def close(self) -> None:
        self.server.close()

    def _serve(self) -> None:
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def _handle(self, conn: socket.socket) -> None:
        with conn:
            reader = conn.makefile("rb")
            while True:
                line = reader.readline()
                if not line:
                    return
                # 只记录方法与目标：不同 Python 版本的 urllib 对 CONNECT 使用 HTTP/1.0 或 HTTP/1.1。
                request_line = " ".join(line.decode("latin-1").rstrip("\r\n").split(" ")[:2])
                auth = None
                while True:
                    header = reader.readline()
                    if header in (b"", b"\r\n", b"\n"):
                        break
                    name, _, value = header.decode("latin-1").partition(":")
                    if name.strip().lower() == "proxy-authorization":
                        auth = value.strip()
                self.requests.append((request_line, auth))
                if self.proxy and auth != EXPECTED_AUTH:
                    conn.sendall(b"HTTP/1.1 407 Proxy Authentication Required\r\n"
                                 b"Proxy-Authenticate: Basic realm=\"nexara-tailnet\"\r\nContent-Length: 0\r\n\r\n")
                    continue
                # CONNECT 与普通请求都以 502 结束：只验证请求如何到达代理。
                conn.sendall(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return


def _environment(port: int) -> dict[str, str]:
    """与 TailnetProxyConfig.environment 相同的变量集合。"""
    authority = f"{USER}:{PASSWORD}@127.0.0.1:{port}"
    http = f"http://{authority}"
    socks = f"socks5h://{authority}"
    no_proxy = "localhost,127.0.0.1,::1"
    return {
        "HTTPS_PROXY": http, "https_proxy": http,
        "HTTP_PROXY": http, "http_proxy": http,
        "ALL_PROXY": socks, "all_proxy": socks,
        "NO_PROXY": no_proxy, "no_proxy": no_proxy,
    }


class _ProxyCase(unittest.TestCase):
    def setUp(self) -> None:
        self.proxy = _Recorder(proxy=True)
        self.local = _Recorder(proxy=False)
        self.addCleanup(self.proxy.close)
        self.addCleanup(self.local.close)
        patcher = mock.patch.dict(os.environ, _environment(self.proxy.port), clear=False)
        patcher.start()
        self.addCleanup(patcher.stop)


class UrllibProxyEnvTest(_ProxyCase):
    def open(self, url: str) -> None:
        opener = urllib.request.build_opener()  # 默认 ProxyHandler 读取环境变量
        try:
            opener.open(url, timeout=5).close()
        except urllib.error.HTTPError as error:
            error.close()
        except (urllib.error.URLError, OSError):
            pass

    def test_http_tailnet_target_goes_to_proxy_with_credentials(self) -> None:
        self.open("http://nas.tail1.ts.net:8080/status")
        self.assertIn(("GET http://nas.tail1.ts.net:8080/status", EXPECTED_AUTH), self.proxy.requests)

    def test_https_target_uses_connect_with_credentials(self) -> None:
        self.open("https://nas.tail1.ts.net/")
        self.assertIn(("CONNECT nas.tail1.ts.net:443", EXPECTED_AUTH), self.proxy.requests)

    def test_no_proxy_loopback_connects_directly(self) -> None:
        self.open(f"http://127.0.0.1:{self.local.port}/local")
        self.assertEqual([line for line, _ in self.local.requests], ["GET /local"])
        self.assertEqual(self.proxy.requests, [])


def _load_dulwich() -> None:
    for entry in reversed([part for part in os.environ.get("NEXARA_DULWICH_PATH", "").split(os.pathsep) if part]):
        if entry not in sys.path:
            sys.path.insert(0, entry)
    import dulwich.client  # noqa: F401


class DulwichProxyEnvTest(_ProxyCase):
    def setUp(self) -> None:
        try:
            _load_dulwich()
        except ImportError as error:
            self.skipTest(f"dulwich 不可用：{error}")
        super().setUp()

    def fetch_refs(self, url: str) -> None:
        from dulwich.client import get_transport_and_path

        client, path = get_transport_and_path(url)
        try:
            client.get_refs(path)
        except Exception:  # noqa: BLE001  只验证请求如何到达代理
            pass

    def test_https_clone_uses_connect_with_credentials(self) -> None:
        self.fetch_refs("https://nas.tail1.ts.net/team/repo.git")
        self.assertIn(("CONNECT nas.tail1.ts.net:443", EXPECTED_AUTH), self.proxy.requests)

    def test_http_remote_goes_to_proxy_with_credentials(self) -> None:
        self.fetch_refs("http://100.64.0.5:3000/team/repo.git")
        lines = [line for line, auth in self.proxy.requests if auth == EXPECTED_AUTH]
        self.assertTrue(any(line.startswith("GET http://100.64.0.5:3000/team/repo.git/info/refs") for line in lines), self.proxy.requests)

    def test_no_proxy_loopback_connects_directly(self) -> None:
        self.fetch_refs(f"http://127.0.0.1:{self.local.port}/repo.git")
        self.assertTrue(self.local.requests, "本机地址应直接连接")
        self.assertTrue(all(line.startswith("GET /repo.git/info/refs") for line, _ in self.local.requests), self.local.requests)
        self.assertEqual(self.proxy.requests, [])


if __name__ == "__main__":
    unittest.main()
