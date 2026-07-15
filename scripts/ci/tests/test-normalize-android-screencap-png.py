#!/usr/bin/env python3
"""Android screencap PNG alpha 归一化工具的离线契约测试。"""

from __future__ import annotations

import binascii
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "normalize-android-screencap-png.py"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    crc = binascii.crc32(chunk_type)
    crc = binascii.crc32(data, crc) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", crc)


def paeth(left: int, up: int, upper_left: int) -> int:
    prediction = left + up - upper_left
    left_distance = abs(prediction - left)
    up_distance = abs(prediction - up)
    upper_left_distance = abs(prediction - upper_left)
    if left_distance <= up_distance and left_distance <= upper_left_distance:
        return left
    if up_distance <= upper_left_distance:
        return up
    return upper_left


def encode_scanline(raw: bytes, previous: bytes, filter_type: int) -> bytes:
    filtered = bytearray(len(raw))
    for index, value in enumerate(raw):
        left = raw[index - 4] if index >= 4 else 0
        up = previous[index] if previous else 0
        upper_left = previous[index - 4] if previous and index >= 4 else 0
        if filter_type == 0:
            predictor = 0
        elif filter_type == 1:
            predictor = left
        elif filter_type == 2:
            predictor = up
        elif filter_type == 3:
            predictor = (left + up) // 2
        elif filter_type == 4:
            predictor = paeth(left, up, upper_left)
        else:
            raise ValueError(filter_type)
        filtered[index] = (value - predictor) & 0xFF
    return bytes([filter_type]) + bytes(filtered)


