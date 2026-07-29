#!/usr/bin/env python3
"""v0.2-beta workflow 发行可靠性纯文本契约测试。"""

from __future__ import annotations

import re
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
ANDROID_CI = (ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
RELEASE = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
SMOKE = (ROOT / "scripts/ci/android-release-apk-smoke.sh").read_text(encoding="utf-8")
APP_BUILD = (ROOT / "native-ui/app/build.gradle.kts").read_text(encoding="utf-8")
VALIDATOR = str((ROOT / "scripts/ci/validate-release-readiness.py"))
REQUIRED_LEDGER_MARKERS = (
    "> 物理真机人工验收：BETA-RISK-ACCEPTED",
    "> GitHub 发布动作：AUTHORIZED",
    "> 当前本地 APK 冷安装：NOT-RUN",
    "> 同源历史候选冷安装：PASS",
)


def braced_block_after(source: str, marker: str) -> str:
    marker_index = source.index(marker)
    start = source.index("{", marker_index)
    depth = 0
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError(f"unclosed block after {marker}")


def workflow_job(name: str) -> str:
    match = re.search(
        rf"^  {re.escape(name)}:\n(?P<body>.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)",
        RELEASE,
        flags=re.MULTILINE | re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"missing workflow job: {name}")
    return match.group("body")


def job_level_value(job: str, key: str) -> str | None:
    match = re.search(rf"^    {re.escape(key)}:\s*(.+)$", job, flags=re.MULTILINE)
    return match.group(1).strip() if match else None


def continued_shell_commands(script: str, executable: str) -> list[list[str]]:
    commands: list[list[str]] = []
    current: list[str] | None = None
    for line in script.splitlines():
        stripped = line.strip()
        if current is None:
            if re.match(rf"{re.escape(executable)}(?:\s|$)", stripped) is None:
                continue
            current = [stripped.removesuffix("\\").rstrip()]
        else:
            current.append(stripped.removesuffix("\\").rstrip())
        if not stripped.endswith("\\"):
            commands.append(shlex.split(" ".join(current)))
            current = None
    if current is not None:
        raise AssertionError(f"unterminated shell command: {executable}")
    return commands


class ReleaseWorkflowReliabilityTest(unittest.TestCase):
    def test_android_ci_covers_current_default_and_release_branches(self) -> None:
        self.assertIn("pull_request:", ANDROID_CI)
        self.assertIn("      - B-native-refactor", ANDROID_CI)
        self.assertIn("      - codex/v0.2-beta", ANDROID_CI)
        self.assertIn("      - codex/md3-redesign", ANDROID_CI)

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

    def test_workflow_dispatch_manual_candidate_entry_exists(self) -> None:
        self.assertRegex(
            RELEASE,
            r"(?m)^on:\n  workflow_dispatch:\n  push:\n    tags:\n      - v0\.2-beta$",
        )

    def test_workflow_dispatch_candidate_must_be_only_codex_md3_redesign_branch(self) -> None:
        validate_job = RELEASE.split("  validate-release-inputs:", 1)[1].split("  device-e2e:", 1)[0]
        self.assertIn('test "${GITHUB_REF_TYPE}" = "branch"', validate_job)
        self.assertIn('test "${GITHUB_REF_NAME}" = "codex/md3-redesign"', validate_job)

    def test_workflow_dispatch_candidate_must_match_controlled_full_sha(self) -> None:
        validate_job = workflow_job("validate-release-inputs")
        candidate_start = validate_job.index('if [[ "${GITHUB_EVENT_NAME}" == "workflow_dispatch" ]]')
        candidate_end = validate_job.index("\n          fi", candidate_start)
        candidate_branch = validate_job[candidate_start:candidate_end]
        ordered_checks = (
            'test "${GITHUB_REF_TYPE}" = "branch"',
            'test "${GITHUB_REF_NAME}" = "codex/md3-redesign"',
            'candidate_head="$(git rev-parse HEAD)"',
            'controlled_sha="${NEXARA_RELEASE_COMMIT_SHA:-}"',
            '[[ ! "${controlled_sha}" =~ ^[0-9a-fA-F]{40}$ ]]',
            'test "${GITHUB_SHA}" = "${candidate_head}"',
            'test "${GITHUB_SHA,,}" = "${controlled_sha,,}"',
            "exit 0",
        )
        positions = [candidate_branch.index(check) for check in ordered_checks]
        self.assertEqual(positions, sorted(positions))

    def test_readiness_validator_only_runs_for_push_tag_event(self) -> None:
        validate_job = workflow_job("validate-release-inputs")
        readiness_step = validate_job.split("- name: 发行前统一校验发布状态", 1)[1].split(
            "\n      - name:", 1
        )[0]
        self.assertIn(
            "if: ${{ github.event_name == 'push' && github.ref_type == 'tag' }}",
            readiness_step,
        )
        self.assertIn("validate-release-readiness.py", readiness_step)
        self.assertIn("校验固定标签、发行文档、签名与来源", validate_job)

    def test_publish_only_runs_on_tag_push_events(self) -> None:
        publish_job = workflow_job("publish")
        self.assertEqual(
            job_level_value(publish_job, "if"),
            "${{ github.event_name == 'push' && github.ref == 'refs/tags/v0.2-beta' }}",
        )

    def test_candidate_mode_keeps_all_pre_publish_jobs_enabled(self) -> None:
        for job_name in ("device-e2e", "minified-blackbox", "build-release", "signed-apk-smoke"):
            with self.subTest(job=job_name):
                self.assertIsNone(job_level_value(workflow_job(job_name), "if"))
        signed_job = workflow_job("signed-apk-smoke")
        self.assertIn("- api-level: 35", signed_job)
        self.assertIn("- api-level: 36", signed_job)

    def test_candidate_checks_documents_before_early_exit(self) -> None:
        validate_job = workflow_job("validate-release-inputs")
        document_check = validate_job.index("for required_document in")
        candidate_branch = validate_job.index('if [[ "${GITHUB_EVENT_NAME}" == "workflow_dispatch" ]]')
        candidate_exit = validate_job.index("exit 0", candidate_branch)
        self.assertLess(document_check, candidate_branch)
        self.assertLess(candidate_branch, candidate_exit)

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

    def test_build_release_cleans_before_fresh_full_gate_gradle_invocation(self) -> None:
        build_job = workflow_job("build-release")
        build_step = build_job.split("- name: 执行全门禁并构建签名 APK", 1)[1]
        build_step = build_step.split("- name: 删除临时 keystore", 1)[0]
        commands = continued_shell_commands(build_step, "./gradlew")
        self.assertEqual(
            commands,
            [
                [
                    "./gradlew",
                    "--no-daemon",
                    "--stacktrace",
                    "--no-build-cache",
                    "clean",
                ],
                [
                    "./gradlew",
                    "--no-daemon",
                    "--stacktrace",
                    "--no-build-cache",
                    ":app:validateDebugScreenshotTest",
                ],
                [
                    "./gradlew",
                    "--no-daemon",
                    "--stacktrace",
                    "--no-build-cache",
                    ":app:testDebugUnitTest",
                    ":app:lintDebug",
                    ":app:assembleDebug",
                    ":app:assembleRelease",
                ],
            ],
        )
        errexit = re.search(r"(?m)^\s*set -euo pipefail\s*$", build_step)
        self.assertIsNotNone(errexit)
        assert errexit is not None
        self.assertLess(errexit.start(), build_step.index("./gradlew"))
        self.assertNotRegex(build_step, r"(?m)^\s*set\s+\+e\b")

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

    def test_smoke_reuses_fail_closed_apk_identity_and_signer_verifier(self) -> None:
        verifier_call = 'python3 "${REPO_ROOT}/scripts/verify-release-apk.py"'
        self.assertIn(verifier_call, SMOKE)
        for option in (
            "--expected-package",
            "--expected-version-code",
            "--expected-version-name",
            "--expected-cert-sha256",
        ):
            self.assertIn(option, SMOKE)
        self.assertLess(SMOKE.find(verifier_call), SMOKE.find('adb install --no-streaming'))
        self.assertNotIn("Signer #1 certificate SHA-256 digest", SMOKE)

    def test_real_llm_task_never_reuses_stale_test_outputs(self) -> None:
        task = braced_block_after(
            APP_BUILD,
            'tasks.register<Test>("realLlmIntegrationTest")',
        )
        self.assertIn("outputs.upToDateWhen { false }", task)
        self.assertIn("outputs.cacheIf { false }", task)

    def test_publish_is_idempotent_and_checks_existing_asset_hashes(self) -> None:
        publish_job = RELEASE.split("\n  publish:", 1)[1]
        self.assertIn("gh release view", publish_job)
        self.assertIn("gh release download", publish_job)
        self.assertIn("sha256sum", publish_job)
        self.assertIn("gh release upload", publish_job)
        self.assertIn("gh release create", publish_job)

    def test_publish_keeps_release_draft_until_all_assets_are_verified(self) -> None:
        publish_job = RELEASE.split("\n  publish:", 1)[1]
        create_idx = publish_job.index("gh release create")
        upload_idx = publish_job.index("gh release upload")
        verification_idx = publish_job.index("上传后 Release 资产")
        publish_idx = publish_job.index("gh release edit")
        self.assertIn("--draft", publish_job[create_idx:upload_idx])
        self.assertLess(create_idx, upload_idx)
        self.assertLess(upload_idx, verification_idx)
        self.assertLess(verification_idx, publish_idx)
        self.assertIn("--draft=false", publish_job[publish_idx:])
        self.assertIn("--notes-file docs/release/v0.2-beta.md", publish_job[publish_idx:])
        self.assertIn("--latest=false", publish_job[publish_idx:])

    def test_validate_release_input_job_calls_validator_early(self) -> None:
        validate_job = RELEASE.split("  validate-release-inputs:", 1)[1].split("  device-e2e:", 1)[0]
        checkout_idx = validate_job.find("- name: 检出候选提交或已存在的发行标签")
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
        required_markers = "\n".join(REQUIRED_LEDGER_MARKERS)
        accepted_documents = (
            (f"> 状态：GO\n{required_markers}\n- 最终结论：GO\n", "> 发布状态：GO\n"),
            (f"> 状态：**GO**\n{required_markers}\n- 最终结论：`GO`\n", "> 发布状态：**GO**\n"),
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
        invalid_values = (
            "PENDING",
            "NO-GO",
            "NOT GO",
            "NO GO",
            "ONGOING",
            "GOING",
            "GO / PASS",
            "GO / PENDING",
        )
        required_markers = "\n".join(REQUIRED_LEDGER_MARKERS)
        for invalid_value in invalid_values:
            documents = (
                (
                    f"> 状态：{invalid_value}\n{required_markers}\n- 最终结论：GO\n",
                    "> 发布状态：GO\n",
                ),
                (
                    f"> 状态：GO\n{required_markers}\n- 最终结论：{invalid_value}\n",
                    "> 发布状态：GO\n",
                ),
                (
                    f"> 状态：GO\n{required_markers}\n- 最终结论：GO\n",
                    f"> 发布状态：{invalid_value}\n",
                ),
            )
            for ledger, notes in documents:
                with self.subTest(value=invalid_value, ledger=ledger, notes=notes):
                    result = self.run_release_validator(ledger, notes)
                    self.assertNotEqual(result.returncode, 0)

    def test_validate_release_readiness_requires_explicit_beta_risk_and_evidence_markers(self) -> None:
        required_markers = "\n".join(REQUIRED_LEDGER_MARKERS)
        valid_ledger = f"> 状态：GO\n{required_markers}\n- 最终结论：GO\n"
        for missing_marker in REQUIRED_LEDGER_MARKERS:
            with self.subTest(missing_marker=missing_marker):
                result = self.run_release_validator(
                    valid_ledger.replace(f"{missing_marker}\n", ""),
                    "> 发布状态：GO\n",
                )
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
