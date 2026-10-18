# 从 nativeLibraryDir 加载改名为 libpyext_<模块名>.so 的标准库原生扩展。
# 打包库目录对 isolatedProcess 可读，因此主进程与隔离进程都无需从数据目录 dlopen。
import importlib.machinery
import importlib.util
import os
import sys

_EXT_DIR = os.environ.get("NEXARA_PYEXT_DIR")


class _PackagedExtensionFinder:
    @classmethod
    def find_spec(cls, name, path=None, target=None):
        if not _EXT_DIR or "." in name:
            return None
        candidate = os.path.join(_EXT_DIR, "libpyext_" + name + ".so")
        if not os.path.exists(candidate):
            return None
        loader = importlib.machinery.ExtensionFileLoader(name, candidate)
        return importlib.util.spec_from_file_location(name, candidate, loader=loader)


if _EXT_DIR:
    sys.meta_path.append(_PackagedExtensionFinder)
