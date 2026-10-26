#!/usr/bin/env python3
"""用合成 ELF 与 APK 夹具验证 16KB 页对齐检查。"""

import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-native-page-alignment.py"
spec = importlib.util.spec_from_file_location("native_page_alignment", SCRIPT)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def elf(*alignments, load=True):
    """最小 ELF64 小端头加程序头表；load=False 时段类型为 PT_DYNAMIC。"""
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", header, 0x20, 64)
    struct.pack_into("<HH", header, 0x36, 56, len(alignments))
    table = bytearray()
    for align in alignments:
        entry = bytearray(56)
        struct.pack_into("<I", entry, 0, check.PT_LOAD if load else 2)
        struct.pack_into("<Q", entry, 0x30, align)
        table += entry
    return bytes(header + table)


class NativePageAlignmentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name) / "app.apk"

    def write(self, entries, stored=()):
        with zipfile.ZipFile(self.apk, "w", zipfile.ZIP_DEFLATED) as apk:
            for name, data in entries.items():
                apk.writestr(name, data, zipfile.ZIP_STORED if name in stored else zipfile.ZIP_DEFLATED)

    def test_accepts_16k_and_larger_alignment(self):
        self.write({"lib/arm64-v8a/liba.so": elf(0x4000, 0x4000), "lib/x86_64/libb.so": elf(0x10000)})
        self.assertEqual(check.check_apk(self.apk), [])

    def test_rejects_any_4k_load_segment(self):
        self.write({"lib/arm64-v8a/libtermux.so": elf(0x4000, 0x1000)})
        problems = check.check_apk(self.apk)
        self.assertEqual(len(problems), 1)
        self.assertIn("libtermux.so", problems[0])
        self.assertIn("0x1000", problems[0])

    def test_ignores_32_bit_abis_and_non_elf_payloads(self):
        self.write({
            "lib/arm64-v8a/liba.so": elf(0x4000),
            "lib/armeabi-v7a/libold.so": elf(0x1000),
            "lib/arm64-v8a/libpystdlib.so": b"PK\x03\x04zip payload",
        })
        self.assertEqual(check.check_apk(self.apk), [])

    def test_rejects_elf_without_load_segment(self):
        self.write({"lib/arm64-v8a/liba.so": elf(0x4000, load=False)})
        self.assertEqual(len(check.check_apk(self.apk)), 1)

    def test_rejects_apk_without_64_bit_native_libraries(self):
        self.write({"lib/armeabi-v7a/libold.so": elf(0x4000)})
        self.assertEqual(len(check.check_apk(self.apk)), 1)

    def test_rejects_stored_library_at_unaligned_zip_offset(self):
        self.write({"lib/arm64-v8a/liba.so": elf(0x4000)}, stored={"lib/arm64-v8a/liba.so"})
        problems = check.check_apk(self.apk)
        self.assertEqual(len(problems), 1)
        self.assertIn("ZIP", problems[0])


if __name__ == "__main__":
    unittest.main()
