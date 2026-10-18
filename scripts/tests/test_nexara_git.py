"""内置 git 包装（native-ui/app/python/site/nexara_git.py）中不依赖 dulwich 的改写与输出整理逻辑。"""

from __future__ import annotations

import importlib.util
import io
from pathlib import Path
import unittest

MODULE_PATH = Path(__file__).resolve().parents[2] / "native-ui/app/python/site/nexara_git.py"
spec = importlib.util.spec_from_file_location("nexara_git", MODULE_PATH)
nexara_git = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nexara_git)


class RewriteTest(unittest.TestCase):
    def test_log_count_shorthand_becomes_max_count(self) -> None:
        self.assertEqual(nexara_git._rewrite("log", ["--oneline", "-5", "src"]), (["--oneline", "-n", "5", "src"], None))
        self.assertEqual(nexara_git._rewrite("log", ["-n", "3"]), (["-n", "3"], None))

    def test_branch_short_listing_options(self) -> None:
        self.assertEqual(nexara_git._rewrite("branch", ["-a"]), (["--all"], None))
        self.assertEqual(nexara_git._rewrite("branch", ["-r"]), (["--remotes"], None))

    def test_add_all_maps_to_dot(self) -> None:
        self.assertEqual(nexara_git._rewrite("add", ["-A"]), (["."], None))

    def test_commit_requires_message(self) -> None:
        rewritten, problem = nexara_git._rewrite("commit", ["-a"])
        self.assertIsNone(rewritten)
        self.assertIn("-m", problem)
        self.assertEqual(nexara_git._rewrite("commit", ["-am", "msg"]), (["-am", "msg"], None))

    def test_ssh_urls_are_detected(self) -> None:
        for url in ("git@github.com:a/b.git", "ssh://git@host/r.git"):
            self.assertTrue(nexara_git._ssh_url(url), url)
        self.assertFalse(nexara_git._ssh_url("https://github.com/a/b.git"))


class ProgressCollapserTest(unittest.TestCase):
    def collapse(self, *chunks: bytes) -> bytes:
        sink = io.BytesIO()
        collapser = nexara_git._ProgressCollapser(sink)
        for chunk in chunks:
            collapser.write(chunk)
        collapser.finish()
        return sink.getvalue()

    def test_keeps_only_final_state_of_carriage_return_updates(self) -> None:
        self.assertEqual(
            self.collapse(b"Counting: 1%\rCounting: 50%\rCounting: 100%, done.\nnext\n"),
            b"Counting: 100%, done.\nnext\n",
        )

    def test_crlf_split_across_writes_is_a_newline(self) -> None:
        self.assertEqual(self.collapse(b"line\r", b"\nafter\n"), b"line\nafter\n")

    def test_trailing_partial_line_is_flushed(self) -> None:
        self.assertEqual(self.collapse(b"writing 0/3\rwriting 3/3"), b"writing 3/3\n")


if __name__ == "__main__":
    unittest.main()
