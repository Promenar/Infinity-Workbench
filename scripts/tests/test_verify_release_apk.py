"""发行 APK 制品验证器的回归测试。"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "verify-release-apk.py"
SPEC = importlib.util.spec_from_file_location("verify_release_apk", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
verify_release_apk = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify_release_apk)


class LocalInferenceArtifactVerificationTest(unittest.TestCase):
    def make_apk(self, entries: list[str]) -> Path:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        apk = Path(temporary_directory.name) / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for entry in entries:
                archive.writestr(entry, b"fixture")
        return apk

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


if __name__ == "__main__":
    unittest.main()
