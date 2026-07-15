#!/usr/bin/env python3
"""跨平台 timeout helper 的契约测试。"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "run-with-timeout.py"


class RunWithTimeoutContractTest(unittest.TestCase):
    def _run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT_PATH), *argv],
            cwd=cwd,
            text=True,
            capture_output=True,
        )

    @staticmethod
    def _read_pid_or_none(path: Path) -> int | None:
        if not path.exists():
            return None
        content = path.read_text(encoding="utf-8").strip()
        if not content:
            return None
        return int(content.splitlines()[0])

    @staticmethod
    def _pid_alive(pid: int) -> bool:
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False

    @staticmethod
    def _wait_for_pid_exit(pid: int, timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not RunWithTimeoutContractTest._pid_alive(pid):
                return True
            time.sleep(0.05)
        return not RunWithTimeoutContractTest._pid_alive(pid)

    @staticmethod
    def _cleanup_pid(pid: int) -> None:
        try:
            os.kill(pid, signal.SIGKILL)
        except OSError:
            return

    def _write_parent_script(self, workspace: Path, name: str) -> Path:
        child_code = textwrap.dedent(
            """
            import signal
            import time

            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            time.sleep(60)
            """
        ).strip()
        script = textwrap.dedent(
            f"""
            import subprocess
            import sys
            import time

            marker = sys.argv[1]
            child = subprocess.Popen([sys.executable, '-c', {child_code!r}])
            with open(marker, 'w', encoding='utf-8') as f:
                f.write(str(child.pid))
            while True:
                time.sleep(1)
            """
        ).strip()
        script_file = workspace / name
        script_file.write_text(script, encoding="utf-8")
        return script_file

    def test_successful_command_output_and_return_code(self) -> None:
        process = self._run(["5", sys.executable, "-c", "print('timeout-ok')"])
        self.assertEqual(process.returncode, 0)
        self.assertIn("timeout-ok", process.stdout)
        self.assertEqual(process.stderr, "")

    def test_non_zero_exit_code_is_propagated(self) -> None:
        process = self._run(["5", sys.executable, "-c", "import sys; print('boom'); sys.exit(7)"])
        self.assertEqual(process.returncode, 7)
        self.assertIn("boom", process.stdout)

    def test_timeout_returns_124_and_cleans_descendants(self) -> None:
        with tempfile.TemporaryDirectory() as workdir:
            workspace = Path(workdir)
            marker = workspace / "grandchild-pid.txt"
            script_file = self._write_parent_script(workspace, "long_running.py")
            child_pid: int | None = None
            try:
                process = self._run(["1", sys.executable, str(script_file), str(marker)], cwd=workspace)
                child_pid = self._read_pid_or_none(marker)
                self.assertEqual(process.returncode, 124)
                if child_pid is not None:
                    self.assertTrue(
                        self._wait_for_pid_exit(child_pid, 2.0),
                        f"忽略 SIGTERM 的孙进程 {child_pid} 在超时后未退出",
                    )
            finally:
                if child_pid is not None:
                    self._cleanup_pid(child_pid)
                    self.assertTrue(
                        self._wait_for_pid_exit(child_pid, 1.0),
                        f"测试清理未回收孙进程 {child_pid}",
                    )

    def _run_helper_with_signal_and_assert_exit(self, signal_to_send: int, expected_code: int) -> None:
        with tempfile.TemporaryDirectory() as workdir:
            workspace = Path(workdir)
            marker = workspace / "grandchild-pid.txt"
            script_file = self._write_parent_script(workspace, "parent_holds_child.py")
            helper_process: subprocess.Popen[object] | None = None
            grandchild_pid: int | None = None
            try:
                helper_process = subprocess.Popen(
                    [sys.executable, str(SCRIPT_PATH), "30", sys.executable, str(script_file), str(marker)],
                    cwd=workspace,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )

                deadline = time.monotonic() + 3.0
                while time.monotonic() < deadline and not marker.exists():
                    time.sleep(0.05)

                grandchild_pid = self._read_pid_or_none(marker)
                self.assertIsNotNone(grandchild_pid)

                helper_process.send_signal(signal_to_send)
                helper_process.wait(timeout=5)
                self.assertEqual(helper_process.returncode, expected_code)
                self.assertTrue(
                    self._wait_for_pid_exit(grandchild_pid, 2.0),
                    f"忽略 SIGTERM 的孙进程 {grandchild_pid} 在 helper {signal_to_send} 后未退出",
                )
            finally:
                if helper_process is not None and helper_process.poll() is None:
                    helper_process.kill()
                    helper_process.wait(timeout=1)
                if grandchild_pid is not None:
                    self._cleanup_pid(grandchild_pid)
                    self.assertTrue(
                        self._wait_for_pid_exit(grandchild_pid, 1.0),
                        f"测试清理未回收孙进程 {grandchild_pid}",
                    )

    def test_parent_signal_interrupt_cleans_descendants(self) -> None:
        self._run_helper_with_signal_and_assert_exit(signal.SIGINT, 130)

    def test_parent_signal_termination_cleans_descendants(self) -> None:
        self._run_helper_with_signal_and_assert_exit(signal.SIGTERM, 143)

    def test_invocation_shape_smoke(self) -> None:
        process = self._run(["1", "/usr/bin/true"])
        self.assertEqual(process.returncode, 0)

    def test_invalid_seconds_rejected_fast(self) -> None:
        process = self._run(["-3", "echo", "x"])
        self.assertNotEqual(process.returncode, 0)
        self.assertIn("正整数", process.stdout + process.stderr)

    def test_missing_command_returns_127_without_traceback(self) -> None:
        process = self._run(["5", "no-such-command-abcdef"])
        self.assertEqual(process.returncode, 127)
        self.assertNotIn("Traceback", process.stdout + process.stderr)
        self.assertIn("命令不存在", process.stdout + process.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
