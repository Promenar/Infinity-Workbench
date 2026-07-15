"""发行 APK 制品验证器的回归测试。"""

from __future__ import annotations

import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "verify-release-apk.py"
SPEC = importlib.util.spec_from_file_location("verify_release_apk", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
verify_release_apk = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify_release_apk)


class ApkFixtureTestCase(unittest.TestCase):
    def make_apk(self, entries: dict[str, bytes] | list[str]) -> Path:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        apk = Path(temporary_directory.name) / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            if isinstance(entries, dict):
                for entry, content in entries.items():
                    archive.writestr(entry, content)
            else:
                for entry in entries:
                    archive.writestr(entry, b"fixture")
        return apk


class LocalInferenceArtifactVerificationTest(ApkFixtureTestCase):
    def test_rejects_local_inference_native_libraries_and_gguf_assets(self) -> None:
        forbidden_entries = (
            "lib/arm64-v8a/libnexara_llama.so",
            "lib/x86_64/libllama.so",
            "lib/armeabi-v7a/libllama_android.so",
            "lib/arm64-v8a/libggml.so",
            "lib/x86/libggml-base.so",
            "assets/models/demo.GGUF",
        )

        for forbidden_entry in forbidden_entries:
            with self.subTest(entry=forbidden_entry):
                apk = self.make_apk(["AndroidManifest.xml", forbidden_entry])
                with self.assertRaisesRegex(
                    verify_release_apk.VerificationError,
                    "本地推理制品",
                ):
                    verify_release_apk.verify_no_local_inference_artifacts(apk)

    def test_allows_ordinary_multi_abi_libraries_and_llama_text(self) -> None:
        apk = self.make_apk(
            [
                "lib/arm64-v8a/libcrypto.so",
                "lib/armeabi-v7a/libcrypto.so",
                "lib/x86/libcrypto.so",
                "lib/x86_64/libcrypto.so",
                "assets/docs/llama-release-notes.txt",
                "assets/model-catalog.json",
            ]
        )

        verify_release_apk.verify_no_local_inference_artifacts(apk)


class ZipAndSizeVerificationTest(ApkFixtureTestCase):
    def test_rejects_corrupt_zip(self) -> None:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        apk = Path(temporary_directory.name) / "broken.apk"
        apk.write_bytes(b"not-a-zip")

        with self.assertRaisesRegex(verify_release_apk.VerificationError, "有效 ZIP"):
            verify_release_apk.verify_zip(apk)

    def test_rejects_structurally_valid_zip_with_bad_crc_entry(self) -> None:
        apk = self.make_apk({"classes.dex": b"fixture-payload"})
        raw = apk.read_bytes()
        self.assertIn(b"fixture-payload", raw)
        apk.write_bytes(raw.replace(b"fixture-payload", b"fixture-payloaD", 1))

        with self.assertRaisesRegex(verify_release_apk.VerificationError, "ZIP 条目损坏"):
            verify_release_apk.verify_zip(apk)

    def test_rejects_empty_and_oversized_apk(self) -> None:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        apk = Path(temporary_directory.name) / "fixture.apk"
        apk.write_bytes(b"")
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "大小不合规"):
            verify_release_apk.verify_file_size(apk, 4)

        apk.write_bytes(b"12345")
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "大小不合规"):
            verify_release_apk.verify_file_size(apk, 4)

    def test_rejects_zip_bomb_layout_before_decompression(self) -> None:
        suspicious = mock.Mock(
            filename="assets/payload.bin",
            file_size=verify_release_apk.MAX_ZIP_ENTRY_UNCOMPRESSED_BYTES + 1,
            compress_size=1,
            is_dir=mock.Mock(return_value=False),
        )
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "解压规模"):
            verify_release_apk.verify_zip_entries([suspicious])


