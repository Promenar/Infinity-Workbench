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

    def write(self, path, permission=False, package="com.promenar.nexara.native.debug", special_use=None, service=None):
        special_use = permission if special_use is None else special_use
        service = permission if service is None else service
        items = '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />'
        items += '<application><service android:name="com.promenar.nexara.background.schedule.ScheduledTaskJobService" android:permission="android.permission.BIND_JOB_SERVICE" /></application>'
        if permission:
            items += '<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />'
        if permission:
            items += '<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />'
        if special_use:
            items += '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />'
        if service:
            items += f'<application><service android:name="{check.SIDELOAD_ONLY_SERVICE}" /></application>'
        path.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">{items}</manifest>')

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

    def test_exec_foreground_service_is_exclusive_to_sideload(self):
        cases = (
            ({"special_use": True}, {}),
            ({"service": True}, {}),
            ({}, {"special_use": False}),
            ({}, {"service": False}),
        )
        for play_extra, sideload_extra in cases:
            with self.subTest(play=play_extra, sideload=sideload_extra):
                self.write(self.play, **play_extra)
                self.write(self.sideload, True, **sideload_extra)
                with self.assertRaises(ValueError):
                    check.check_manifests(self.play, self.sideload)

    def test_play_rejects_both_exact_alarm_permissions(self):
        self.write(self.sideload, True)
        for permission in ("SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM"):
            with self.subTest(permission=permission):
                self.write(self.play)
                content = self.play.read_text().replace("</manifest>", f'<uses-permission android:name="android.permission.{permission}" /></manifest>')
                self.play.write_text(content)
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
