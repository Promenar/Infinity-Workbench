#!/usr/bin/env python3
"""验证 SSH 会话校验、输出脱敏及 instrumentation 参数传递，不连接设备。"""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def load_helper():
    spec = importlib.util.spec_from_file_location("ssh_device_session", Path(__file__).with_name("ssh-device-session.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SshDeviceSessionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.session = self.root / "session.json"
        self.password = "test-only-once-password"
        self.data = {"port": 2222, "echoPort": 2223, "username": "infinity-test",
                     "fingerprint": "SHA256:" + "A" * 43, "password": self.password, "transport": "adb-reverse"}
        self.session.write_text(json.dumps(self.data))
        self.session.chmod(0o600)

    def test_rejects_invalid_session_without_echoing_credential(self):
        helper = load_helper()
        for field, value in [("port", 22), ("echoPort", 2222), ("fingerprint", "bad"),
                             ("password", "bad\nvalue"), ("transport", "network"), ("username", "user;id")]:
            data = dict(self.data, **{field: value})
            self.session.write_text(json.dumps(data))
            with self.subTest(field=field), self.assertRaises(ValueError) as failure:
                helper.load_session(self.session)
            self.assertNotIn(self.password, str(failure.exception))

    def test_rejects_group_readable_session(self):
        helper = load_helper()
        self.session.chmod(0o644)
        with self.assertRaises(ValueError):
            helper.load_session(self.session)

    def test_redacts_stream_before_any_output(self):
        helper = load_helper()
        destination = io.StringIO()
        helper.redact(io.StringIO(f"prefix {self.password} suffix\n{self.password}\n"), destination, self.data)
        self.assertEqual(destination.getvalue(), "prefix [已隐藏] suffix\n[已隐藏]\n")

    def test_stop_removes_only_validated_session_lease(self):
        result = subprocess.run([os.sys.executable, str(Path(__file__).with_name("ssh-device-session.py")),
                                 "stop", "--session", str(self.session)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertFalse(self.session.exists())
        self.assertNotIn(self.password, result.stdout + result.stderr)

    def test_run_sends_secret_through_stdin_and_masks_instrumentation_output(self):
        helper = load_helper()
        capture = self.root / "capture.json"
        adb = self.root / "adb"
        adb.write_text("#!/usr/bin/env python3\nimport json,sys,os,shlex\n"
                       "command=sys.stdin.read()\nparts=shlex.split(command)\n"
                       "password=parts[parts.index('sshPassword')+1]\n"
                       "json.dump({'argv':sys.argv,'port':parts[parts.index('sshPort')+1],"
                       "'fingerprint':parts[parts.index('sshFingerprint')+1],'passwordInStdin':bool(password)},"
                       f"open({str(capture)!r},'w'))\n"
                       "print('remote-output:'+password)\n"
                       "print('INSTRUMENTATION_STATUS_CODE: 0')\n"
                       "print('OK (9 tests)')\nprint('INSTRUMENTATION_CODE: -1')\n")
        adb.chmod(0o700)
        output = io.StringIO()
        with patch.dict(os.environ, {"PATH": str(self.root) + os.pathsep + os.environ["PATH"]}), contextlib.redirect_stdout(output):
            result = helper.run_device(self.data, "test.runner/androidx.test.runner.AndroidJUnitRunner", 10)
        self.assertEqual(result, 0)
        self.assertNotIn(self.password, output.getvalue())
        self.assertIn("[已隐藏]", output.getvalue())
        observed = json.loads(capture.read_text())
        self.assertEqual(observed["argv"][1:], ["shell"])
        self.assertEqual(observed["port"], "2222")
        self.assertEqual(observed["fingerprint"], self.data["fingerprint"])
        self.assertTrue(observed["passwordInStdin"])

    def test_skipped_or_empty_suite_is_not_success(self):
        helper = load_helper()
        for output in ["OK (0 tests)\nINSTRUMENTATION_CODE: -1\n",
                       "INSTRUMENTATION_STATUS_CODE: -3\nOK (9 tests)\nINSTRUMENTATION_CODE: -1\n",
                       "INSTRUMENTATION_STATUS_CODE: -4\nOK (9 tests)\nINSTRUMENTATION_CODE: -1\n",
                       "FAILURES!!!\nINSTRUMENTATION_CODE: -1\n"]:
            self.assertFalse(helper.successful_suite(output))
        self.assertTrue(helper.successful_suite("OK (9 tests)\nINSTRUMENTATION_CODE: -1\n"))

    def run_script_fixture(self, scope, api, ssh=False, skipped=False):
        # 整份脚本在临时仓库运行；假的 adb/gradlew 不接触真实设备或构建目录。
        repo = self.root / "repo"
        ci = repo / "scripts/ci"
        ci.mkdir(parents=True)
        (ci / "lib").mkdir()
        original = Path(__file__).parent
        for name in ["android-device-core-e2e.sh", "ssh-device-session.py", "run-with-timeout.py", "lib/android-instrumentation.sh"]:
            shutil.copy(original / name, ci / name)
        native = repo / "native-ui"
        native.mkdir()
        (native / "gradlew").write_text("#!/bin/sh\nexit 0\n")
        (native / "gradlew").chmod(0o700)
        for apk in ["app/build/outputs/apk/sideload/deviceTest/app-sideload-deviceTest.apk",
                    "app/build/outputs/apk/androidTest/sideload/deviceTest/app-sideload-deviceTest-androidTest.apk",
                    "mainactivity-e2e/build/outputs/apk/deviceTest/mainactivity-e2e-deviceTest.apk"]:
            target = native / apk
            target.parent.mkdir(parents=True, exist_ok=True)
            target.touch()
        adb = self.root / "adb"
        audit = self.root / "adb-audit.jsonl"
        adb.write_text('''#!/usr/bin/env python3
import json,os,sys,shlex
from pathlib import Path
a=sys.argv[1:]
with open(os.environ['FAKE_AUDIT'],'a') as f: f.write(json.dumps(a)+'\\n')
password=json.load(open(os.environ['FAKE_SESSION']))['password']
package='com.promenar.infinity.deviceTest'
if a==['shell']:
    parts=shlex.split(sys.stdin.read())
    assert parts[parts.index('sshPassword')+1]==password
    print('remote-output:'+password)
    if os.environ['FAKE_SKIP']=='1': print('INSTRUMENTATION_STATUS_CODE: -4')
    print('OK (9 tests)\\nINSTRUMENTATION_CODE: -1')
elif a[:3]==['shell','getprop','ro.build.version.sdk']: print(os.environ['FAKE_API'])
elif a[:4]==['shell','pm','list','instrumentation']:
    print('instrumentation:fixture/androidx.test.runner.AndroidJUnitRunner (target='+package+')')
    print('instrumentation:fixture/com.promenar.infinity.MainActivityE2eRunner (target='+package+')')
elif a[:3]==['shell','am','instrument']:
    if 'restoreRelayPhase' in a and a[a.index('restoreRelayPhase')+1] in ['stage','stageNoKey']:
        print('INSTRUMENTATION_STATUS: class=com.promenar.infinity.data.backup.AndroidRestoreRelayEndToEndTest')
        print('INSTRUMENTATION_STATUS: test=relayPhase')
        print('INSTRUMENTATION_RESULT: shortMsg=Process crashed.\\nINSTRUMENTATION_CODE: 0')
    else: print('OK (1 test)\\nINSTRUMENTATION_CODE: -1')
elif a[:2]==['shell','find']:
    for name in ['notification-permission-deny-dialog.png','notification-permission-grant-dialog.png']:
        print('/sdcard/Android/data/'+package+'/files/'+name)
elif a[:1]==['pull']: Path(a[-1]).touch()
elif a==['get-state']: print('device')
elif a[:1]==['logcat'] and '-d' in a:
    if '-s' in a: print('[InfinityRestoreRelay] relay_pid=200 main_pid=100 payload=none')
    else: print('safe-log '+password)
elif a[:2] in [['shell','dumpsys'],['shell','cat']]: print('safe-dump '+password)
''')
        adb.chmod(0o700)
        environment = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ["PATH"],
                           FAKE_AUDIT=str(audit), FAKE_SESSION=str(self.session), FAKE_API=str(api),
                           FAKE_SKIP="1" if skipped else "0", ANDROID_SERIAL="fixture-serial",
                           INFINITY_DEVICE_E2E_ABI="arm64-v8a", INFINITY_DEVICE_E2E_SCOPE=scope)
        environment.pop("INFINITY_SSH_TEST_SESSION", None)
        if ssh:
            environment["INFINITY_SSH_TEST_SESSION"] = str(self.session)
        result = subprocess.run(["bash", str(ci / "android-device-core-e2e.sh")], cwd=repo, env=environment,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=45)
        calls = [json.loads(line) for line in audit.read_text().splitlines()]
        return result, calls, repo / f"artifacts/android-device-api-{api}-arm64-v8a"

    def test_minimum_runs_webview_saf_and_all_script_tests_without_ssh(self):
        result, calls, _ = self.run_script_fixture("minimum", 31)
        self.assertEqual(result.returncode, 0, result.stdout)
        classes = [call[call.index("class")+1] for call in calls if "class" in call]
        for name in ["ui.renderer.RichContentWebViewDeviceTest", "ui.renderer.RichContentThemeTest", "data.storage.SafExternalFolderDeviceTest", "data.script.ScriptRuntimeDeviceTest"]:
            self.assertIn("com.promenar.infinity."+name, classes)
        self.assertFalse(any(call[:1] == ["reverse"] for call in calls))
        self.assertIn("跳过 SSH 设备用例", result.stdout)

    def test_full_runs_migrations_and_ssh_and_redacts_all_text_artifacts(self):
        result, calls, artifacts = self.run_script_fixture("full", 35, ssh=True)
        self.assertEqual(result.returncode, 0, result.stdout)
        classes = [call[call.index("class")+1] for call in calls if "class" in call]
        for name in ["data.storage.RealPathExternalFolderDeviceTest", "data.local.db.InfinityDatabaseMigration18To23Test", "data.local.db.LegacyDatabasePromoterInstrumentedTest"]:
            self.assertIn("com.promenar.infinity."+name, classes)
        self.assertIn(["reverse", "--no-rebind", "tcp:2222", "tcp:2222"], calls)
        self.assertIn(["reverse", "--remove", "tcp:2222"], calls)
        self.assertNotIn(self.password, result.stdout)
        for path in artifacts.iterdir():
            self.assertNotIn(self.password.encode(), path.read_bytes(), path.name)

    def test_ssh_assumption_fails_full_and_removes_reverse_on_exit(self):
        result, calls, _ = self.run_script_fixture("full", 35, ssh=True, skipped=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(["reverse", "--remove", "tcp:2222"], calls)
        self.assertNotIn(self.password, result.stdout)


if __name__ == "__main__":
    unittest.main()
