#!/usr/bin/env python3
"""API 36 外部 adb UI tree 测试；避免 instrumentation EmptyActivity 干扰前台窗口。"""

import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.promenar.nexara.native.debug"
COMPONENT = f"{PACKAGE}/com.promenar.nexara.StartupGateTestActivity"
RECOVERING = {"Safe recovery in progress", "正在安全恢复数据"}
BLOCKED = {"Safe recovery needs attention", "安全恢复需要处理"}
RETRY = {"Retry safe recovery", "重试安全恢复"}
READY_PREFIX = "STARTUP_READY_MARKER_WRITERS_"
RETRY_RECOVERY_MILLIS = 5_000


class StartupGateDeviceTest:
    def __init__(self, serial: str, apk: Path):
        self.serial = serial
        self.apk = apk

    def adb(self, *args: str, timeout: int = 20) -> str:
        return subprocess.run(
            ["adb", "-s", self.serial, *args],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        ).stdout

    def install(self) -> None:
        self.adb("install", "-r", str(self.apk), timeout=60)
        self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.adb("shell", "wm", "dismiss-keyguard")

    def launch(self, mode: str | None = None, retry_delay_ms: int | None = None) -> None:
        self.adb("shell", "am", "force-stop", PACKAGE)
        command = ["shell", "am", "start", "-W", "-n", COMPONENT]
        if mode:
            command += ["--es", "startup_mode", mode]
        if retry_delay_ms is not None:
            command += ["--el", "retry_recovery_millis", str(retry_delay_ms)]
        self.adb(*command)

    def tree(self) -> ET.Element:
        output = self.adb("exec-out", "uiautomator", "dump", "/dev/tty")
        xml_start = output.find("<?xml")
        if xml_start < 0:
            raise AssertionError(f"无法读取 UI tree: {output[-300:]}")
        xml_end = output.find("</hierarchy>", xml_start)
        if xml_end < 0:
            raise AssertionError(f"UI tree XML 不完整: {output[-300:]}")
        return ET.fromstring(output[xml_start : xml_end + len("</hierarchy>")])

    @staticmethod
    def nodes(root: ET.Element):
        return list(root.iter("node"))

    def wait_tree(self, predicate, timeout: float = 6.0) -> ET.Element:
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            last = self.tree()
            if predicate(last):
                return last
            time.sleep(0.1)
        visible = [n.attrib.get("text", "") for n in self.nodes(last)] if last is not None else []
        raise AssertionError(f"等待 UI tree 条件超时，可见文本={visible}")

    def has_any(self, root: ET.Element, values: set[str]) -> bool:
        return any(n.attrib.get("text") in values for n in self.nodes(root))

    def has_prefix(self, root: ET.Element, prefix: str) -> bool:
        return any(n.attrib.get("text", "").startswith(prefix) for n in self.nodes(root))

    def find_any(self, root: ET.Element, values: set[str]) -> ET.Element:
        for node in self.nodes(root):
            if node.attrib.get("text") in values:
                return node
        raise AssertionError(f"UI tree 未找到文本: {values}")

    def find_clickable_with_text(self, root: ET.Element, values: set[str]) -> ET.Element:
        for node in self.nodes(root):
            if node.attrib.get("clickable") != "true":
                continue
            if any(child.attrib.get("text") in values for child in node.iter("node")):
                return node
        raise AssertionError(f"UI tree 未找到可点击文本容器: {values}")

    def click(self, node: ET.Element) -> None:
        match = re.fullmatch(r"\[(\d+),(\d+)]\[(\d+),(\d+)]", node.attrib["bounds"])
        if not match:
            raise AssertionError(f"bounds 无效: {node.attrib.get('bounds')}")
        left, top, right, bottom = map(int, match.groups())
        self.adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def test_recovering(self) -> None:
        self.launch()
        root = self.wait_tree(lambda tree: self.has_any(tree, RECOVERING))
        assert not self.has_any(root, RETRY)
        assert not self.has_prefix(root, READY_PREFIX)

    def test_blocked_rotation_and_touch_target(self) -> None:
        self.launch("blocked")
        root = self.wait_tree(lambda tree: self.has_any(tree, BLOCKED))
        self.adb("shell", "cmd", "window", "user-rotation", "lock", "1")
        try:
            root = self.wait_tree(lambda tree: self.has_any(tree, BLOCKED))
            retry = self.find_clickable_with_text(root, RETRY)
            bounds = list(map(int, re.findall(r"\d+", retry.attrib["bounds"])))
            density_text = self.adb("shell", "wm", "density")
            density = int(re.findall(r"(?:Override|Physical) density: (\d+)", density_text)[-1])
            assert bounds[3] - bounds[1] >= int(48 * density / 160)
            assert retry.attrib.get("clickable") == "true"
            assert not self.has_prefix(root, READY_PREFIX)
        finally:
            self.adb("shell", "cmd", "window", "user-rotation", "free")

    def test_ready(self) -> None:
        self.launch("ready")
        root = self.wait_tree(lambda tree: self.has_prefix(tree, READY_PREFIX))
        assert not self.has_any(root, RECOVERING | BLOCKED)

    def test_long_recovery(self) -> None:
        self.launch("long")
        root = self.wait_tree(lambda tree: self.has_any(tree, RECOVERING))
        assert not self.has_prefix(root, READY_PREFIX)
        root = self.wait_tree(lambda tree: self.has_prefix(tree, f"{READY_PREFIX}1"), timeout=12)
        assert self.has_prefix(root, f"{READY_PREFIX}1")

    def test_failure_retry(self) -> None:
        self.launch("failure_retry", retry_delay_ms=RETRY_RECOVERY_MILLIS)
        root = self.wait_tree(lambda tree: self.has_any(tree, BLOCKED))
        retry = self.find_clickable_with_text(root, RETRY)
        self.click(retry)
        recovering = self.wait_tree(lambda tree: self.has_any(tree, RECOVERING), timeout=4)
        assert not self.has_prefix(recovering, READY_PREFIX)
        root = self.wait_tree(lambda tree: self.has_prefix(tree, f"{READY_PREFIX}1"), timeout=10)
        assert self.has_prefix(root, f"{READY_PREFIX}1")

    def run(self) -> None:
        self.install()
        tests = [
            self.test_recovering,
            self.test_blocked_rotation_and_touch_target,
            self.test_ready,
            self.test_long_recovery,
            self.test_failure_retry,
        ]
        try:
            for index, test in enumerate(tests, 1):
                test()
                print(f"PASS {index}/5 {test.__name__}")
        finally:
            self.adb("shell", "cmd", "window", "user-rotation", "free")
            self.adb("shell", "am", "force-stop", PACKAGE)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument(
        "--apk",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "app/build/outputs/apk/debug/app-debug.apk",
    )
    args = parser.parse_args()
    StartupGateDeviceTest(args.serial, args.apk).run()


if __name__ == "__main__":
    main()
