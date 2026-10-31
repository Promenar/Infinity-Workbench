#!/usr/bin/env python3
"""Linux 截图参考图合成契约。"""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "screenshot-baseline.py"


class ScreenshotBaselineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "base"
        self.overlay = self.root / "overlay"
        self.output = self.root / "output"
        self.base.mkdir()
        self.overlay.mkdir()
        (self.base / "one.png").write_bytes(b"base")
        (self.base / "two.png").write_bytes(b"same")
        (self.overlay / "one.png").write_bytes(b"linux")
        self.manifest = {"version": 1, "entries": {"one.png": {
            "base_sha256": hashlib.sha256(b"base").hexdigest(),
            "linux_sha256": hashlib.sha256(b"linux").hexdigest(),
        }}}
        self.save_manifest()

    def save_manifest(self):
        (self.overlay / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def run_script(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--base", str(self.base), "--overlay", str(self.overlay),
                               "--output", str(self.output)], capture_output=True, text=True)

    def test_merges_without_changing_inputs(self):
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual((self.output / "one.png").read_bytes(), b"linux")
        self.assertEqual((self.output / "two.png").read_bytes(), b"same")
        self.assertEqual((self.base / "one.png").read_bytes(), b"base")

    def test_stale_base_fails_before_writing_output(self):
        (self.base / "one.png").write_bytes(b"changed")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("须重新审阅", result.stderr)
        self.assertFalse(self.output.exists())

    def test_unlisted_overlay_fails(self):
        (self.overlay / "extra.png").write_bytes(b"extra")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("与清单不一致", result.stderr)
        self.assertFalse(self.output.exists())

    def test_changed_linux_candidate_fails(self):
        (self.overlay / "one.png").write_bytes(b"changed")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("须重新审阅", result.stderr)
        self.assertFalse(self.output.exists())

    def test_missing_linux_candidate_fails(self):
        (self.overlay / "one.png").unlink()
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("截图参考缺失", result.stderr)
        self.assertFalse(self.output.exists())

    def test_output_must_not_exist(self):
        self.output.mkdir()
        (self.output / "keep").write_text("keep")
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertEqual((self.output / "keep").read_text(), "keep")

    def test_manifest_cannot_escape_reference_directory(self):
        self.manifest["entries"]["../outside.png"] = self.manifest["entries"]["one.png"]
        self.save_manifest()
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertFalse(self.output.exists())

    def test_symlinked_baseline_is_rejected(self):
        (self.base / "two.png").unlink()
        (self.base / "two.png").symlink_to(self.root / "outside")
        (self.root / "outside").write_bytes(b"private")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("符号链接", result.stderr)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