class BadgingVerificationTest(unittest.TestCase):
    def test_accepts_exact_package_identity(self) -> None:
        output = "package: name='com.promenar.nexara.native' versionCode='2' versionName='0.2-beta'\n"
        self.assertEqual(
            verify_release_apk.parse_badging(output),
            {
                "name": "com.promenar.nexara.native",
                "versionCode": "2",
                "versionName": "0.2-beta",
            },
        )

    def test_ignores_android_16_badging_metadata_field_suffixes(self) -> None:
        output = (
            "package: name='com.promenar.nexara.native' versionCode='2' "
            "versionName='0.2-beta' platformBuildVersionName='16' "
            "platformBuildVersionCode='36' compileSdkVersion='36' "
            "compileSdkVersionCodename='16'\n"
        )

        self.assertEqual(
            verify_release_apk.parse_badging(output),
            {
                "name": "com.promenar.nexara.native",
                "versionCode": "2",
                "versionName": "0.2-beta",
            },
        )

    def test_rejects_missing_or_duplicate_badging_fields(self) -> None:
        malformed = (
            "package: name='com.promenar.nexara.native' "
            "versionCode='2' versionCode='3' versionName='0.2-beta'\n"
        )
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "badging"):
            verify_release_apk.parse_badging(malformed)

        with self.assertRaisesRegex(verify_release_apk.VerificationError, "badging"):
            verify_release_apk.parse_badging("application-label:'Nexara'\n")

        duplicated_package_lines = (
            "package: name='com.promenar.nexara.native' versionCode='2' "
            "versionName='0.2-beta'\n"
            "package: name='com.promenar.nexara.native' versionCode='2' "
            "versionName='0.2-beta'\n"
        )
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "badging"):
            verify_release_apk.parse_badging(duplicated_package_lines)

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/aapt"))
    @mock.patch.object(verify_release_apk, "run_tool", return_value="garbled")
    def test_badging_output_drift_fails_closed(self, _run: mock.Mock, _find: mock.Mock) -> None:
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "badging"):
            verify_release_apk.verify_badging(Path("app.apk"), "pkg", "2", "beta")

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/aapt"))
    @mock.patch.object(
        verify_release_apk,
        "run_tool",
        return_value="package: name='pkg' versionCode='3' versionName='beta'\n",
    )
    def test_rejects_wrong_version_identity(self, _run: mock.Mock, _find: mock.Mock) -> None:
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "版本不匹配"):
            verify_release_apk.verify_badging(Path("app.apk"), "pkg", "2", "beta")


class SignatureVerificationTest(unittest.TestCase):
    DIGEST = "ab" * 32

    def test_normalizes_colon_delimited_uppercase_fingerprint(self) -> None:
        delimited = ":".join(self.DIGEST.upper()[i : i + 2] for i in range(0, 64, 2))
        self.assertEqual(verify_release_apk.normalize_digest(delimited), self.DIGEST)

    def test_rejects_invalid_expected_fingerprint_before_invoking_tool(self) -> None:
        with mock.patch.object(verify_release_apk, "run_tool") as run:
            with self.assertRaisesRegex(verify_release_apk.VerificationError, "预期签名"):
                verify_release_apk.verify_signature(Path("app.apk"), "not-a-digest")
            run.assert_not_called()

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/apksigner"))
    @mock.patch.object(verify_release_apk, "run_tool")
    def test_accepts_build_tools_37_signature_output(
        self,
        run: mock.Mock,
        _find: mock.Mock,
    ) -> None:
        run.return_value = (
            "Verifies\n"
            "Number of signers: 1\n"
            f"V2 Signer: certificate SHA-256 digest: {self.DIGEST}\n"
        )

        verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/apksigner"))
    @mock.patch.object(verify_release_apk, "run_tool")
    def test_rejects_build_tools_37_output_without_declared_signer_count(
        self,
        run: mock.Mock,
        _find: mock.Mock,
    ) -> None:
        run.return_value = f"V2 Signer: certificate SHA-256 digest: {self.DIGEST}\n"

        with self.assertRaisesRegex(verify_release_apk.VerificationError, "单一 signer"):
            verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/apksigner"))
    @mock.patch.object(verify_release_apk, "run_tool")
    def test_rejects_wrong_certificate_and_multiple_signers(
        self,
        run: mock.Mock,
        _find: mock.Mock,
    ) -> None:
        run.return_value = f"Signer #1 certificate SHA-256 digest: {'cd' * 32}\n"
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "不一致"):
            verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

        run.return_value = (
            f"Signer #1 certificate SHA-256 digest: {self.DIGEST}\n"
            f"Signer #2 certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "单一 signer"):
            verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/apksigner"))
    @mock.patch.object(verify_release_apk, "run_tool", return_value="Verified")
    def test_rejects_apksigner_output_without_certificate_digest(
        self,
        _run: mock.Mock,
        _find: mock.Mock,
    ) -> None:
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "未返回签名证书"):
            verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

    @mock.patch.object(verify_release_apk.subprocess, "run")
    def test_tool_failure_is_redacted_and_fails_closed(self, run: mock.Mock) -> None:
        run.return_value = subprocess.CompletedProcess(["tool"], 7, stdout="secret output")
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "退出码 7") as raised:
            verify_release_apk.run_tool(["/sdk/tool", "argument"])
        self.assertNotIn("secret output", str(raised.exception))


