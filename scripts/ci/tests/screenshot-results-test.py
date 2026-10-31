#!/usr/bin/env python3
"""截图测试报告覆盖检查。"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "screenshot-results.py"


class ScreenshotResultsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.classes = self.root / "classes.txt"
        self.classes.write_text("FirstKt\nSecondKt\n")
        self.xml = self.root / "TEST-engine.xml"
        self.xml.write_text('<testsuite tests="2" skipped="0" failures="0" errors="0">'
                            '<testcase classname="com.example.FirstKt" name="one"/>'
                            '<testcase classname="com.example.SecondKt" name="two"/>'
                            '</testsuite>')

    def run_check(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--classes", str(self.classes),
                               "--results", str(self.root)], capture_output=True, text=True)

    def test_all_classes_pass(self):
        self.assertEqual(self.run_check().returncode, 0)

    def test_missing_class_fails(self):
        self.xml.write_text('<testsuite tests="1" skipped="0" failures="0" errors="0">'
                            '<testcase classname="com.example.FirstKt" name="one"/>'
                            '</testsuite>')
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_skipped_or_failed_test_fails(self):
        self.xml.write_text('<testsuite tests="2" skipped="1" failures="0" errors="0">'
                            '<testcase classname="com.example.FirstKt" name="one"/>'
                            '<testcase classname="com.example.SecondKt" name="two"><skipped/></testcase>'
                            '</testsuite>')
        self.assertNotEqual(self.run_check().returncode, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
