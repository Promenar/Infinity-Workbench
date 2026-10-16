#!/usr/bin/env python3
"""通过真实 XML 夹具验证两个风味的权限隔离检查。"""

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-distribution-manifests.py"
spec = importlib.util.spec_from_file_location("distribution_manifests", SCRIPT)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class DistributionManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.play = Path(self.tmp.name) / "play.xml"
        self.sideload = Path(self.tmp.name) / "sideload.xml"

    def write(self, path, permission=False, package="com.promenar.nexara.native.debug"):
        access = '<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />' if permission else ""
        path.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">{access}</manifest>')

    def test_permission_is_exclusive_to_sideload(self):
        self.write(self.play)
        self.write(self.sideload, True)
        check.check_manifests(self.play, self.sideload)

    def test_rejects_play_permission_and_missing_sideload_permission(self):
        for play_permission, sideload_permission in ((True, True), (False, False)):
            with self.subTest(play=play_permission, sideload=sideload_permission):
                self.write(self.play, play_permission)
                self.write(self.sideload, sideload_permission)
                with self.assertRaises(ValueError):
                    check.check_manifests(self.play, self.sideload)

    def test_missing_invalid_and_different_package_fail_closed(self):
        self.write(self.sideload, True)
        with self.assertRaises(OSError):
            check.check_manifests(self.play, self.sideload)
        self.play.write_text("<manifest>")
        with self.assertRaises(Exception):
            check.check_manifests(self.play, self.sideload)
        self.write(self.play, package="com.example.wrong")
        with self.assertRaises(ValueError):
            check.check_manifests(self.play, self.sideload)


if __name__ == "__main__":
    unittest.main(verbosity=2)
