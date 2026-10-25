"""Linux 随包制品的离线发行检查。"""

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location("verify_linux_apk", Path(__file__).resolve().parents[1] / "verify-release-apk.py")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class LinuxReleaseApkTest(unittest.TestCase):
    def test_sideload_requires_both_libraries_and_verified_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            properties = root / "linux-runtime.properties"
            properties.write_text("alpine.asset=linux/rootfs.bin\nalpine.sha256=" + hashlib.sha256(b"rootfs").hexdigest() + "\n")
            entries = {"lib/arm64-v8a/libnxproot.so": b"proot", "lib/arm64-v8a/libnxproot-loader.so": b"loader", "assets/linux/rootfs.bin": b"rootfs"}
            apk = root / "sideload.apk"
            def write(values):
                with zipfile.ZipFile(apk, "w") as archive:
                    for name, data in values.items():
                        archive.writestr(name, data)
            write(entries)
            VERIFIER.verify_linux_runtime(apk, "sideload", properties)
            for missing in entries:
                write({name: data for name, data in entries.items() if name != missing})
                with self.assertRaises(VERIFIER.VerificationError):
                    VERIFIER.verify_linux_runtime(apk, "sideload", properties)
            write({**entries, "assets/linux/rootfs.bin": b"corrupt"})
            with self.assertRaisesRegex(VERIFIER.VerificationError, "SHA-256"):
                VERIFIER.verify_linux_runtime(apk, "sideload", properties)

    def test_play_rejects_any_linux_library_or_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "play.apk"
            for entry in (None, "lib/arm64-v8a/libnxproot.so", "lib/x86_64/libnxproot-loader.so", "assets/linux/alpine-minirootfs.tar.gz.bin", "assets/linux/linux-runtime.properties"):
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr(entry or "AndroidManifest.xml", b"fixture")
                if entry is None:
                    VERIFIER.verify_linux_runtime(apk, "play")
                else:
                    with self.assertRaises(VERIFIER.VerificationError):
                        VERIFIER.verify_linux_runtime(apk, "play")


if __name__ == "__main__":
    unittest.main()
