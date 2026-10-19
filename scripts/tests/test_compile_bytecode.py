"""打包 Python 构建期字节码编译脚本的回归测试。"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import marshal
from pathlib import Path
import tempfile
import unittest


SCRIPT_PATH = Path(__file__).resolve().parents[2] / "native-ui" / "app" / "python" / "compile-bytecode.py"
SPEC = importlib.util.spec_from_file_location("compile_bytecode", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
compile_bytecode = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(compile_bytecode)


class CompileBytecodeTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def run_main(self, *args: str) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = compile_bytecode.main(list(args))
        return code, stdout.getvalue(), stderr.getvalue()

    def test_writes_unchecked_hash_pyc_beside_source_with_relative_filename(self) -> None:
        package = self.root / "pkg"
        package.mkdir()
        (package / "__init__.py").write_text("VALUE = 1\n", encoding="utf-8")
        (package / "mod.py").write_text("def f():\n    return 2\n", encoding="utf-8")

        code, stdout, _ = self.run_main("--magic", str(compile_bytecode.host_magic()), str(self.root))

        self.assertEqual(code, 0)
        self.assertIn("已编译 2 个模块", stdout)
        data = (package / "mod.pyc").read_bytes()
        self.assertEqual(data[:4], importlib.util.MAGIC_NUMBER)
        self.assertEqual(int.from_bytes(data[4:8], "little"), 0b01)
        module = marshal.loads(data[16:])
        self.assertEqual(module.co_filename, "pkg/mod.py")
        self.assertNotIn(str(self.root).encode(), data)
        namespace: dict[str, object] = {}
        exec(module, namespace)
        self.assertEqual(namespace["f"](), 2)

    def test_skips_files_that_do_not_compile_and_keeps_compiling_others(self) -> None:
        (self.root / "bad.py").write_text("def broken(:\n", encoding="utf-8")
        (self.root / "good.py").write_text("X = 1\n", encoding="utf-8")

        code, stdout, stderr = self.run_main("--magic", str(compile_bytecode.host_magic()), str(self.root))

        self.assertEqual(code, 0)
        self.assertIn("跳过 1 个", stdout)
        self.assertIn("bad.py", stderr)
        self.assertFalse((self.root / "bad.pyc").exists())
        self.assertTrue((self.root / "good.pyc").exists())

    def test_magic_mismatch_fails_without_writing_bytecode(self) -> None:
        (self.root / "mod.py").write_text("X = 1\n", encoding="utf-8")

        code, _, stderr = self.run_main("--magic", str(compile_bytecode.host_magic() + 1), str(self.root))

        self.assertEqual(code, compile_bytecode.MAGIC_MISMATCH)
        self.assertIn("magic", stderr)
        self.assertFalse((self.root / "mod.pyc").exists())


if __name__ == "__main__":
    unittest.main()
