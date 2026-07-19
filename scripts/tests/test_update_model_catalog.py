"""models.dev 离线目录脚本的 TDD 回归测试。"""

from __future__ import annotations

import json
import argparse
import re
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch
import importlib.util
import textwrap


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "model-catalog" / "update-model-catalog.py"
SPEC = importlib.util.spec_from_file_location("update_model_catalog", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def make_manifest(**overrides: object) -> dict[str, object]:
    manifest: dict[str, object] = {
        "source": MODULE.MANIFEST_SOURCE,
        "license": MODULE.LICENSE,
        "fetchedAt": "2026-07-17T00:00:00Z",
        "sourceSha256": "0" * 64,
        "catalogSha256": "0" * 64,
        "sourceBytes": 0,
        "recordCount": 0,
        "schemaVersion": MODULE.SCHEMA_VERSION,
        "unknownFieldCount": 0,
    }
    manifest.update(overrides)
    return manifest


DIFF_KEYS: list[str] = [
    "added",
    "removed",
    "renamed",
    "reasoning_changed",
    "context_changed",
    "output_changed",
    "deprecated_changed",
    "exact_id_conflicts",
    "overrides_covered_by_upstream",
    "total_record_count",
    "unknown_field_count",
]


class NormalizeModelCatalogTestCase(unittest.TestCase):
    def test_normalize_keeps_exact_identity_and_reasoning(self) -> None:
        source = {
            "vendor/model-reasoning-2026": {
                "id": "vendor/model-reasoning-2026",
                "name": "Model Reasoning 2026",
                "family": "model",
                "reasoning": True,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 131072, "output": 8192},
                "modalities": {"input": ["text"], "output": ["text"]},
            }
        }
        normalized = MODULE.normalize_catalog(source)
        self.assertEqual(normalized[0]["canonicalModelId"], "vendor/model-reasoning-2026")
        self.assertEqual(normalized[0]["displayName"], "Model Reasoning 2026")
        self.assertTrue(normalized[0]["reasoning"])
        self.assertEqual(normalized[0]["contextTokens"], 131072)
        self.assertNotIn("inputTokens", normalized[0])

    def test_normalize_missing_structured_output_becomes_none(self) -> None:
        source = {
            "vendor/model-optional": {
                "id": "vendor/model-optional",
                "name": "Optional Structured",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1024, "output": 1024},
                "modalities": {"input": ["text"], "output": ["text"]},
            }
        }
        normalized = MODULE.normalize_catalog(source)
        self.assertIsNone(normalized[0]["structured_output"])

    def test_normalize_rejects_non_bool_structured_output(self) -> None:
        source = {
            "vendor/model-bad-flag": {
                "id": "vendor/model-bad-flag",
                "name": "Bad Flag",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": "yes",
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1024, "output": 1024},
                "modalities": {"input": ["text"], "output": ["text"]},
            }
        }
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "structured_output 必须为布尔值"):
            MODULE.normalize_catalog(source)

    def test_normalize_maps_input_limit_to_inputTokens(self) -> None:
        source = {
            "vendor/model-limit": {
                "id": "vendor/model-limit",
                "name": "Model Limit",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1, "input": 2, "output": 3},
                "modalities": {"input": ["text"], "output": ["text"]},
            }
        }
        normalized = MODULE.normalize_catalog(source)
        self.assertEqual(normalized[0]["inputTokens"], 2)
        self.assertEqual(normalized[0]["contextTokens"], 1)
        self.assertEqual(normalized[0]["outputTokens"], 3)

    def test_normalize_rejects_duplicate_canonical_id(self) -> None:
        source = {
            "vendor/model-a": {
                "id": "vendor/model-a",
                "name": "A",
                "modalities": {"input": ["text"], "output": ["text"]},
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1, "output": 1},
            },
            "vendor/model-b": {
                "id": "vendor/model-a",
                "name": "B",
                "modalities": {"input": ["text"], "output": ["text"]},
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1, "output": 1},
            },
            }
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "重复的 canonicalModelId"):
            MODULE.normalize_catalog(source)

    def test_normalize_rejects_duplicate_canonical_id_after_task4_normalization(self) -> None:
        source = {
            "  models/alpha": {
                "id": "  models/alpha",
                "name": "Alpha",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1, "output": 1},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            "alpha::Alpha ": {
                "id": "alpha::Alpha ",
                "name": "Model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1, "output": 1},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        }
        with self.assertRaisesRegex(
            MODULE.ModelCatalogError,
            "canonicalModelId 归一化后重复",
        ):
            MODULE.normalize_catalog(source)

    def test_normalize_keeps_original_canonical_value(self) -> None:
        source = {
            "Models/Alpha": {
                "id": "Models/Alpha",
                "name": "Alpha",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1, "output": 1},
                "modalities": {"input": ["text"], "output": ["text"]},
            }
        }
        normalized = MODULE.normalize_catalog(source)
        self.assertEqual(normalized[0]["canonicalModelId"], "Models/Alpha")

    def test_normalize_rejects_empty_id_or_name(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "canonicalModelId 不能为空"):
            MODULE.normalize_catalog(
                {
                    "": {
                        "id": "",
                        "name": "Model Empty ID",
                        "modalities": {"input": ["text"], "output": ["text"]},
                        "limit": {"context": 1, "output": 1},
                    }
                }
            )

        with self.assertRaisesRegex(MODULE.ModelCatalogError, "displayName 不能为空"):
            MODULE.normalize_catalog(
                {
                    "vendor/empty-name": {
                        "id": "vendor/empty-name",
                        "name": "",
                        "modalities": {"input": ["text"], "output": ["text"]},
                        "limit": {"context": 1, "output": 1},
                    }
                }
            )

    def test_normalize_rejects_negative_token(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "contextTokens 不能为负数"):
            MODULE.normalize_catalog(
                {
                    "vendor/bad": {
                        "id": "vendor/bad",
                        "name": "Bad",
                        "reasoning": False,
                        "attachment": False,
                        "tool_call": False,
                        "structured_output": False,
                        "knowledge": None,
                        "release_date": "2026-01-01",
                        "last_updated": "2026-01-01",
                        "limit": {"context": -1, "output": 1},
                        "modalities": {"input": ["text"], "output": ["text"]},
                    }
                }
            )

    def test_normalize_rejects_excessive_token(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "超过 Int.MAX_VALUE"):
            MODULE.normalize_catalog(
                {
                    "vendor/bad": {
                        "id": "vendor/bad",
                        "name": "Bad",
                        "reasoning": False,
                        "attachment": False,
                        "tool_call": False,
                        "structured_output": False,
                        "knowledge": None,
                        "release_date": "2026-01-01",
                        "last_updated": "2026-01-01",
                        "limit": {"context": MODULE.INT_MAX_VALUE + 1, "output": 1},
                        "modalities": {"input": ["text"], "output": ["text"]},
                    }
                }
            )

    def test_normalize_rejects_invalid_modalities(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "非法 modality"):
            MODULE.normalize_catalog(
                {
                    "vendor/bad": {
                        "id": "vendor/bad",
                        "name": "Bad",
                        "reasoning": False,
                        "attachment": False,
                        "tool_call": False,
                        "structured_output": False,
                        "knowledge": None,
                        "release_date": "2026-01-01",
                        "last_updated": "2026-01-01",
                        "limit": {"context": 1, "output": 1},
                        "modalities": {"input": ["text", "spark"], "output": ["text"]},
                    }
                }
            )

    def test_normalize_rejects_modalities_extra_keys(self) -> None:
        with self.assertRaisesRegex(
            MODULE.ModelCatalogError,
            "modalities 只允许 input/output",
        ):
            MODULE.normalize_catalog(
                {
                    "vendor/bad": {
                        "id": "vendor/bad",
                        "name": "Bad",
                        "reasoning": False,
                        "attachment": False,
                        "tool_call": False,
                        "structured_output": False,
                        "knowledge": None,
                        "release_date": "2026-01-01",
                        "last_updated": "2026-01-01",
                        "limit": {"context": 1, "output": 1},
                        "modalities": {
                            "input": ["text"],
                            "output": ["text"],
                            "bad": ["text"],
                        },
                    }
                }
            )

    def test_normalize_rejects_unknown_output_modality(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "非法 modality"):
            MODULE.normalize_catalog(
                {
                    "vendor/bad": {
                        "id": "vendor/bad",
                        "name": "Bad",
                        "reasoning": False,
                        "attachment": False,
                        "tool_call": False,
                        "structured_output": False,
                        "knowledge": None,
                        "release_date": "2026-01-01",
                        "last_updated": "2026-01-01",
                        "limit": {"context": 1, "output": 1},
                        "modalities": {"input": ["text"], "output": ["binary"]},
                    }
                }
            )

    def test_normalize_allows_non_text_outputs(self) -> None:
        normalized = MODULE.normalize_catalog(
            {
                "vendor/multimodal": {
                    "id": "vendor/multimodal",
                    "name": "Multimodal Model",
                    "reasoning": False,
                    "attachment": False,
                    "tool_call": False,
                    "structured_output": False,
                    "knowledge": None,
                    "release_date": "2026-01-01",
                    "last_updated": "2026-01-01",
                    "limit": {"context": 4096, "output": 1024},
                    "modalities": {
                        "input": ["text", "image", "pdf"],
                        "output": ["audio", "video", "text", "image"],
                    },
                }
            }
        )
        self.assertEqual(normalized[0]["canonicalModelId"], "vendor/multimodal")
        self.assertEqual(normalized[0]["modalities"]["output"], ["audio", "video", "text", "image"])

    def test_normalize_rejects_non_json_like_schema(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "models.json 必须为 object 格式"):
            MODULE.normalize_catalog([{"id": "a", "name": "A"}])

    def test_normalize_builds_exact_id_conflict_map_for_vendor_and_short_alias_collision(self) -> None:
        source = {
            "vendor/foo/bar": {
                "id": "vendor/foo/bar",
                "name": "Vendor Bar",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-18",
                "last_updated": "2026-07-18",
                "limit": {"context": 1, "output": 1},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            "foo/bar": {
                "id": "foo/bar",
                "name": "Short Bar",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-18",
                "last_updated": "2026-07-18",
                "limit": {"context": 1, "output": 1},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        }

        _, _, conflicts = MODULE._normalize_catalog_for_update(source)
        self.assertEqual(
            conflicts,
            {"foo/bar": ["foo/bar", "vendor/foo/bar"]},
        )


class ValidateCatalogFileTestCase(unittest.TestCase):
    def test_validate_catalog_records_rejects_nan_values(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "不能为 NaN"):
            MODULE.validate_normalized_record(
                {
                    "canonicalModelId": "vendor/model",
                    "displayName": "Model",
                    "family": "model",
                    "reasoning": False,
                    "attachment": False,
                    "tool_call": False,
                    "structured_output": False,
                    "knowledge": None,
                    "release_date": "2026-01-01",
                    "last_updated": "2026-01-01",
                    "contextTokens": float("nan"),
                    "outputTokens": 1,
                    "modalities": {"input": ["text"], "output": ["text"]},
                }
            )

    def test_validate_catalog_records_rejects_unknown_fields(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "发现非法字段"):
            MODULE.validate_normalized_record(
                {
                    "canonicalModelId": "vendor/model",
                    "displayName": "Model",
                    "family": "model",
                    "reasoning": False,
                    "tool_call": False,
                    "structured_output": False,
                    "knowledge": None,
                    "contextTokens": 1,
                    "outputTokens": 1,
                    "modalities": {"input": ["text"], "output": ["text"]},
                    "unexpected": True,
                }
            )

    def test_validate_catalog_records_rejects_missing_required_base_fields(self) -> None:
        required_fields = [
            "canonicalModelId",
            "displayName",
            "family",
            "reasoning",
            "attachment",
            "tool_call",
            "structured_output",
            "knowledge",
            "release_date",
            "last_updated",
            "modalities",
        ]
        base_record = {
            "canonicalModelId": "vendor/model",
            "displayName": "Model",
            "family": "model",
            "reasoning": False,
            "attachment": False,
            "tool_call": False,
            "structured_output": False,
            "knowledge": None,
            "release_date": "2026-01-01",
            "last_updated": "2026-01-01",
            "modalities": {"input": ["text"], "output": ["text"]},
            "contextTokens": 1,
            "outputTokens": 1,
        }
        for field in required_fields:
            with self.subTest(field=field):
                record = dict(base_record)
                del record[field]
                with self.assertRaisesRegex(MODULE.ModelCatalogError, "缺少必填字段"):
                    MODULE.validate_normalized_record(record)

    def test_check_rejects_boolean_schemaVersion(self) -> None:
        models = [
            {
                "canonicalModelId": "vendor/model",
                "displayName": "Model",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "modalities": {"input": ["text"], "output": ["text"]},
                "contextTokens": 1,
                "outputTokens": 1,
            }
        ]
        manifest = make_manifest(
            sourceSha256="0" * 64,
            catalogSha256="0" * 64,
            sourceBytes=0,
            recordCount=1,
            schemaVersion=True,
            unknownFieldCount=0,
        )
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "manifest.schemaVersion 非法"):
            with tempfile.TemporaryDirectory() as tmp:
                models_path = Path(tmp) / "models-dev.normalized.json"
                manifest_path = Path(tmp) / "manifest.json"
                models_path.write_bytes(MODULE._canonical_json_bytes(models))
                manifest_path.write_bytes(MODULE._canonical_json_bytes(manifest))
                MODULE.run_check(models_path, manifest_path)

    def test_check_rejects_boolean_record_count(self) -> None:
        models = [
            {
                "canonicalModelId": "vendor/model",
                "displayName": "Model",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "modalities": {"input": ["text"], "output": ["text"]},
                "contextTokens": 1,
                "outputTokens": 1,
            }
        ]
        manifest = make_manifest(
            sourceSha256="0" * 64,
            catalogSha256="0" * 64,
            sourceBytes=0,
            recordCount=True,
            unknownFieldCount=0,
        )
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "manifest.recordCount 必须为非负整数"):
            with tempfile.TemporaryDirectory() as tmp:
                models_path = Path(tmp) / "models-dev.normalized.json"
                manifest_path = Path(tmp) / "manifest.json"
                models_path.write_bytes(MODULE._canonical_json_bytes(models))
                manifest_path.write_bytes(MODULE._canonical_json_bytes(manifest))
                MODULE.run_check(models_path, manifest_path)

    def test_assert_utc_timestamp_rejects_invalid_month(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "manifest.fetchedAt 不是合法 UTC 时间"):
            MODULE._assert_utc_timestamp("2026-99-99T00:00:00Z")

    def test_check_rejects_json_constant_in_catalog_file(self) -> None:
        invalid = '[{"canonicalModelId":"vendor/model","displayName":"Model","family":"model","reasoning":false,"attachment":false,"tool_call":false,"structured_output":false,"modalities":{"input":["text"],"output":["text"]},"contextTokens":NaN,"outputTokens":1}]'
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "非法常量"):
            MODULE._load_json_bytes(invalid.encode("utf-8"))

        manifest = make_manifest(
            sourceSha256="0" * 64,
            catalogSha256="0" * 64,
            sourceBytes=0,
            recordCount=1,
            unknownFieldCount=0,
        )
        with tempfile.TemporaryDirectory() as tmp:
            models_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            models_path.write_text(invalid, encoding="utf-8")
            manifest_path.write_bytes(MODULE._canonical_json_bytes(manifest))
            with self.assertRaisesRegex(MODULE.ModelCatalogError, "非法常量"):
                MODULE.run_check(models_path, manifest_path)

    def test_run_check_rejects_non_sorted_records(self) -> None:
        models = [
            {
                "canonicalModelId": "vendor/zeta",
                "displayName": "Zeta",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "modalities": {"input": ["text"], "output": ["text"]},
                "contextTokens": 1,
                "outputTokens": 1,
            },
            {
                "canonicalModelId": "vendor/alpha",
                "displayName": "Alpha",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "modalities": {"input": ["text"], "output": ["text"]},
                "contextTokens": 1,
                "outputTokens": 1,
            },
        ]
        manifest = make_manifest(
            sourceSha256="0" * 64,
            catalogSha256="0" * 64,
            sourceBytes=0,
            recordCount=2,
            unknownFieldCount=0,
        )
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "canonicalModelId 未按字典序严格排序"):
            with tempfile.TemporaryDirectory() as tmp:
                models_path = Path(tmp) / "models-dev.normalized.json"
                manifest_path = Path(tmp) / "manifest.json"
                models_path.write_bytes(MODULE._canonical_json_bytes(models))
                manifest_path.write_bytes(MODULE._canonical_json_bytes(manifest))
                MODULE.run_check(models_path, manifest_path)

    def test_run_check_rejects_extra_record_field_and_format(self) -> None:
        models = [
            {
                "canonicalModelId": "vendor/model",
                "displayName": "Model",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "modalities": {"input": ["text"], "output": ["text"]},
                "contextTokens": 1,
                "outputTokens": 1,
                "unexpected": "bad",
            }
        ]
        manifest = make_manifest(
            sourceSha256="0" * 64,
            catalogSha256="0" * 64,
            sourceBytes=0,
            recordCount=1,
            unknownFieldCount=0,
        )
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "发现非法字段"):
            with tempfile.TemporaryDirectory() as tmp:
                models_path = Path(tmp) / "models-dev.normalized.json"
                manifest_path = Path(tmp) / "manifest.json"
                models_path.write_bytes(MODULE._canonical_json_bytes(models))
                manifest_path.write_bytes(MODULE._canonical_json_bytes(manifest))
                MODULE.run_check(models_path, manifest_path)


    def test_run_check_rejects_non_deterministic_format(self) -> None:
        models = [
            {
                "canonicalModelId": "vendor/model",
                "displayName": "Model",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "modalities": {"input": ["text"], "output": ["text"]},
                "contextTokens": 1,
                "outputTokens": 1,
            }
        ]
        manifest = make_manifest(
            sourceSha256="0" * 64,
            catalogSha256="0" * 64,
            sourceBytes=0,
            recordCount=1,
            unknownFieldCount=0,
        )
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "未使用确定性 JSON 格式"):
            with tempfile.TemporaryDirectory() as tmp:
                models_path = Path(tmp) / "models-dev.normalized.json"
                manifest_path = Path(tmp) / "manifest.json"
                models_path.write_text(json.dumps(models), encoding="utf-8")
                manifest_path.write_bytes(MODULE._canonical_json_bytes(manifest))
                MODULE.run_check(models_path, manifest_path)

    def test_run_update_rejects_source_size_guard(self) -> None:
        bad_payload = b'{"vendor/model-a": {"id":"vendor/model-a","name":"A","family":"f","limit":{"context":1,"output":1},"modalities":{"input":["text"],"output":["text"]}}}'
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "models-dev.normalized.json"
            manifest = Path(tmp) / "manifest.json"
            with patch.object(
                MODULE,
                "_fetch_source",
                return_value=(bad_payload, MODULE.sha256_hex(bad_payload)),
            ):
                MODULE.run_update("https://models.dev/models.json", models, manifest)
            manifest_payload = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(manifest_payload["recordCount"], 1)
            MODULE.run_check(models, manifest)
            with tempfile.TemporaryDirectory() as tmp:
                models = Path(tmp) / "models-dev.normalized.json"
                manifest = Path(tmp) / "manifest.json"
                with patch.object(
                    MODULE,
                    "_fetch_source",
                    return_value=(bad_payload, MODULE.sha256_hex(bad_payload)),
                ):
                    MODULE.run_update("https://models.dev/models.json", models, manifest)

    def test_run_update_reproducible_with_fixed_258_source_memory_fixture(self) -> None:
        source_records = {}
        for index in range(258):
            model_id = f"vendor/model-{index:03d}"
            source_record = {
                "id": model_id,
                "name": f"Model {index:03d}",
                "family": "model",
                "reasoning": index % 3 == 0,
                "attachment": False,
                "tool_call": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1024 + index, "output": 1024},
                "modalities": {"input": ["text"], "output": ["text"]},
            }
            if index >= 158:
                source_record["structured_output"] = index % 2 == 0
            source_records[model_id] = source_record

        payload = MODULE._canonical_json_bytes(source_records)
        source_sha = MODULE.sha256_hex(payload)

        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "models-dev.normalized.json"
            manifest = Path(tmp) / "manifest.json"
            with patch.object(
                MODULE,
                "_fetch_source",
                return_value=(payload, source_sha),
            ):
                with patch.object(
                    MODULE,
                    "_utc_now_iso",
                    side_effect=["2026-07-17T19:24:01Z", "2026-07-17T19:24:02Z"],
                ):
                    MODULE.run_update("https://models.dev/models.json", models, manifest)
                    first_snapshot = models.read_bytes()
                    first_manifest = json.loads(manifest.read_text(encoding="utf-8"))
                    MODULE.run_update("https://models.dev/models.json", models, manifest)
                    second_snapshot = models.read_bytes()
                    second_manifest = json.loads(manifest.read_text(encoding="utf-8"))

            normalized = json.loads(models.read_text(encoding="utf-8"))
            manifest_payload = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(first_snapshot, second_snapshot)
            first_manifest.pop("fetchedAt", None)
            second_manifest.pop("fetchedAt", None)
            self.assertEqual(first_manifest, second_manifest)
            self.assertEqual(len(normalized), 258)
            self.assertEqual(manifest_payload["recordCount"], 258)
            self.assertIn("structured_output", normalized[0])
            self.assertIsNone(normalized[0]["structured_output"])
            missing = sum(
                1 for record in normalized if record.get("structured_output") is None
            )
            self.assertEqual(missing, 158)

    def test_run_update_rejects_checksum_mismatch(self) -> None:
        source_records = {
            "vendor/model-check": {
                "id": "vendor/model-check",
                "name": "Model Check",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1, "output": 1},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        }
        payload = MODULE._canonical_json_bytes(source_records)
        source_sha = MODULE.sha256_hex(payload)
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "models-dev.normalized.json"
            manifest = Path(tmp) / "manifest.json"
            with patch.object(MODULE, "_fetch_source", return_value=(payload, source_sha)):
                MODULE.run_update("https://models.dev/models.json", models, manifest)
                manifest_payload = json.loads(manifest.read_text(encoding="utf-8"))
                models_payload = models.read_bytes()
                self.assertEqual(manifest_payload["catalogSha256"], MODULE.sha256_hex(models_payload))
                self.assertEqual(manifest_payload["sourceSha256"], source_sha)
                self.assertEqual(manifest_payload["sourceBytes"], len(payload))
                self.assertEqual(manifest_payload["recordCount"], 1)
                self.assertEqual(manifest_payload["schemaVersion"], 2)
                MODULE.run_check(models, manifest)

    def test_run_update_validates_raw_source_before_parse(self) -> None:
        payload = b'{"vendor/model": "bad"}'
        with patch.object(MODULE, "_fetch_source", return_value=(payload, MODULE.sha256_hex(payload))):
            with self.assertRaisesRegex(MODULE.ModelCatalogError, "provider记录"):
                MODULE.run_update("https://models.dev/models.json", Path("/tmp/models.json"), Path("/tmp/manifest.json"))


    def test_write_deterministic_json_creates_parent_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "nested" / "models-dev.normalized.json"
            payload = [
                {"canonicalModelId": "vendor/model", "displayName": "Model", "family": "model"},
                {"canonicalModelId": "vendor/other", "displayName": "Other", "family": "model"},
            ]
            MODULE.write_deterministic_json(models, payload)
            self.assertTrue(models.exists())
            self.assertTrue(models.parent.exists())
            self.assertEqual(models.read_bytes(), MODULE._canonical_json_bytes(payload))

    def test_write_deterministic_json_removes_tmp_on_exception(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "models-dev.normalized.json"
            payload = [{"canonicalModelId": "vendor/model", "displayName": "Model", "family": "model"}]
            with patch.object(MODULE.os, "replace", side_effect=OSError("replace failed")):
                with self.assertRaises(OSError):
                    MODULE.write_deterministic_json(path, payload)
            remains = list(Path(tmp).glob(f".{path.name}*.tmp"))
            self.assertEqual(remains, [])

    def test_write_catalog_and_manifest_atomically_rolls_back_on_manifest_replace_failure(self) -> None:
        source_records = {
            "vendor/new-updated": {
                "id": "vendor/new-updated",
                "name": "New Updated",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-18",
                "last_updated": "2026-07-18",
                "limit": {"context": 10, "output": 5},
                "modalities": {"input": ["text"], "output": ["text"]},
            }
        }
        source_payload = MODULE._canonical_json_bytes(source_records)
        source_sha = MODULE.sha256_hex(source_payload)
        canonical_payload = MODULE._canonical_json_bytes(MODULE.normalize_catalog(source_records))
        canonical_sha = MODULE.sha256_hex(canonical_payload)

        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            output_path.write_bytes(b"[]")
            manifest_path.write_bytes(
                MODULE._canonical_json_bytes(
                    make_manifest(
                        sourceSha256="0" * 64,
                        catalogSha256="0" * 64,
                        sourceBytes=1,
                        recordCount=0,
                        schemaVersion=MODULE.SCHEMA_VERSION,
                        unknownFieldCount=0,
                    )
                )
            )

            original_replace = MODULE.os.replace

            def fail_manifest_replace(tmp_path: Path, target: Path) -> None:
                if target == manifest_path:
                    raise OSError("manifest replace failed")
                original_replace(tmp_path, target)

            old_output = output_path.read_bytes()
            old_manifest = manifest_path.read_bytes()
            with patch.object(MODULE.os, "replace", side_effect=fail_manifest_replace):
                with patch.object(MODULE, "_fetch_source", return_value=(source_payload, source_sha)):
                    with self.assertRaises(OSError):
                        MODULE.run_update(MODULE.SOURCE_URL, output_path, manifest_path)

            self.assertEqual(output_path.read_bytes(), old_output)
            self.assertEqual(manifest_path.read_bytes(), old_manifest)
            tmp_files = list(output_path.parent.glob(f".{output_path.name}*.tmp")) + list(manifest_path.parent.glob(f".{manifest_path.name}*.tmp"))
            self.assertEqual(tmp_files, [])

            manifest_payload = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertNotEqual(manifest_payload["catalogSha256"], canonical_sha)

    def test_write_catalog_and_manifest_atomically_rolls_back_when_snapshot_directory_fsync_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            old_output = b"old snapshot\n"
            old_manifest = b"old manifest\n"
            output_path.write_bytes(old_output)
            manifest_path.write_bytes(old_manifest)

            fsync_calls = 0

            def fail_first_directory_fsync(_path: Path) -> None:
                nonlocal fsync_calls
                fsync_calls += 1
                if fsync_calls == 1:
                    raise OSError("snapshot directory fsync failed")

            with patch.object(MODULE, "_should_fsync_directory", return_value=True):
                with patch.object(MODULE, "_fsync_file", side_effect=fail_first_directory_fsync):
                    with self.assertRaisesRegex(OSError, "snapshot directory fsync failed"):
                        MODULE._write_catalog_and_manifest_atomically(
                            output_path,
                            manifest_path,
                            b"new snapshot\n",
                            b"new manifest\n",
                        )

            self.assertEqual(output_path.read_bytes(), old_output)
            self.assertEqual(manifest_path.read_bytes(), old_manifest)
            self.assertEqual(list(Path(tmp).glob(".*.tmp")), [])

    def test_write_catalog_and_manifest_atomically_rolls_back_when_manifest_directory_fsync_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            old_output = b"old snapshot\n"
            old_manifest = b"old manifest\n"
            output_path.write_bytes(old_output)
            manifest_path.write_bytes(old_manifest)
            fsync_calls = 0

            def fail_second_directory_fsync(_path: Path) -> None:
                nonlocal fsync_calls
                fsync_calls += 1
                if fsync_calls == 2:
                    raise OSError("manifest directory fsync failed")

            with patch.object(MODULE, "_should_fsync_directory", return_value=True):
                with patch.object(MODULE, "_fsync_file", side_effect=fail_second_directory_fsync):
                    with self.assertRaisesRegex(OSError, "manifest directory fsync failed"):
                        MODULE._write_catalog_and_manifest_atomically(
                            output_path,
                            manifest_path,
                            b"new snapshot\n",
                            b"new manifest\n",
                        )

            self.assertEqual(output_path.read_bytes(), old_output)
            self.assertEqual(manifest_path.read_bytes(), old_manifest)
            self.assertEqual(list(Path(tmp).glob(".*.tmp")), [])

    def test_write_catalog_and_manifest_atomically_attempts_both_rollbacks_when_fsync_keeps_failing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            old_output = b"old snapshot\n"
            old_manifest = b"old manifest\n"
            output_path.write_bytes(old_output)
            manifest_path.write_bytes(old_manifest)
            fsync_calls = 0

            def fail_from_second_directory_fsync(_path: Path) -> None:
                nonlocal fsync_calls
                fsync_calls += 1
                if fsync_calls >= 2:
                    raise OSError("persistent directory fsync failure")

            with patch.object(MODULE, "_should_fsync_directory", return_value=True):
                with patch.object(MODULE, "_fsync_file", side_effect=fail_from_second_directory_fsync):
                    with self.assertRaisesRegex(
                        MODULE.ModelCatalogError,
                        "事务写入失败，回滚持久化确认失败",
                    ):
                        MODULE._write_catalog_and_manifest_atomically(
                            output_path,
                            manifest_path,
                            b"new snapshot\n",
                            b"new manifest\n",
                        )

            self.assertEqual(output_path.read_bytes(), old_output)
            self.assertEqual(manifest_path.read_bytes(), old_manifest)
            self.assertEqual(list(Path(tmp).glob(".*.tmp")), [])


class CheckJsonConstantsTestCase(unittest.TestCase):
    def test_load_json_rejects_infinity(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "非法常量"):
            MODULE._load_json_bytes(b'{"v":Infinity}')

    def test_load_json_rejects_negative_infinity(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "非法常量"):
            MODULE._load_json_bytes(b'{"v":-Infinity}')


class CheckJsonDuplicateKeyTestCase(unittest.TestCase):
    def test_load_json_rejects_duplicate_root_key(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "JSON 包含重复键: a"):
            MODULE._load_json_bytes(b'{"a": 1, "a": 2}')

    def test_load_json_rejects_duplicate_record_key(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "JSON 包含重复键: canonicalModelId"):
            MODULE._load_json_bytes(
                b'{"canonicalModelId": "vendor/model", "canonicalModelId": "vendor/model", "name": "Vendor", "reasoning": false, "attachment": false, "tool_call": false, "structured_output": false, "knowledge": null, "release_date": "2026-07-18", "last_updated": "2026-07-18", "limit": {"context": 1, "output": 1}, "modalities": {"input": ["text"], "output": ["text"]}}'
            )

    def test_load_json_rejects_duplicate_nested_limit_key(self) -> None:
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "JSON 包含重复键: context"):
            MODULE._load_json_bytes(
                b'{"canonicalModelId": "vendor/model", "name": "Vendor", "reasoning": false, "attachment": false, "tool_call": false, "structured_output": false, "knowledge": null, "release_date": "2026-07-18", "last_updated": "2026-07-18", "limit": {"context": 1, "context": 2}, "modalities": {"input": ["text"], "output": ["text"]}}'
            )

    def test_run_check_rejects_manifest_duplicate_keys(self) -> None:
        source_models = MODULE._canonical_json_bytes(
            [
                {
                    "canonicalModelId": "vendor/model",
                    "displayName": "Model",
                    "family": "model",
                    "reasoning": False,
                    "attachment": False,
                    "tool_call": False,
                    "structured_output": False,
                    "knowledge": None,
                    "release_date": "2026-07-18",
                    "last_updated": "2026-07-18",
                    "modalities": {"input": ["text"], "output": ["text"]},
                    "contextTokens": 1,
                    "outputTokens": 1,
                }
            ]
        )
        catalog_sha = MODULE.sha256_hex(source_models)
        manifest = make_manifest(
            sourceSha256="0" * 64,
            catalogSha256=catalog_sha,
            sourceBytes=0,
            recordCount=1,
            unknownFieldCount=0,
        )

        with tempfile.TemporaryDirectory() as tmp:
            models_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            models_path.write_bytes(source_models)
            manifest_payload = json.dumps(manifest)
            manifest_text = (
                manifest_payload[: manifest_payload.find('"license"')]
                + '"license": "MIT", "license": "MIT", '
                + manifest_payload[manifest_payload.find('"license"') + len('"license": "MIT", '):]
            )
            manifest_path.write_text(manifest_text, encoding="utf-8")

            with self.assertRaisesRegex(MODULE.ModelCatalogError, "JSON 包含重复键: license"):
                MODULE.run_check(models_path, manifest_path)


