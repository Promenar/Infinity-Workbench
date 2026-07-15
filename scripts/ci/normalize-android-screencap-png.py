#!/usr/bin/env python3
"""把 Android screencap 的 RGBA PNG alpha 通道原子归一化为完全不透明。"""

from __future__ import annotations

import binascii
import os
import struct
import sys
import tempfile
import zlib
from pathlib import Path


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_DECOMPRESSED_BYTES = 512 * 1024 * 1024


class PngError(ValueError):
    """输入不是本工具可安全处理的 Android RGBA screencap PNG。"""


def _crc32(chunk_type: bytes, data: bytes) -> int:
    checksum = binascii.crc32(chunk_type)
    return binascii.crc32(data, checksum) & 0xFFFFFFFF


def _chunk(chunk_type: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", _crc32(chunk_type, data))


def _parse_png(payload: bytes) -> tuple[int, int, bytes, bytes]:
    if not payload.startswith(PNG_SIGNATURE):
        raise PngError("PNG signature 无效")

    offset = len(PNG_SIGNATURE)
    ihdr: bytes | None = None
    idat_parts: list[bytes] = []
    saw_idat = False
    idat_closed = False
    saw_iend = False

    while offset < len(payload):
        if len(payload) - offset < 12:
            raise PngError("PNG chunk 头或 CRC 被截断")
        length = struct.unpack(">I", payload[offset : offset + 4])[0]
        chunk_type = payload[offset + 4 : offset + 8]
        data_start = offset + 8
        data_end = data_start + length
        chunk_end = data_end + 4
        if chunk_end > len(payload):
            raise PngError(f"{chunk_type!r} chunk 数据被截断")
        data = payload[data_start:data_end]
        expected_crc = struct.unpack(">I", payload[data_end:chunk_end])[0]
        if expected_crc != _crc32(chunk_type, data):
            raise PngError(f"{chunk_type.decode('ascii', 'replace')} CRC 无效")
        offset = chunk_end

        if ihdr is None and chunk_type != b"IHDR":
            raise PngError("IHDR 必须是第一个 chunk")
        if chunk_type == b"IHDR":
            if ihdr is not None:
                raise PngError("存在重复 IHDR")
            if length != 13:
                raise PngError("IHDR 长度无效")
            ihdr = data
        elif chunk_type == b"IDAT":
            if idat_closed:
                raise PngError("IDAT chunk 必须连续")
            saw_idat = True
            idat_parts.append(data)
        elif chunk_type == b"IEND":
            if saw_iend:
                raise PngError("存在重复 IEND")
            if length != 0:
                raise PngError("IEND 必须为空")
            if not saw_idat:
                raise PngError("IEND 前缺少 IDAT")
            saw_iend = True
            if offset != len(payload):
                raise PngError("IEND 后存在尾随数据")
            break
        else:
            if saw_idat:
                idat_closed = True
            if chunk_type[:1].isupper():
                raise PngError(f"不支持关键 chunk {chunk_type.decode('ascii', 'replace')}")

    if ihdr is None:
        raise PngError("缺少 IHDR")
    if not saw_idat:
        raise PngError("缺少 IDAT")
    if not saw_iend:
        raise PngError("缺少 IEND")

    width, height, bit_depth, color_type, compression, filter_method, interlace = struct.unpack(
        ">IIBBBBB", ihdr
    )
    if width == 0 or height == 0:
        raise PngError("PNG 宽高必须大于零")
    if (bit_depth, color_type, compression, filter_method, interlace) != (8, 6, 0, 0, 0):
        raise PngError("仅支持 8-bit RGBA、标准压缩/过滤、非交错 PNG")
    return width, height, ihdr, b"".join(idat_parts)


def _paeth(left: int, up: int, upper_left: int) -> int:
    prediction = left + up - upper_left
    left_distance = abs(prediction - left)
    up_distance = abs(prediction - up)
    upper_left_distance = abs(prediction - upper_left)
    if left_distance <= up_distance and left_distance <= upper_left_distance:
        return left
    if up_distance <= upper_left_distance:
        return up
    return upper_left


def _decompress_exact(compressed: bytes, expected_size: int) -> bytes:
    if expected_size > MAX_DECOMPRESSED_BYTES:
        raise PngError("PNG 解压尺寸超过 512 MiB 安全上限")
    decompressor = zlib.decompressobj()
    try:
        decoded = decompressor.decompress(compressed, expected_size + 1)
        if len(decoded) > expected_size or decompressor.unconsumed_tail:
            raise PngError("PNG 解压数据超过预期尺寸")
        decoded += decompressor.flush()
    except zlib.error as error:
        raise PngError(f"IDAT zlib 数据无效：{error}") from error
    if not decompressor.eof or decompressor.unused_data:
        raise PngError("IDAT zlib 流不完整或包含尾随数据")
    if len(decoded) != expected_size:
        raise PngError(f"PNG 解压尺寸无效：期望 {expected_size}，实际 {len(decoded)}")
    return decoded


def _unfilter_and_force_opaque(width: int, height: int, compressed: bytes) -> bytes:
    bytes_per_pixel = 4
    stride = width * bytes_per_pixel
    decoded = _decompress_exact(compressed, height * (stride + 1))
    output = bytearray()
    previous = bytes(stride)
    cursor = 0

    for row_index in range(height):
        filter_type = decoded[cursor]
        filtered = decoded[cursor + 1 : cursor + stride + 1]
        cursor += stride + 1
        raw = bytearray(stride)
        for index, value in enumerate(filtered):
            left = raw[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            up = previous[index]
            upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            if filter_type == 0:
                predictor = 0
            elif filter_type == 1:
                predictor = left
            elif filter_type == 2:
                predictor = up
            elif filter_type == 3:
                predictor = (left + up) // 2
            elif filter_type == 4:
                predictor = _paeth(left, up, upper_left)
            else:
                raise PngError(f"第 {row_index + 1} 行使用未知 PNG filter {filter_type}")
            raw[index] = (value + predictor) & 0xFF
        for alpha_index in range(3, stride, bytes_per_pixel):
            raw[alpha_index] = 255
        output.append(0)
        output.extend(raw)
        previous = bytes(raw)
    return bytes(output)


def normalize_png(payload: bytes) -> bytes:
    width, height, ihdr, compressed = _parse_png(payload)
    opaque_scanlines = _unfilter_and_force_opaque(width, height, compressed)
    return b"".join(
        (
            PNG_SIGNATURE,
            _chunk(b"IHDR", ihdr),
            _chunk(b"IDAT", zlib.compress(opaque_scanlines)),
            _chunk(b"IEND", b""),
        )
    )


def normalize_file(input_path: Path, output_path: Path) -> None:
    payload = input_path.read_bytes()
    normalized = normalize_png(payload)
    output_path.parent.mkdir(parents=False, exist_ok=True)
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{output_path.name}.",
            suffix=".tmp",
            dir=output_path.parent,
            delete=False,
        ) as temp_file:
            temp_path = Path(temp_file.name)
            temp_file.write(normalized)
            temp_file.flush()
            os.fsync(temp_file.fileno())
        os.replace(temp_path, output_path)
        temp_path = None
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(f"用法：{Path(argv[0]).name} INPUT_PNG OUTPUT_PNG", file=sys.stderr)
        return 2
    try:
        normalize_file(Path(argv[1]), Path(argv[2]))
    except (OSError, PngError) as error:
        print(f"PNG alpha 归一化失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