def make_rgba_png(filters: list[int]) -> tuple[bytes, list[bytes]]:
    width = 3
    rows: list[bytes] = []
    encoded = bytearray()
    previous = b""
    for row_index, filter_type in enumerate(filters):
        row = bytes(
            (row_index * 37 + pixel_index * 19 + channel * 53) & 0xFF
            for pixel_index in range(width)
            for channel in range(4)
        )
        rows.append(row)
        encoded.extend(encode_scanline(row, previous, filter_type))
        previous = row
    ihdr = struct.pack(">IIBBBBB", width, len(rows), 8, 6, 0, 0, 0)
    compressed = zlib.compress(bytes(encoded))
    midpoint = max(1, len(compressed) // 2)
    png = b"".join(
        (
            PNG_SIGNATURE,
            png_chunk(b"IHDR", ihdr),
            png_chunk(b"IDAT", compressed[:midpoint]),
            png_chunk(b"IDAT", compressed[midpoint:]),
            png_chunk(b"IEND", b""),
        )
    )
    return png, rows


def parse_rgba_png(payload: bytes) -> tuple[int, int, list[bytes]]:
    if not payload.startswith(PNG_SIGNATURE):
        raise AssertionError("PNG signature 无效")
    offset = len(PNG_SIGNATURE)
    width = height = None
    idat = bytearray()
    while offset < len(payload):
        length = struct.unpack(">I", payload[offset : offset + 4])[0]
        chunk_type = payload[offset + 4 : offset + 8]
        data_start = offset + 8
        data_end = data_start + length
        data = payload[data_start:data_end]
        expected_crc = struct.unpack(">I", payload[data_end : data_end + 4])[0]
        actual_crc = binascii.crc32(chunk_type)
        actual_crc = binascii.crc32(data, actual_crc) & 0xFFFFFFFF
        if expected_crc != actual_crc:
            raise AssertionError(f"{chunk_type!r} CRC 无效")
        offset = data_end + 4
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, compression, filter_method, interlace = struct.unpack(
                ">IIBBBBB", data
            )
            if (bit_depth, color_type, compression, filter_method, interlace) != (8, 6, 0, 0, 0):
                raise AssertionError("输出 PNG 格式不符合 RGBA8 非交错契约")
        elif chunk_type == b"IDAT":
            idat.extend(data)
        elif chunk_type == b"IEND":
            if data or offset != len(payload):
                raise AssertionError("IEND 或尾随数据无效")
            break
    if width is None or height is None:
        raise AssertionError("缺少 IHDR")
    decoded = zlib.decompress(bytes(idat))
    stride = width * 4
    rows: list[bytes] = []
    cursor = 0
    previous = bytes(stride)
    for _ in range(height):
        filter_type = decoded[cursor]
        filtered = decoded[cursor + 1 : cursor + 1 + stride]
        cursor += stride + 1
        raw = bytearray(stride)
        for index, value in enumerate(filtered):
            left = raw[index - 4] if index >= 4 else 0
            up = previous[index]
            upper_left = previous[index - 4] if index >= 4 else 0
            if filter_type == 0:
                predictor = 0
            elif filter_type == 1:
                predictor = left
            elif filter_type == 2:
                predictor = up
            elif filter_type == 3:
                predictor = (left + up) // 2
            elif filter_type == 4:
                predictor = paeth(left, up, upper_left)
            else:
                raise AssertionError(f"未知 filter {filter_type}")
            raw[index] = (value + predictor) & 0xFF
        row = bytes(raw)
        rows.append(row)
        previous = row
    if cursor != len(decoded):
        raise AssertionError("解压数据存在尾随字节")
    return width, height, rows


class NormalizeAndroidScreencapPngTest(unittest.TestCase):
    def run_tool(self, input_path: Path, output_path: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(input_path), str(output_path)],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_filters_zero_to_four_preserve_rgb_and_force_opaque_alpha(self) -> None:
        source, source_rows = make_rgba_png([0, 1, 2, 3, 4])
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "input.png"
            output_path = Path(temp_dir) / "output.png"
            input_path.write_bytes(source)

            result = self.run_tool(input_path, output_path)

            self.assertEqual(0, result.returncode, result.stderr)
            width, height, output_rows = parse_rgba_png(output_path.read_bytes())
            self.assertEqual((3, 5), (width, height))
            for source_row, output_row in zip(source_rows, output_rows, strict=True):
                self.assertEqual(source_row[0::4], output_row[0::4])
                self.assertEqual(source_row[1::4], output_row[1::4])
                self.assertEqual(source_row[2::4], output_row[2::4])
                self.assertEqual(bytes([255]) * width, output_row[3::4])

    def test_same_input_and_output_path_is_atomically_replaced(self) -> None:
        source, source_rows = make_rgba_png([4, 3])
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "same.png"
            path.write_bytes(source)

            result = self.run_tool(path, path)

            self.assertEqual(0, result.returncode, result.stderr)
            _, _, output_rows = parse_rgba_png(path.read_bytes())
            self.assertEqual(source_rows[0][0::4], output_rows[0][0::4])
            self.assertEqual(bytes([255]) * 3, output_rows[0][3::4])

    def test_bad_crc_fails_without_overwriting_existing_output(self) -> None:
        source, _ = make_rgba_png([0])
        corrupted = bytearray(source)
        corrupted[-8] ^= 0x01
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "bad.png"
            output_path = Path(temp_dir) / "evidence.png"
            input_path.write_bytes(corrupted)
            output_path.write_bytes(b"KEEP")

            result = self.run_tool(input_path, output_path)

            self.assertNotEqual(0, result.returncode)
            self.assertTrue(result.stderr.strip())
            self.assertEqual(b"KEEP", output_path.read_bytes())
            self.assertEqual([], list(Path(temp_dir).glob(".evidence.png.*.tmp")))

    def test_unsupported_color_type_and_trailing_data_are_rejected(self) -> None:
        source, _ = make_rgba_png([0])
        ihdr_start = len(PNG_SIGNATURE) + 8
        unsupported = bytearray(source)
        unsupported[ihdr_start + 9] = 2
        ihdr_type_start = len(PNG_SIGNATURE) + 4
        ihdr_data = bytes(unsupported[ihdr_start : ihdr_start + 13])
        crc = binascii.crc32(b"IHDR")
        crc = binascii.crc32(ihdr_data, crc) & 0xFFFFFFFF
        unsupported[ihdr_start + 13 : ihdr_start + 17] = struct.pack(">I", crc)
        self.assertEqual(b"IHDR", unsupported[ihdr_type_start : ihdr_type_start + 4])

        with tempfile.TemporaryDirectory() as temp_dir:
            for name, payload in (
                ("unsupported.png", bytes(unsupported)),
                ("trailing.png", source + b"TRAIL"),
            ):
                input_path = Path(temp_dir) / name
                output_path = Path(temp_dir) / f"{name}.out"
                input_path.write_bytes(payload)
                result = self.run_tool(input_path, output_path)
                self.assertNotEqual(0, result.returncode, name)
                self.assertTrue(result.stderr.strip(), name)
                self.assertFalse(output_path.exists(), name)


if __name__ == "__main__":
    unittest.main(verbosity=2)
