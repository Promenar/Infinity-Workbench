import importlib.util
import pathlib
import sys
import unittest


SCRIPT = pathlib.Path(__file__).parents[1] / "e2e" / "run-encrypted-backup-restore-e2e.py"
SPEC = importlib.util.spec_from_file_location("encrypted_backup_e2e", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class InstrumentationResultTest(unittest.TestCase):
    def test_regular_phase_requires_semantic_success_marker(self):
        MODULE.require_regular_phase_success("verify", 0, "OK (1 test)\n")

        with self.assertRaisesRegex(RuntimeError, "verify"):
            MODULE.require_regular_phase_success("verify", 0, "INSTRUMENTATION_CODE: 0\n")

    def test_regular_phase_rejects_failure_even_when_adb_exit_code_is_zero(self):
        with self.assertRaisesRegex(RuntimeError, "FAILURES"):
            MODULE.require_regular_phase_success(
                "verify",
                0,
                "FAILURES!!!\nTests run: 1,  Failures: 1\n",
            )

    def test_stage_accepts_expected_process_termination_with_relay_proof(self):
        MODULE.require_stage_termination(
            0,
            "INSTRUMENTATION_ABORTED: System has crashed.\n",
            "I/NexaraRestoreRelay: relay_pid=222 main_pid=111 payload=none\n",
        )

    def test_stage_rejects_normal_test_completion(self):
        with self.assertRaisesRegex(RuntimeError, "正常结束"):
            MODULE.require_stage_termination(
                0,
                "OK (1 test)\n",
                "I/NexaraRestoreRelay: relay_pid=222 main_pid=111 payload=none\n",
            )

    def test_stage_rejects_missing_or_unsafe_relay_proof(self):
        with self.assertRaisesRegex(RuntimeError, "relay"):
            MODULE.require_stage_termination(
                1,
                "INSTRUMENTATION_ABORTED\n",
                "I/NexaraRestoreRelay: relay_pid=222 main_pid=111 payload=secret\n",
            )

    def test_stage_rejects_assertion_failure_instead_of_treating_it_as_kill(self):
        with self.assertRaisesRegex(RuntimeError, "FAILURES"):
            MODULE.require_stage_termination(
                0,
                "FAILURES!!!\nTests run: 1,  Failures: 1\n",
                "I/NexaraRestoreRelay: relay_pid=222 main_pid=111 payload=none\n",
            )


if __name__ == "__main__":
    unittest.main()