class CatalogCheckBehaviorTestCase(unittest.TestCase):
    def test_run_check_rejects_duplicate_canonical_after_task4_normalization(self) -> None:
        models = [
            {
                "canonicalModelId": "Vendor/Model",
                "displayName": "Model One",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "modalities": {"input": ["text"], "output": ["text"]},
                "contextTokens": 1,
                "outputTokens": 1,
            },
            {
                "canonicalModelId": "vendor/model",
                "displayName": "Model Two",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "modalities": {"input": ["text"], "output": ["text"]},
                "contextTokens": 1,
                "outputTokens": 1,
            },
        ]
        catalog_payload = MODULE._canonical_json_bytes(models)
        manifest = make_manifest(
            catalogSha256=MODULE.sha256_hex(catalog_payload),
            sourceSha256="0" * 64,
            sourceBytes=0,
            recordCount=2,
            unknownFieldCount=0,
        )
        with self.assertRaisesRegex(MODULE.ModelCatalogError, "canonicalModelId 归一化后重复"):
            with tempfile.TemporaryDirectory() as tmp:
                models_path = Path(tmp) / "models-dev.normalized.json"
                manifest_path = Path(tmp) / "manifest.json"
                models_path.write_bytes(MODULE._canonical_json_bytes(models))
                manifest_path.write_bytes(MODULE._canonical_json_bytes(manifest))
                MODULE.run_check(models_path, manifest_path)

    def test_run_check_rejects_tampered_display_name(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = json.loads(
                (Path(__file__).resolve().parents[2] / "native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json").read_text(encoding="utf-8")
            )
            manifest = json.loads(
                (Path(__file__).resolve().parents[2] / "native-ui/app/src/main/assets/model-catalog/manifest.json").read_text(encoding="utf-8")
            )
            models_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            models[0]["displayName"] = "tampered"
            models_path.write_text(MODULE._canonical_json_bytes(models).decode("utf-8"), encoding="utf-8")
            manifest_path.write_text(MODULE._canonical_json_bytes(manifest).decode("utf-8"), encoding="utf-8")
            with self.assertRaisesRegex(MODULE.ModelCatalogError, "models 快照 SHA-256 与预期不一致"):
                MODULE.run_check(models_path, manifest_path)

    def test_write_deterministic_json_skips_directory_fsync_on_windows(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "models-dev.normalized.json"
            payload = [{"canonicalModelId": "vendor/model", "displayName": "Model", "family": "model"}]
            with patch.object(MODULE, "_should_fsync_directory", return_value=False):
                with patch.object(MODULE, "_fsync_file") as dir_fsync:
                    with patch.object(MODULE.os, "fsync") as file_fsync:
                        MODULE.write_deterministic_json(path, payload)
                        self.assertEqual(file_fsync.call_count, 1)
                        dir_fsync.assert_not_called()


class KotlinOverrideParsingTestCase(unittest.TestCase):
    def _build_overrides_source(self, path: Path, content: str) -> None:
        path.write_text(textwrap.dedent(content).strip() + "\n", encoding="utf-8")

    def test_parse_overrides_ignores_false_positives_in_comments_and_strings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source_path = Path(tmp) / "NexaraModelOverrides.kt"
            self._build_overrides_source(
                source_path,
                textwrap.dedent(
                    r'''
                    package com.promenar.nexara.data.model.catalog

                    // exactModel(exactAlias = "ignored", canonicalId = "ignored")
                    /*
                     exactModel(exactAlias = "ignored", canonicalId = "ignored")
                     */
                    /*
                     outer /* nested exactModel(exactAlias = "ignored2", canonicalId = "ignored2") */
                     */

                    val commentExample = "exactModel(exactAlias = \\\"ignored3\\\", canonicalId = \\\"ignored3\\\")"
                    val rawExample = """exactModel(exactAlias = "ignored4", canonicalId = "ignored4")"""

                    private fun exactModel(
                        canonicalId: String,
                        exactAlias: String,
                        displayName: String,
                        familyName: String,
                        contextTokenLimit: Int,
                        outputTokenLimit: Int,
                    ) = Unit

                    val quote = '"'

                    exactModel(
                        exactAlias = "vendor/official",
                        canonicalId = "models/vend/official",
                        displayName = "Model Official",
                        familyName = "model",
                        contextTokenLimit = 1024,
                        outputTokenLimit = 1024,
                    )
                    ''',
                ),
            )
            overrides = MODULE.parse_nexara_overrides(source_path)
            self.assertEqual(overrides, [("vendor/official", "models/vend/official")])

    def test_parse_overrides_raises_when_exact_alias_not_literal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source_path = Path(tmp) / "NexaraModelOverrides.kt"
            self._build_overrides_source(
                source_path,
                textwrap.dedent(
                    """
                    package com.promenar.nexara.data.model.catalog

                    fun buildAlias(): String = "vendor/alias"

                    exactModel(
                        exactAlias = buildAlias(),
                        canonicalId = "vendor/model",
                        displayName = "Model",
                        familyName = "model",
                        contextTokenLimit = 1024,
                        outputTokenLimit = 1024,
                    )
                    """
                ),
            )

            with self.assertRaisesRegex(MODULE.ModelCatalogError, "exactAlias 不是字符串字面量"):
                MODULE.parse_nexara_overrides(source_path)

    def test_parse_overrides_parses_escaped_quotes_in_raw_and_quoted_string(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source_path = Path(tmp) / "NexaraModelOverrides.kt"
            self._build_overrides_source(
                source_path,
                textwrap.dedent(
                    r'''
                    package com.promenar.nexara.data.model.catalog

                    exactModel(
                        exactAlias = "vendor/model\"escaped\"",
                        canonicalId = "vendor/\u006dodel",
                        displayName = "Model",
                        familyName = "model",
                        contextTokenLimit = 1024,
                        outputTokenLimit = 1024,
                    )

                    exactModel(
                        exactAlias = """vendor/another""",
                        canonicalId = """models/another""",
                        displayName = "Model",
                        familyName = "model",
                        contextTokenLimit = 1024,
                        outputTokenLimit = 1024,
                    )
                    ''',
                ),
            )
            overrides = MODULE.parse_nexara_overrides(source_path)
            self.assertEqual(
                overrides,
                [
                    ("vendor/model\"escaped\"", "vendor/model"),
                    ("vendor/another", "models/another"),
                ],
            )

    def test_parse_overrides_ignores_commented_named_argument_inside_real_call(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source_path = Path(tmp) / "NexaraModelOverrides.kt"
            self._build_overrides_source(
                source_path,
                textwrap.dedent(
                    '''
                    exactModel(
                        /* exactAlias = "fake/comment", */
                        exactAlias = "real/alias",
                        canonicalId = "real/canonical",
                    )
                    '''
                ),
            )

            self.assertEqual(
                MODULE.parse_nexara_overrides(source_path),
                [("real/alias", "real/canonical")],
            )

    def test_parse_overrides_skips_function_declaration_with_literal_default(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source_path = Path(tmp) / "NexaraModelOverrides.kt"
            self._build_overrides_source(
                source_path,
                textwrap.dedent(
                    '''
                    private fun exactModel(
                        exactAlias: String = "fake/default",
                        canonicalId: String = "fake/canonical",
                    ) = Unit

                    exactModel(
                        exactAlias = "real/alias",
                        canonicalId = "real/canonical",
                    )
                    '''
                ),
            )

            self.assertEqual(
                MODULE.parse_nexara_overrides(source_path),
                [("real/alias", "real/canonical")],
            )


class Task9CatalogDiffTestCase(unittest.TestCase):
    def test_run_update_rejects_noncanonical_source_before_fetch(self) -> None:
        with patch.object(MODULE, "_fetch_source") as fetch_source:
            with self.assertRaisesRegex(
                MODULE.ModelCatalogError,
                "更新来源必须为 https://models.dev/models.json",
            ):
                MODULE.run_update(
                    "https://example.invalid/models.json",
                    Path("/tmp/models-dev.normalized.json"),
                    Path("/tmp/manifest.json"),
                )

        fetch_source.assert_not_called()

    def test_run_update_with_no_change_keeps_manifest_and_snapshot_bytes(self) -> None:
        source_records = {
            "vendor/base": {
                "id": "vendor/base",
                "name": "Model Base",
                "reasoning": True,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-18",
                "last_updated": "2026-07-18",
                "limit": {"context": 1024, "output": 1024},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            "vendor/current": {
                "id": "vendor/current",
                "name": "Model Current",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-18",
                "last_updated": "2026-07-18",
                "limit": {"context": 2048, "output": 1024},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        }
        source_payload = MODULE._canonical_json_bytes(source_records)
        source_sha = MODULE.sha256_hex(source_payload)

        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            diff_path = Path(tmp) / "model-catalog-diff.md"

            with patch.object(
                MODULE,
                "_fetch_source",
                return_value=(source_payload, source_sha),
            ):
                with patch.object(
                    MODULE,
                    "_utc_now_iso",
                    side_effect=["2026-07-18T00:00:00Z", "2026-07-18T00:00:01Z"],
                ):
                    MODULE.run_update(
                        MODULE.SOURCE_URL,
                        output_path,
                        manifest_path,
                        diff_report=diff_path,
                        overrides_source=Path(__file__).resolve().parents[2]
                        / "native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/NexaraModelOverrides.kt",
                    )
                    first_snapshot = output_path.read_bytes()
                    first_manifest = manifest_path.read_bytes()

                    MODULE.run_update(
                        MODULE.SOURCE_URL,
                        output_path,
                        manifest_path,
                        diff_report=diff_path,
                        overrides_source=Path(__file__).resolve().parents[2]
                        / "native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/NexaraModelOverrides.kt",
                    )

            self.assertEqual(output_path.read_bytes(), first_snapshot)
            self.assertEqual(manifest_path.read_bytes(), first_manifest)
            rendered = diff_path.read_text(encoding="utf-8")
            self.assertIn("## added", rendered)
            self.assertIn("- (0)", rendered)

    def test_run_check_rejects_runtime_short_alias_conflict(self) -> None:
        models = [
            {
                "canonicalModelId": canonical,
                "displayName": canonical,
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-18",
                "last_updated": "2026-07-18",
                "modalities": {"input": ["text"], "output": ["text"]},
                "contextTokens": 1,
                "outputTokens": 1,
            }
            for canonical in ("vendor-a/shared", "vendor-b/shared")
        ]
        payload = MODULE._canonical_json_bytes(models)
        manifest = make_manifest(
            sourceBytes=1,
            sourceSha256="1" * 64,
            catalogSha256=MODULE.sha256_hex(payload),
            recordCount=2,
        )
        with tempfile.TemporaryDirectory() as tmp:
            models_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            models_path.write_bytes(payload)
            manifest_path.write_bytes(MODULE._canonical_json_bytes(manifest))
            with self.assertRaisesRegex(MODULE.ModelCatalogError, "exact_id_conflicts"):
                MODULE.run_check(models_path, manifest_path)

    def test_generate_model_catalog_diff_includes_all_keys(self) -> None:
        base = [
            {
                "canonicalModelId": "vendor/model-a",
                "displayName": "Model A",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "contextTokens": 1024,
                "outputTokens": 512,
                "modalities": {"input": ["text"], "output": ["text"]},
            }
        ]
        current = [
            {
                "canonicalModelId": "vendor/model-a",
                "displayName": "Model A",
                "family": "model",
                "reasoning": True,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "contextTokens": 2048,
                "outputTokens": 512,
                "status": "deprecated",
                "modalities": {"input": ["text"], "output": ["text"]},
            }
        ]
        diff = MODULE.generate_model_catalog_diff(base, current, unknown_field_count=3)
        for key in DIFF_KEYS:
            self.assertIn(key, diff, f"缺失差异键：{key}")
        self.assertEqual(diff["renamed"], [])
        self.assertEqual(diff["reasoning_changed"], ["vendor/model-a"])
        self.assertEqual(diff["context_changed"], ["vendor/model-a"])
        self.assertEqual(diff["output_changed"], [])
        self.assertEqual(diff["deprecated_changed"], ["vendor/model-a"])
        self.assertEqual(diff["total_record_count"], 1)
        self.assertEqual(diff["unknown_field_count"], 3)

    def test_generate_model_catalog_diff_render_markdown_stable_keys(self) -> None:
        base = [
            {
                "canonicalModelId": "vendor/model-a",
                "displayName": "Model A",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "contextTokens": 1024,
                "outputTokens": 512,
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            {
                "canonicalModelId": "vendor/model-b",
                "displayName": "Model B",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "contextTokens": 1024,
                "outputTokens": 512,
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        ]
        current = [
            {
                "canonicalModelId": "vendor/model-b",
                "displayName": "Model B",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "contextTokens": 1024,
                "outputTokens": 512,
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            {
                "canonicalModelId": "vendor/model-c",
                "displayName": "Model C",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "contextTokens": 1024,
                "outputTokens": 512,
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        ]
        diff = MODULE.generate_model_catalog_diff(base, current, unknown_field_count=0)
        rendered = MODULE.render_catalog_diff_markdown(diff)
        index = 0
        for key in DIFF_KEYS:
            idx = rendered.find(f"## {key}")
            self.assertGreater(idx, index)
            index = idx
        for key in DIFF_KEYS:
            self.assertIn(f"## {key}", rendered)
        self.assertIn("- vendor/model-a", rendered)
        self.assertIn("total_record_count: 2", rendered)

    def test_parse_overrides_missing_exact_alias_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            overrides_source = Path(tmp) / "NexaraModelOverrides.kt"
            overrides_source.write_text(
                textwrap.dedent(
                    """
                    package com.promenar.nexara.data.model.catalog

                    private fun exactModel(
                        canonicalId: String = "x/y",
                        displayName: String,
                        familyName: String,
                    ) = 0
                    """,
                ).strip()
                + "\n",
                encoding="utf-8",
            )

            source_records = {
                "vendor/model-a": {
                    "id": "vendor/model-a",
                    "name": "Model A",
                    "reasoning": False,
                    "attachment": False,
                    "tool_call": False,
                    "structured_output": False,
                    "knowledge": None,
                    "release_date": "2026-01-01",
                    "last_updated": "2026-01-01",
                    "limit": {"context": 1, "output": 1},
                    "modalities": {"input": ["text"], "output": ["text"]},
                }
            }

            payload = MODULE._canonical_json_bytes(source_records)
            source_sha = MODULE.sha256_hex(payload)
            output_path = Path(tmp) / "models.json"
            manifest_path = Path(tmp) / "manifest.json"
            diff_path = Path(tmp) / "model-catalog-diff.md"

            with patch.object(MODULE, "_fetch_source", return_value=(payload, source_sha)):
                with self.assertRaisesRegex(MODULE.ModelCatalogError, "未解析到任意 exactAlias"):
                    MODULE.run_update(
                        "https://models.dev/models.json",
                        output_path,
                        manifest_path,
                        diff_report=diff_path,
                        overrides_source=overrides_source,
                    )

    def test_run_update_reports_all_diff_keys_when_non_zero(self) -> None:
        source_records = {
            "vendor/model-a": {
                "id": "vendor/model-a",
                "name": "Model A",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 1024, "output": 256},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            "vendor/model-b": {
                "id": "vendor/model-b",
                "name": "Model B",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-01-01",
                "last_updated": "2026-01-01",
                "limit": {"context": 2048, "output": 1024},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        }
        base = MODULE.normalize_catalog(source_records)
        source_payload = MODULE._canonical_json_bytes(source_records)
        source_sha = MODULE.sha256_hex(source_payload)

        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            diff_path = Path(tmp) / "diff.md"
            with patch.object(MODULE, "_fetch_source", return_value=(source_payload, source_sha)):
                with patch.object(
                    MODULE,
                    "load_existing_catalog",
                    return_value=base,
                ):
                    MODULE.run_update(
                        MODULE.SOURCE_URL,
                        output_path,
                        manifest_path,
                        diff_report=diff_path,
                        overrides_source=Path(
                            __file__,
                        ).resolve().parents[2] / "native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/NexaraModelOverrides.kt",
                    )
            rendered = diff_path.read_text(encoding="utf-8")
            for key in DIFF_KEYS:
                self.assertIn(f"## {key}", rendered)
            self.assertIn("total_record_count", rendered)

    def test_generate_model_catalog_diff_non_zero_fixture_with_full_keys(self) -> None:
        base = [
            {
                "canonicalModelId": "vendor/model-a",
                "displayName": "Model A",
                "family": "model",
                "reasoning": True,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-01",
                "last_updated": "2026-07-01",
                "contextTokens": 1024,
                "outputTokens": 512,
                "status": "deprecated",
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            {
                "canonicalModelId": "vendor/model-b",
                "displayName": "Model B",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-01",
                "last_updated": "2026-07-01",
                "contextTokens": 256,
                "outputTokens": 256,
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        ]
        current = [
            {
                "canonicalModelId": "vendor/model-a",
                "displayName": "Model A Renamed",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": True,
                "knowledge": None,
                "release_date": "2026-07-01",
                "last_updated": "2026-07-01",
                "contextTokens": 2048,
                "outputTokens": 1024,
                "status": "ACTIVE",
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            {
                "canonicalModelId": "vendor/model-c",
                "displayName": "Model C",
                "family": "model",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-01",
                "last_updated": "2026-07-01",
                "contextTokens": 128,
                "outputTokens": 64,
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        ]
        exact_id_conflicts = {
            "shared": ["vendor/shared/model", "model/shared/model"],
        }

        diff = MODULE.generate_model_catalog_diff(
            base,
            current,
            unknown_field_count=7,
            exact_id_conflicts=exact_id_conflicts,
            overrides_covered_by_upstream=2,
        )

        self.assertEqual(diff, {
            "added": ["vendor/model-c"],
            "removed": ["vendor/model-b"],
            "renamed": ["vendor/model-a"],
            "reasoning_changed": ["vendor/model-a"],
            "context_changed": ["vendor/model-a"],
            "output_changed": ["vendor/model-a"],
            "deprecated_changed": ["vendor/model-a"],
            "exact_id_conflicts": exact_id_conflicts,
            "overrides_covered_by_upstream": 2,
            "total_record_count": 2,
            "unknown_field_count": 7,
        })

        rendered = MODULE.render_catalog_diff_markdown(diff)
        sections = [
            "added",
            "removed",
            "renamed",
            "reasoning_changed",
            "context_changed",
            "output_changed",
            "deprecated_changed",
            "exact_id_conflicts",
            "overrides_covered_by_upstream",
            "total_record_count",
            "unknown_field_count",
        ]
        last_position = 0
        for key in sections:
            marker = f"## {key}"
            position = rendered.find(marker)
            self.assertGreater(position, last_position)
            last_position = position

        conflict_block = rendered[rendered.find("## exact_id_conflicts") :]
        self.assertIn("- shared", conflict_block)
        self.assertIn("  - model/shared/model", conflict_block)
        self.assertIn("  - vendor/shared/model", conflict_block)
        self.assertIn("- overrides_covered_by_upstream: 2", rendered)
        self.assertIn("- total_record_count: 2", rendered)
        self.assertIn("- unknown_field_count: 7", rendered)

    def test_detect_exact_id_conflict_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source_records = {
                "openai::shared": {
                    "id": "openai::shared",
                    "name": "Shared OpenAI",
                    "reasoning": False,
                    "attachment": False,
                    "tool_call": False,
                    "structured_output": False,
                    "knowledge": None,
                    "release_date": "2026-01-01",
                    "last_updated": "2026-01-01",
                    "limit": {"context": 1, "output": 1},
                    "modalities": {"input": ["text"], "output": ["text"]},
                },
                "xai::shared": {
                    "id": "xai::shared",
                    "name": "Shared XAI",
                    "reasoning": False,
                    "attachment": False,
                    "tool_call": False,
                    "structured_output": False,
                    "knowledge": None,
                    "release_date": "2026-01-01",
                    "last_updated": "2026-01-01",
                    "limit": {"context": 1, "output": 1},
                    "modalities": {"input": ["text"], "output": ["text"]},
                },
            }
            payload = MODULE._canonical_json_bytes(source_records)
            output_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            with patch.object(
                MODULE,
                "_fetch_source",
                return_value=(payload, MODULE.sha256_hex(payload)),
            ):
                with self.assertRaisesRegex(
                    MODULE.ModelCatalogError,
                    "exact_id_conflicts",
                ):
                    MODULE.run_update(
                        MODULE.SOURCE_URL,
                        output_path,
                        manifest_path,
                    )
        self.assertFalse(output_path.exists())

    def test_task_9_red_run_update_accepts_real_snapshot_without_constant_patching(self) -> None:
        source_records = {
            "vendor/real-time-shift-a": {
                "id": "vendor/real-time-shift-a",
                "name": "Real Time Shift A",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-17",
                "last_updated": "2026-07-17",
                "limit": {"context": 1, "output": 1},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            "vendor/real-time-shift-b": {
                "id": "vendor/real-time-shift-b",
                "name": "Real Time Shift B",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-17",
                "last_updated": "2026-07-17",
                "limit": {"context": 2, "output": 2},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        }
        payload = MODULE._canonical_json_bytes(source_records)
        source_sha = MODULE.sha256_hex(payload)
        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            with patch.object(MODULE, "_fetch_source", return_value=(payload, source_sha)):
                try:
                    MODULE.run_update(
                        MODULE.SOURCE_URL,
                        output_path,
                        manifest_path,
                        diff_report=Path(tmp) / "diff.md",
                        overrides_source=Path(
                            __file__,
                        ).resolve().parents[2]
                        / "native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/NexaraModelOverrides.kt",
                    )
                except MODULE.ModelCatalogError as exc:
                    self.fail(
                        "run_update 仍在依赖首版锁值阻断更新，不能进入 manifest-v2 写入路径。"
                        f" 错误: {exc}",
                    )
            self.assertTrue(output_path.exists())
            self.assertTrue(manifest_path.exists())
            first_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(first_manifest["schemaVersion"], 2)
            self.assertIn("sourceBytes", first_manifest)
            self.assertIn("catalogSha256", first_manifest)
            self.assertIn("sourceSha256", first_manifest)
            MODULE.run_check(output_path, manifest_path)

    def test_task_9_red_conflict_fail_closed_only_writes_report_when_diff_report_present(self) -> None:
        source_records = {
            "vendor-a/shared": {
                "id": "vendor-a/shared",
                "name": "Shared A",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-17",
                "last_updated": "2026-07-17",
                "limit": {"context": 1, "output": 1},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
            "vendor-b/shared": {
                "id": "vendor-b/shared",
                "name": "Shared B",
                "reasoning": False,
                "attachment": False,
                "tool_call": False,
                "structured_output": False,
                "knowledge": None,
                "release_date": "2026-07-17",
                "last_updated": "2026-07-17",
                "limit": {"context": 1, "output": 1},
                "modalities": {"input": ["text"], "output": ["text"]},
            },
        }
        payload = MODULE._canonical_json_bytes(source_records)
        source_sha = MODULE.sha256_hex(payload)
        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "models-dev.normalized.json"
            manifest_path = Path(tmp) / "manifest.json"
            diff_report = Path(tmp) / "model-catalog-diff.md"
            with patch.object(MODULE, "_fetch_source", return_value=(payload, source_sha)):
                with self.assertRaisesRegex(
                    MODULE.ModelCatalogError,
                    "exact_id_conflicts",
                ):
                    MODULE.run_update(
                        MODULE.SOURCE_URL,
                        output_path,
                        manifest_path,
                        diff_report=diff_report,
                        overrides_source=Path(
                            __file__,
                        ).resolve().parents[2] / "native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/NexaraModelOverrides.kt",
                    )
            self.assertFalse(output_path.exists())
            self.assertFalse(manifest_path.exists())
            self.assertTrue(diff_report.exists())
            self.assertIn("exact_id_conflicts", diff_report.read_text(encoding="utf-8"))

    def test_task_9_check_mode_rejects_diff_report_and_overrides_source(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = Path(tmp) / "manifest.json"
            models_input = Path(tmp) / "models-dev.normalized.json"

            with patch.object(
                MODULE,
                "parse_args",
                return_value=argparse.Namespace(
                    input=str(models_input),
                    manifest=str(manifest_path),
                    check=True,
                    source_url=MODULE.SOURCE_URL,
                    output=None,
                    diff_report=str(Path(tmp) / "model-catalog-diff.md"),
                    overrides_source=None,
                ),
            ):
                with self.assertRaisesRegex(
                    MODULE.ModelCatalogError,
                    "check 模式不支持 --diff-report 或 --overrides-source",
                ):
                    MODULE.main()

            with patch.object(
                MODULE,
                "parse_args",
                return_value=argparse.Namespace(
                    input=str(models_input),
                    manifest=str(manifest_path),
                    check=True,
                    source_url=MODULE.SOURCE_URL,
                    output=None,
                    diff_report=None,
                    overrides_source=str(
                        Path(__file__).resolve().parents[2]
                        / "native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/NexaraModelOverrides.kt"
                    ),
                ),
            ):
                with self.assertRaisesRegex(
                    MODULE.ModelCatalogError,
                    "check 模式不支持 --diff-report 或 --overrides-source",
                ):
                    MODULE.main()

    def test_task_9_update_mode_requires_diff_report_and_overrides_source(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(
                MODULE,
                "parse_args",
                return_value=argparse.Namespace(
                    input=None,
                    manifest=str(Path(tmp) / "manifest.json"),
                    check=False,
                    source_url=MODULE.SOURCE_URL,
                    output=str(Path(tmp) / "models-dev.normalized.json"),
                    diff_report=None,
                    overrides_source=None,
                ),
            ):
                with self.assertRaisesRegex(
                    MODULE.ModelCatalogError,
                    "更新模式必须同时提供 --diff-report 与 --overrides-source",
                ):
                    MODULE.main()

    def test_task_9_red_workflow_static_contract(self) -> None:
        content = Path(
            __file__
        ).resolve().parents[2] / ".github/workflows/model-catalog-refresh.yml"
        workflow_text = content.read_text(encoding="utf-8")
        self.assertIn("on:", workflow_text)
        self.assertIn("23 3 * * 1", workflow_text)
        self.assertIn("workflow_dispatch", workflow_text)
        self.assertIn(
            "permissions:\n  contents: write\n  pull-requests: write\n\nconcurrency:",
            workflow_text,
        )

        self.assertNotIn("automerge", workflow_text.lower())
        self.assertNotIn("auto-merge", workflow_text.lower())
        self.assertIn("concurrency", workflow_text)
        self.assertIn("group:", workflow_text)
        self.assertIn("cancel-in-progress: false", workflow_text)
        expected_actions = [
            "actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10",
            "actions/setup-python@a309ff8b426b58ec0e2a45f0f869d46889d02405",
            "gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92",
            "peter-evans/create-pull-request@5f6978faf089d4d20b00c7766989d076bb2fc7f1",
        ]
        self.assertEqual(
            re.findall(r"^\s*uses:\s*(\S+)\s*$", workflow_text, re.MULTILINE),
            expected_actions,
        )
        self.assertNotIn("${{ secrets.", workflow_text)
        self.assertNotIn("secrets.", workflow_text)
        self.assertIn("contents: write", workflow_text)
        self.assertIn("pull-requests: write", workflow_text)

        checkout_run = workflow_text.find("actions/checkout")
        self.assertGreaterEqual(checkout_run, 0)
        self.assertIn("persist-credentials: false", workflow_text)
        self.assertIn("fetch-depth: 0", workflow_text)
        self.assertIn("ref: ${{ github.event.repository.default_branch }}", workflow_text)
        self.assertIn("python-version: \"3.x\"", workflow_text)
        self.assertIn("draft: true", workflow_text)
        self.assertIn('commit-message: "chore(model-catalog): refresh snapshot"', workflow_text)
        self.assertIn('title: "chore(model-catalog): refresh catalog snapshot"', workflow_text)
        self.assertIn("body-path: ${{ runner.temp }}/model-catalog/model-catalog-diff.md", workflow_text)
        self.assertIn("branch: ${{ steps.branch.outputs.name }}", workflow_text)
        self.assertIn("base: ${{ github.event.repository.default_branch }}", workflow_text)
        add_paths = workflow_text.split("add-paths: |", 1)[1].split("labels: |", 1)[0]
        self.assertEqual(
            [line.strip() for line in add_paths.splitlines() if line.strip()],
            [
                "native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json",
                "native-ui/app/src/main/assets/model-catalog/manifest.json",
                "docs/legal/THIRD_PARTY_NOTICES.md",
            ],
        )
        self.assertIn("python3 scripts/model-catalog/update-model-catalog.py", workflow_text)
        self.assertIn("python3 -m unittest scripts.tests.test_update_model_catalog", workflow_text)
        self.assertIn("./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.data.model.catalog.*'", workflow_text)
        update_step = re.search(
            r"- name: 更新模型快照并写入差异报告（runner temp）\n(?P<body>(?:\s{8}.*\n)+?)\s+- name: Python 离线校验",
            workflow_text,
            re.MULTILINE,
        )
        self.assertIsNotNone(update_step)
        update_body = update_step.group("body") if update_step else ""
        self.assertIn("updater_exit_code=0", update_body)
        self.assertIn('if [ "$updater_exit_code" -ne 0 ]; then\n            exit "$updater_exit_code"\n', update_body)
        self.assertIn('cat "$RUNNER_TEMP/model-catalog/model-catalog-diff.md"', update_body)
        self.assertIn('cat "$RUNNER_TEMP/model-catalog/model-catalog-diff.md" >> "$GITHUB_STEP_SUMMARY"', update_body)
        self.assertIn('echo "未生成差异报告" >> "$GITHUB_STEP_SUMMARY"', update_body)
        for token in (
            "updater_exit_code=0",
            'if [ -f "$RUNNER_TEMP/model-catalog/model-catalog-diff.md" ]; then',
            'cat "$RUNNER_TEMP/model-catalog/model-catalog-diff.md"',
            'cat "$RUNNER_TEMP/model-catalog/model-catalog-diff.md" >> "$GITHUB_STEP_SUMMARY"',
            'if [ "$updater_exit_code" -ne 0 ]; then',
            'exit "$updater_exit_code"',
        ):
            self.assertIn(token, update_body)
        token_positions = [
            update_body.find(token)
            for token in (
                "updater_exit_code=0",
                'if [ -f "$RUNNER_TEMP/model-catalog/model-catalog-diff.md" ]; then',
                'cat "$RUNNER_TEMP/model-catalog/model-catalog-diff.md"',
                'cat "$RUNNER_TEMP/model-catalog/model-catalog-diff.md" >> "$GITHUB_STEP_SUMMARY"',
                'if [ "$updater_exit_code" -ne 0 ]; then',
                'exit "$updater_exit_code"',
            )
        ]
        self.assertEqual(token_positions, sorted(token_positions))
        ordered_markers = [
            "actions/checkout",
            "更新模型快照并写入差异报告",
            "Python 离线校验",
            "Python 脚本测试",
            "Kotlin 目录目标测试",
            "同步到 draft PR",
        ]
        positions = [workflow_text.find(marker) for marker in ordered_markers]
        self.assertTrue(all(position >= 0 for position in positions))
        self.assertEqual(positions, sorted(positions))


if __name__ == "__main__":
    unittest.main()
