#!/usr/bin/env python3
"""文本换行静态门禁的契约测试。"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "check-text-wrapping.py"


class CheckTextWrappingTest(unittest.TestCase):
    def _run(self, files: dict[str, str]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(textwrap.dedent(content), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(SCRIPT_PATH), "--root", str(root)],
                text=True,
                capture_output=True,
            )

    UI = "native-ui/app/src/main/java/com/example/ui/Screen.kt"
    THEME = "native-ui/app/src/main/java/com/example/ui/theme/Type.kt"
    DATA = "native-ui/app/src/main/java/com/example/data/Export.kt"
    STRINGS = "native-ui/app/src/main/res/values/strings.xml"

    def test_clean_sources_pass(self) -> None:
        result = self._run({
            self.UI: """
                fun Screen() {
                    OutlinedTextField(value = v, textStyle = LocalTextStyle.current.forInput())
                    BasicTextField(value = v)
                    InfinityMetaLine(segments = listOf(a, b))
                    val code = TextStyle(fontFamily = Mono).forInput()
                }
            """,
            self.THEME: "val Body = TextStyle(fontSize = 15.sp)\n",
            self.STRINGS: '<resources><string name="kg_nodes">%1$d 节点</string></resources>\n',
        })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_dot_join_in_ui_fails_but_allow_marker_and_non_ui_pass(self) -> None:
        failing = self._run({self.UI: 'val s = "$type · $state"\nval t = list.joinToString(" · ")\nval u = list.joinToString(" • ")\n'})
        self.assertEqual(failing.returncode, 1)
        self.assertEqual(failing.stdout.count("InfinityMetaLine"), 3)

        allowed = self._run({
            self.UI: 'val a11y = "$a · $b" // wrap-check: allow 无障碍描述\n',
            self.DATA: 'val title = "$name · 分支"\n',
        })
        self.assertEqual(allowed.returncode, 0, allowed.stdout)

    def test_text_fields_require_input_line_break(self) -> None:
        result = self._run({
            self.UI: """
                fun Screen() {
                    OutlinedTextField(value = v, onValueChange = {})
                    TextField(value = v, textStyle = MaterialTheme.typography.bodyLarge)
                    BasicTextField(value = v, textStyle = style)
                }
            """,
        })
        self.assertEqual(result.returncode, 1)
        self.assertIn("OutlinedTextField 缺少", result.stdout)
        self.assertIn("TextField 的 textStyle 未使用", result.stdout)
        self.assertIn("BasicTextField 的 textStyle 未使用", result.stdout)

    def test_raw_text_style_outside_theme_fails(self) -> None:
        result = self._run({self.UI: "val s = TextStyle(fontSize = 13.sp)\n"})
        self.assertEqual(result.returncode, 1)
        self.assertIn("主题外直接新建 TextStyle", result.stdout)

    def test_dot_format_strings_require_allowlist(self) -> None:
        result = self._run({
            self.STRINGS: """
                <resources>
                    <string name="kg_stats_summary">%1$d 节点 · %2$d 边</string>
                    <string name="chat_task_title">任务 · %1$s</string>
                    <string name="share_status">已导入 · 等待索引</string>
                </resources>
            """,
        })
        self.assertEqual(result.returncode, 1)
        self.assertIn("kg_stats_summary", result.stdout)
        self.assertNotIn("chat_task_title", result.stdout)
        self.assertNotIn("share_status", result.stdout)


if __name__ == "__main__":
    unittest.main()