class SensitiveContentVerificationTest(ApkFixtureTestCase):
    def test_rejects_each_sensitive_fixture_without_embedding_real_secrets(self) -> None:
        fixtures = {
            "Authorization Bearer": b"Authorization: Bearer token_fixture_0123456789",
            "private key": (
                b"-----BEGIN PRIVATE KEY-----\n"
                + b"A" * 96
                + b"\n-----END PRIVATE KEY-----"
            ),
            "encrypted private key": (
                b"-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                + b"B" * 96
                + b"\n-----END ENCRYPTED PRIVATE KEY-----"
            ),
            "OpenAI-style key": b"sk-fixture_0123456789_ABCDEFGH",
            "Google-style key": b"AIza" + b"F" * 35,
            "prompt fixture": b"NEXARA_PROMPT_FIXTURE=fixture-user-prompt",
            "response fixture": b"NEXARA_RESPONSE_FIXTURE=fixture-model-response",
            "WebDAV password": b"WEBDAV_PASSWORD_FIXTURE=fixture-password",
            "WebDAV plaintext password": b'webdav_password="fixture-password-0123"',
            "WebDAV URL": b"NEXARA_WEBDAV_TEST_URL=https://example.invalid/webdav-test",
            "absolute path": b"/Users/fixture-user/Projects/Nexara",
            "secure env": b"config/secure_env/release.properties",
        }
        for label, value in fixtures.items():
            with self.subTest(label=label):
                apk = self.make_apk({"classes.dex": value})
                with self.assertRaisesRegex(verify_release_apk.VerificationError, "敏感信息"):
                    verify_release_apk.scan_sensitive_content(apk)

    def test_detects_sensitive_value_crossing_read_chunks(self) -> None:
        marker = b"Authorization: Bearer token_fixture_0123456789"
        prefix = b"x" * 13
        findings = verify_release_apk.scan_stream(
            io.BytesIO(prefix + marker),
            "classes.dex",
            chunk_bytes=17,
        )
        self.assertTrue(any(label == "Authorization Bearer 凭证" for _, label in findings))

    def test_does_not_create_artificial_match_across_zip_entries(self) -> None:
        apk = self.make_apk(
            {
                "assets/part-a.txt": b"Authorization: Bearer ",
                "assets/part-b.txt": b"token_fixture_0123456789",
            }
        )
        verify_release_apk.scan_sensitive_content(apk)

    def test_allows_ordinary_library_names_and_non_secret_ui_words(self) -> None:
        apk = self.make_apk(
            {
                "lib/arm64-v8a/libcrypto.so": b"Bearer token help and prompt response UI labels",
                "assets/providers.json": (
                    b'{"provider":"openai","endpointConfigured":false,'
                    b'"pemParserHeader":"-----BEGIN PRIVATE KEY-----"}'
                ),
            }
        )
        verify_release_apk.scan_sensitive_content(apk)


class ChecksumVerificationTest(ApkFixtureTestCase):
    def test_writes_standard_checksum_with_apk_basename(self) -> None:
        apk = self.make_apk({"AndroidManifest.xml": b"manifest"})
        output = apk.with_name("nexara.apk.sha256")
        digest = verify_release_apk.write_checksum(apk, output)

        self.assertEqual(digest, verify_release_apk.sha256_file(apk))
        self.assertEqual(output.read_text(encoding="utf-8"), f"{digest}  {apk.name}\n")


if __name__ == "__main__":
    unittest.main()
