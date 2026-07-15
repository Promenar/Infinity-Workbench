#!/usr/bin/env python3
"""v0.2-beta workflow 发行可靠性纯文本契约测试。"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
ANDROID_CI = (ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
RELEASE = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
SMOKE = (ROOT / "scripts/ci/android-release-apk-smoke.sh").read_text(encoding="utf-8")
VALIDATOR = str((ROOT / "scripts/ci/validate-release-readiness.py"))


class ReleaseWorkflowReliabilityTest(unittest.TestCase):
    def test_android_ci_covers_current_default_and_release_branches(self) -> None:
        self.assertIn("pull_request:", ANDROID_CI)
        self.assertIn("      - B-native-refactor", ANDROID_CI)
        self.assertIn("      - codex/v0.2-beta", ANDROID_CI)

    def test_release_inputs_and_tag_provenance_fail_closed(self) -> None:
        self.assertIn("docs/release/v0.2-beta.md", RELEASE)
        self.assertIn("docs/release/v0.2-beta-validation.md", RELEASE)
        self.assertIn('git cat-file -t "refs/tags/${GITHUB_REF_NAME}"', RELEASE)
        self.assertIn('"tag"', RELEASE)
        self.assertIn("verification.verified", RELEASE)
        self.assertIn("NEXARA_ALLOW_UNSIGNED_ANNOTATED_TAG", RELEASE)
        self.assertIn("NEXARA_RELEASE_COMMIT_SHA", RELEASE)
        self.assertIn("origin/B-native-refactor", RELEASE)
        self.assertIn("origin/codex/v0.2-beta", RELEASE)
        self.assertIn('git rev-parse "${reviewed_branch}"', RELEASE)
        self.assertNotIn("merge-base --is-ancestor", RELEASE)

    def test_cross_job_artifact_name_survives_failed_job_rerun(self) -> None:
        self.assertIn("signed-release-apk-${{ github.run_id }}", RELEASE)
        self.assertNotIn("signed-release-apk-${{ github.run_attempt }}", RELEASE)
        signed_upload = RELEASE.split("- name: 上传已验签发行产物", 1)[1].split("\n\n", 1)[0]
        self.assertIn("overwrite: true", signed_upload)

    def test_release_workflow_includes_minified_blackbox_job_and_dependency(self) -> None:
        self.assertIn("minified-blackbox", RELEASE)
        self.assertIn("Release 等价 minifiedTest 黑盒回归", RELEASE)
        self.assertIn("scripts/ci/android-minified-blackbox-smoke.sh", RELEASE)
        self.assertIn("artifacts/android-minified-blackbox-api-${{ matrix.api-level }}-x86_64/", RELEASE)
        self.assertNotIn("NEXARA_DEVICE_E2E_BUILD_TYPE: minifiedTest", RELEASE)
        self.assertIn("build-release:", RELEASE)
        self.assertIn("needs: [device-e2e, minified-blackbox]", RELEASE)

    def test_r8_evidence_and_download_integrity_are_mandatory(self) -> None:
        self.assertIn("outputs/mapping/release/mapping.txt", RELEASE)
        self.assertRegex(RELEASE, r"test -s .*mapping\.txt")
        for filename in ("mapping.txt", "seeds.txt", "usage.txt", "configuration.txt"):
            self.assertIn(filename, RELEASE)
        self.assertGreaterEqual(RELEASE.count("sha256sum -c"), 2)

    def test_signed_minified_apk_is_cold_installed_on_api_35(self) -> None:
        signed_job = RELEASE.split("  signed-apk-smoke:", 1)[1].split("\n  publish:", 1)[0]
        self.assertIn("冷安装 smoke", signed_job)
        self.assertIn("- api-level: 35", signed_job)
        self.assertIn("- api-level: 36", signed_job)
        self.assertIn("android-release-apk-smoke.sh", signed_job)
        self.assertNotIn("android-device-core-e2e.sh", signed_job)

    def test_smoke_requires_modern_zip_alignment(self) -> None:
        self.assertIn('ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"', SMOKE)
        self.assertIn("zipalign", SMOKE)
        self.assertIn("-c -P 16 4 \"${APK_PATH}\"", SMOKE)

    def test_publish_is_idempotent_and_checks_existing_asset_hashes(self) -> None:
        publish_job = RELEASE.split("\n  publish:", 1)[1]
        self.assertIn("gh release view", publish_job)
        self.assertIn("gh release download", publish_job)
        self.assertIn("sha256sum", publish_job)
        self.assertIn("gh release upload", publish_job)
        self.assertIn("gh release create", publish_job)

    def test_validate_release_input_job_calls_validator_early(self) -> None:
        validate_job = RELEASE.split("  validate-release-inputs:", 1)[1].split("  device-e2e:", 1)[0]
        checkout_idx = validate_job.find("- name: 检出已存在的发行标签")
        validator_idx = validate_job.find("validate-release-readiness.py")
        provenance_idx = validate_job.find("校验固定标签、发行文档、签名与来源")
        self.assertGreaterEqual(checkout_idx, 0)
        self.assertGreater(validator_idx, checkout_idx)
        self.assertGreater(provenance_idx, validator_idx)

    def test_publish_calls_validator_before_release_commands(self) -> None:
        publish_job = RELEASE.split("\n  publish:", 1)[1]
        validator_idx = publish_job.find("python3 scripts/ci/validate-release-readiness.py")
        self.assertGreaterEqual(validator_idx, 0)
        self.assertLess(validator_idx, publish_job.find("gh release view"))
        self.assertLess(validator_idx, publish_job.find("gh release create"))
        self.assertLess(validator_idx, publish_job.find("gh release upload"))

    def test_publish_validation_call_uses_expected_doc_paths(self) -> None:
        publish_job = RELEASE.split("\n  publish:", 1)[1]
        self.assertRegex(
            publish_job,
            r"python3 scripts/ci/validate-release-readiness\.py \\\n\s+docs/release/v0\.2-beta-validation\.md \\\n\s+docs/release/v0\.2-beta\.md",
        )

        validation_job = RELEASE.split("  validate-release-inputs:", 1)[1].split("  device-e2e:", 1)[0]
        self.assertRegex(
            validation_job,
            r"python3 scripts/ci/validate-release-readiness\.py \\\n\s+docs/release/v0\.2-beta-validation\.md \\\n\s+docs/release/v0\.2-beta\.md",
        )

    def run_release_validator(self, ledger: str, notes: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_root = Path(tmp_dir)
            ledger_path = tmp_root / "v0.2-beta-validation.md"
            notes_path = tmp_root / "v0.2-beta.md"
            ledger_path.write_text(ledger, encoding="utf-8")
            notes_path.write_text(notes, encoding="utf-8")
            return subprocess.run(
                [sys.executable, VALIDATOR, ledger_path, notes_path],
                text=True,
                capture_output=True,
                check=False,
            )

    def test_validate_release_readiness_accepts_only_exact_go_markers(self) -> None:
        accepted_documents = (
            ("> 状态：GO\n- 最终结论：GO\n", "> 发布状态：GO\n"),
            ("> 状态：**GO / PASS**\n- 最终结论：`GO / PASS`\n", "> 发布状态：**GO / PASS**\n"),
        )
        for ledger, notes in accepted_documents:
            with self.subTest(ledger=ledger, notes=notes):
                result = self.run_release_validator(ledger, notes)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_validate_release_readiness_rejects_missing_empty_or_duplicate_markers(self) -> None:
        invalid_documents = (
            ("- 最终结论：GO\n", "> 发布状态：GO\n"),
            ("> 状态：   \n- 最终结论：GO\n", "> 发布状态：GO\n"),
            ("> 状态：GO\n> 状态：GO\n- 最终结论：GO\n", "> 发布状态：GO\n"),
            ("> 状态：GO\n", "> 发布状态：GO\n"),
            ("> 状态：GO\n- 最终结论：   \n", "> 发布状态：GO\n"),
            ("> 状态：GO\n- 最终结论：GO\n- 最终结论：GO\n", "> 发布状态：GO\n"),
            ("> 状态：GO\n- 最终结论：GO\n", "# 缺少发布状态\n"),
            ("> 状态：GO\n- 最终结论：GO\n", "> 发布状态：   \n"),
            ("> 状态：GO\n- 最终结论：GO\n", "> 发布状态：GO\n> 发布状态：GO\n"),
        )
        for ledger, notes in invalid_documents:
            with self.subTest(ledger=ledger, notes=notes):
                result = self.run_release_validator(ledger, notes)
                self.assertNotEqual(result.returncode, 0)

    def test_validate_release_readiness_rejects_ambiguous_go_substrings(self) -> None:
        invalid_values = ("PENDING", "NO-GO", "NOT GO", "NO GO", "ONGOING", "GOING", "GO / PENDING")
        for invalid_value in invalid_values:
            documents = (
                (f"> 状态：{invalid_value}\n- 最终结论：GO\n", "> 发布状态：GO\n"),
                (f"> 状态：GO\n- 最终结论：{invalid_value}\n", "> 发布状态：GO\n"),
                ("> 状态：GO\n- 最终结论：GO\n", f"> 发布状态：{invalid_value}\n"),
            )
            for ledger, notes in documents:
                with self.subTest(value=invalid_value, ledger=ledger, notes=notes):
                    result = self.run_release_validator(ledger, notes)
                    self.assertNotEqual(result.returncode, 0)

    def test_actions_are_sha_pinned_and_permissions_are_minimal(self) -> None:
        for workflow in (ANDROID_CI, RELEASE):
            for action_ref in re.findall(r"uses:\s+([^\s#]+)", workflow):
                self.assertRegex(action_ref, r"@[0-9a-f]{40}$")
        self.assertIn("permissions:\n  contents: read", RELEASE)
        self.assertIn("permissions:\n      contents: write", RELEASE)
        self.assertIn("if: always()", RELEASE)
        self.assertIn('rm -f "${RUNNER_TEMP}/nexara-release.jks"', RELEASE)

    def test_release_workflow_api35_api36_matrix_includes_minified_blackbox(self) -> None:
        self.assertEqual(RELEASE.count("- api-level: 31"), 1)
        self.assertEqual(RELEASE.count("- api-level: 35"), 3)
        self.assertEqual(RELEASE.count("- api-level: 36"), 3)


if __name__ == "__main__":
    unittest.main(verbosity=2)
