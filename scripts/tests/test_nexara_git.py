"""内置 git 包装（native-ui/app/python/site/nexara_git.py）的测试。

改写与输出整理逻辑不依赖 dulwich；clone/fetch 抓取范围与工作区类型的端到端测试需要 dulwich，
通过环境变量 NEXARA_DULWICH_PATH（解压后的 dulwich 与 urllib3 wheel 所在目录，可用 os.pathsep 分隔多个）
或已安装的 dulwich 导入，导入失败时跳过。远端为临时目录中构造的本地仓库，不访问网络。
"""

from __future__ import annotations

import importlib.util
import io
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

SITE_DIR = Path(__file__).resolve().parents[2] / "native-ui/app/python/site"
MODULE_PATH = SITE_DIR / "nexara_git.py"
spec = importlib.util.spec_from_file_location("nexara_git", MODULE_PATH)
nexara_git = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nexara_git)


class RewriteTest(unittest.TestCase):
    def test_log_count_shorthand_becomes_max_count(self) -> None:
        self.assertEqual(nexara_git._rewrite("log", ["--oneline", "-5", "src"]), (["--oneline", "-n", "5", "src"], None))
        self.assertEqual(nexara_git._rewrite("log", ["-n", "3"]), (["-n", "3"], None))

    def test_branch_short_listing_options(self) -> None:
        self.assertEqual(nexara_git._rewrite("branch", ["-a"]), (["--all"], None))
        self.assertEqual(nexara_git._rewrite("branch", ["-r"]), (["--remotes"], None))

    def test_add_all_maps_to_dot(self) -> None:
        self.assertEqual(nexara_git._rewrite("add", ["-A"]), (["."], None))

    def test_commit_requires_message(self) -> None:
        rewritten, problem = nexara_git._rewrite("commit", ["-a"])
        self.assertIsNone(rewritten)
        self.assertIn("-m", problem)
        self.assertEqual(nexara_git._rewrite("commit", ["-am", "msg"]), (["-am", "msg"], None))

    def test_ssh_urls_are_detected(self) -> None:
        for url in ("git@github.com:a/b.git", "ssh://git@host/r.git"):
            self.assertTrue(nexara_git._ssh_url(url), url)
        self.assertFalse(nexara_git._ssh_url("https://github.com/a/b.git"))


class ProgressCollapserTest(unittest.TestCase):
    def collapse(self, *chunks: bytes) -> bytes:
        sink = io.BytesIO()
        collapser = nexara_git._ProgressCollapser(sink)
        for chunk in chunks:
            collapser.write(chunk)
        collapser.finish()
        return sink.getvalue()

    def test_keeps_only_final_state_of_carriage_return_updates(self) -> None:
        self.assertEqual(
            self.collapse(b"Counting: 1%\rCounting: 50%\rCounting: 100%, done.\nnext\n"),
            b"Counting: 100%, done.\nnext\n",
        )

    def test_crlf_split_across_writes_is_a_newline(self) -> None:
        self.assertEqual(self.collapse(b"line\r", b"\nafter\n"), b"line\nafter\n")

    def test_trailing_partial_line_is_flushed(self) -> None:
        self.assertEqual(self.collapse(b"writing 0/3\rwriting 3/3"), b"writing 3/3\n")


class ClonePolicyTest(unittest.TestCase):
    def test_depth_implies_single_branch_with_tag_follow(self) -> None:
        policy = nexara_git._clone_policy(["--depth", "1", "https://example.com/r.git"])
        self.assertTrue(policy.single_branch)
        self.assertIsNone(policy.branches)
        self.assertEqual(policy.tags, "follow")
        self.assertTrue(nexara_git._clone_policy(["--depth=3", "u"]).single_branch)

    def test_explicit_single_branch_options(self) -> None:
        policy = nexara_git._clone_policy(["--single-branch", "-b", "dev", "u"])
        self.assertEqual((policy.single_branch, policy.branches), (True, [b"dev"]))
        policy = nexara_git._clone_policy(["--depth", "1", "--no-single-branch", "u"])
        self.assertEqual((policy.single_branch, policy.tags), (False, "all"))
        self.assertEqual(nexara_git._clone_policy(["--depth", "1", "--no-tags", "u"]).tags, "none")

    def test_plain_clone_takes_all_branches_and_tags(self) -> None:
        policy = nexara_git._clone_policy(["--branch=dev", "u"])
        self.assertEqual((policy.single_branch, policy.branches, policy.tags), (False, None, "all"))

    def test_wrapper_only_options_are_not_passed_to_dulwich(self) -> None:
        rewritten, problem = nexara_git._rewrite(
            "clone", ["--depth", "1", "--single-branch", "--no-tags", "--no-single-branch", "https://example.com/r.git"]
        )
        self.assertIsNone(problem)
        self.assertEqual(rewritten, ["--depth", "1", "https://example.com/r.git"])

    def test_fetch_location_skips_option_values(self) -> None:
        self.assertEqual(nexara_git._fetch_location(["--depth", "2", "upstream"]), "upstream")
        self.assertIsNone(nexara_git._fetch_location(["--no-tags", "--depth", "2"]))

    def test_usage_lists_clone_scope_options(self) -> None:
        usage = nexara_git._usage()
        for option in ("--single-branch", "--no-single-branch", "--no-tags", "core.symlinks=false"):
            self.assertIn(option, usage)


def _load_dulwich() -> str:
    """把 NEXARA_DULWICH_PATH 加入 sys.path 后导入 dulwich，返回其所在目录（供子进程的 PYTHONPATH）。"""
    for entry in reversed([part for part in os.environ.get("NEXARA_DULWICH_PATH", "").split(os.pathsep) if part]):
        if entry not in sys.path:
            sys.path.insert(0, entry)
    import dulwich  # noqa: F401  导入失败由调用方转为 skipTest

    return str(Path(dulwich.__file__).resolve().parents[1])


SIGNATURE = b"Test <test@example.com>"


class _RemoteBuilder:
    """直接写对象构造远端，以便在大小写不敏感的文件系统上也能放入仅大小写不同的两个路径。"""

    def __init__(self, path: Path) -> None:
        from dulwich.repo import Repo

        self.repo = Repo.init_bare(str(path), mkdir=True)
        self.store = self.repo.object_store
        self.clock = 1_700_000_000

    def blob(self, data: bytes) -> bytes:
        from dulwich.objects import Blob

        item = Blob.from_string(data)
        self.store.add_object(item)
        return item.id

    def tree(self, entries: dict) -> bytes:
        from dulwich.objects import Tree

        item = Tree()
        for name, (mode, sha) in entries.items():
            item.add(name, mode, sha)
        self.store.add_object(item)
        return item.id

    def commit(self, readme: bytes, parents: list, extra: dict | None = None) -> bytes:
        from dulwich.objects import Commit

        docs = self.tree({
            b"Guide.txt": (0o100644, self.blob(b"upper\n")),
            b"guide.txt": (0o100644, self.blob(b"lower\n")),
        })
        entries = {
            b"README": (0o100644, self.blob(readme)),
            b"link": (0o120000, self.blob(b"README")),
            b"docs": (stat.S_IFDIR, docs),
        }
        entries.update(extra or {})
        self.clock += 60
        item = Commit()
        item.tree = self.tree(entries)
        item.parents = parents
        item.author = item.committer = SIGNATURE
        item.author_time = item.commit_time = self.clock
        item.author_timezone = item.commit_timezone = 0
        item.message = readme
        self.store.add_object(item)
        return item.id

    def annotated(self, name: bytes, target: bytes) -> bytes:
        from dulwich.objects import Commit, Tag

        self.clock += 1
        item = Tag()
        item.name = name
        item.object = (Commit, target)
        item.tagger = SIGNATURE
        item.tag_time = self.clock
        item.tag_timezone = 0
        item.message = name + b"\n"
        self.store.add_object(item)
        self.repo.refs[b"refs/tags/" + name] = item.id
        return item.id


class CloneFetchScopeTest(unittest.TestCase):
    """经 python3 -m nexara_git 跑 clone/fetch，断言抓取的引用、配置与提示。"""

    def setUp(self) -> None:
        try:
            self.dulwich_root = _load_dulwich()
        except ImportError:
            self.skipTest("未找到 dulwich：设置 NEXARA_DULWICH_PATH 指向解压后的 dulwich 与 urllib3 wheel 目录")
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        (self.root / "home").mkdir()
        self.remote = self.root / "remote.git"
        self.build_remote()
        self.remote_url = str(self.remote)

    def build_remote(self) -> None:
        remote = _RemoteBuilder(self.remote)
        self.builder = remote
        m1 = remote.commit(b"main 1\n", [])
        m2 = remote.commit(b"main 2\n", [m1])
        m3 = remote.commit(b"main 3\n", [m2])
        f1 = remote.commit(b"feature 1\n", [m1])
        p1 = remote.commit(b"pull 1\n", [m1])
        refs = remote.repo.refs
        refs[b"refs/heads/main"] = m3
        refs[b"refs/heads/feature"] = f1
        refs[b"refs/pull/1/head"] = p1
        refs.set_symbolic_ref(b"HEAD", b"refs/heads/main")
        remote.annotated(b"v1", m1)
        refs[b"refs/tags/v2-light"] = m2
        remote.annotated(b"v3", m3)
        remote.annotated(b"feat-annotated", f1)
        refs[b"refs/tags/feat-light"] = f1
        self.commits = {"m1": m1, "m2": m2, "m3": m3, "f1": f1, "p1": p1}

    def git(self, *args: str, cwd: Path | None = None, kind: str | None = "project") -> subprocess.CompletedProcess:
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.root / "home"),
            "LANG": "C.UTF-8",
            "PYTHONPATH": os.pathsep.join([str(SITE_DIR), self.dulwich_root]),
            "GIT_CONFIG_NOSYSTEM": "1",
        }
        if kind is not None:
            env["NEXARA_WORKSPACE_KIND"] = kind
        return subprocess.run(
            [sys.executable, "-m", "nexara_git", *args],
            cwd=str(cwd or self.root), env=env, capture_output=True, text=True, timeout=120,
        )

    def clone(self, *options: str, name: str = "work", kind: str | None = "project") -> Path:
        result = self.git("clone", *options, self.remote_url, name, kind=kind)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.last_stderr = result.stderr
        return self.root / name

    def refs_of(self, path: Path) -> tuple[set, set]:
        from dulwich.repo import Repo

        with Repo(str(path)) as repo:
            names = set(repo.refs.allkeys())
        remotes = {name.decode()[len("refs/remotes/origin/"):] for name in names if name.startswith(b"refs/remotes/origin/")}
        tags = {name.decode()[len("refs/tags/"):] for name in names if name.startswith(b"refs/tags/")}
        return remotes - {"HEAD"}, tags

    def has_object(self, path: Path, key: str) -> bool:
        from dulwich.repo import Repo

        with Repo(str(path)) as repo:
            return self.commits[key] in repo.object_store

    def config(self, path: Path, section: tuple, name: bytes) -> bytes | None:
        from dulwich.repo import Repo

        with Repo(str(path)) as repo:
            try:
                return repo.get_config().get(section, name)
            except KeyError:
                return None

    def test_plain_clone_takes_all_branches_and_tags_but_not_pull_refs(self) -> None:
        work = self.clone()
        self.assertEqual(self.refs_of(work), ({"main", "feature"}, {"v1", "v2-light", "v3", "feat-annotated", "feat-light"}))
        self.assertFalse(self.has_object(work, "p1"))
        self.assertEqual(self.config(work, (b"remote", b"origin"), b"fetch"), b"+refs/heads/*:refs/remotes/origin/*")

    def test_depth_one_takes_default_branch_and_tip_tag_only(self) -> None:
        work = self.clone("--depth", "1")
        self.assertEqual(self.refs_of(work), ({"main"}, {"v3"}))
        self.assertTrue(self.has_object(work, "m3"))
        for key in ("m2", "f1", "p1"):
            self.assertFalse(self.has_object(work, key), key)
        self.assertEqual(self.config(work, (b"remote", b"origin"), b"fetch"), b"+refs/heads/main:refs/remotes/origin/main")
        self.assertEqual(self.config(work, (b"branch", b"main"), b"merge"), b"refs/heads/main")
        self.assertEqual((work / "README").read_text(), "main 3\n")
        log = self.git("log", "--oneline", cwd=work)
        self.assertEqual(log.returncode, 0, log.stderr)
        self.assertIn("main 3", log.stdout)
        status = self.git("status", cwd=work)
        self.assertEqual(status.returncode, 0, status.stderr)

    def test_single_branch_follows_tags_reachable_from_branch(self) -> None:
        work = self.clone("--single-branch")
        self.assertEqual(self.refs_of(work), ({"main"}, {"v1", "v2-light", "v3"}))
        self.assertFalse(self.has_object(work, "f1"))

    def test_single_branch_with_branch_option(self) -> None:
        work = self.clone("--single-branch", "-b", "feature")
        self.assertEqual(self.refs_of(work), ({"feature"}, {"v1", "feat-annotated", "feat-light"}))
        self.assertEqual((work / "README").read_text(), "feature 1\n")
        from dulwich.repo import Repo

        with Repo(str(work)) as repo:
            self.assertEqual(repo.refs.read_ref(b"HEAD"), b"ref: refs/heads/feature")

    def test_missing_branch_fails_like_git(self) -> None:
        result = self.git("clone", "--depth", "1", "-b", "nope", self.remote_url, "work")
        self.assertEqual(result.returncode, 128)
        self.assertIn("Remote branch nope not found", result.stderr)
        self.assertFalse((self.root / "work").exists())

    def test_no_tags(self) -> None:
        work = self.clone("--depth", "1", "--no-tags")
        self.assertEqual(self.refs_of(work), ({"main"}, set()))
        self.assertEqual(self.config(work, (b"remote", b"origin"), b"tagOpt"), b"--no-tags")
        work = self.clone("--no-tags", name="all-branches")
        self.assertEqual(self.refs_of(work), ({"main", "feature"}, set()))

    def test_no_single_branch_restores_all_branches_and_tags(self) -> None:
        work = self.clone("--depth", "1", "--no-single-branch")
        self.assertEqual(self.refs_of(work), ({"main", "feature"}, {"v1", "v2-light", "v3", "feat-annotated", "feat-light"}))

    # 单分支克隆后再 fetch 的克隆选项与 fetch 后应有的标签。
    fetch_clone_options = ("--depth", "1")
    fetch_expected_tags = {"v3", "v4"}

    def test_fetch_after_single_branch_clone_stays_on_branch(self) -> None:
        work = self.clone(*self.fetch_clone_options)
        remote = self.builder
        m4 = remote.commit(b"main 4\n", [self.commits["m3"]])
        f2 = remote.commit(b"feature 2\n", [self.commits["f1"]])
        remote.repo.refs[b"refs/heads/main"] = m4
        remote.repo.refs[b"refs/heads/feature"] = f2
        remote.repo.refs[b"refs/heads/new-branch"] = f2
        remote.annotated(b"v4", m4)
        remote.annotated(b"feat-2", f2)
        result = self.git("fetch", cwd=work)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.refs_of(work), ({"main"}, self.fetch_expected_tags))
        from dulwich.repo import Repo

        with Repo(str(work)) as repo:
            self.assertEqual(repo.refs[b"refs/remotes/origin/main"], m4)
            self.assertNotIn(f2, repo.object_store)
        self.assertEqual(self.git("log", "--oneline", cwd=work).returncode, 0)

    def test_fetch_in_full_clone_takes_new_branches(self) -> None:
        work = self.clone()
        f2 = self.builder.commit(b"feature 2\n", [self.commits["f1"]])
        self.builder.repo.refs[b"refs/heads/new-branch"] = f2
        result = self.git("fetch", "origin", "--no-tags", cwd=work)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("new-branch", self.refs_of(work)[0])

    def test_private_clone_prints_backup_hint(self) -> None:
        self.clone("--depth", "1", kind="private")
        self.assertIn("私有工作区", self.last_stderr)
        self.assertIn("备份", self.last_stderr)
        self.clone("--depth", "1", name="missing-kind", kind=None)
        self.assertIn("私有工作区", self.last_stderr)
        self.clone("--depth", "1", name="project", kind="project")
        self.assertNotIn("私有工作区", self.last_stderr)

    def test_shared_clone_writes_config_before_checkout(self) -> None:
        work = self.clone("--depth", "1", kind="shared")
        for name, value in ((b"symlinks", b"false"), (b"filemode", b"false"), (b"ignorecase", b"true")):
            self.assertEqual(self.config(work, (b"core",), name), value, name)
        link = work / "link"
        self.assertFalse(link.is_symlink())
        self.assertEqual(link.read_text(), "README")
        self.assertIn("符号链接", self.last_stderr)
        self.assertIn("link", self.last_stderr)
        self.assertIn("大小写", self.last_stderr)
        self.assertIn("docs/Guide.txt 与 docs/guide.txt", self.last_stderr)
        self.assertNotIn("私有工作区", self.last_stderr)
        status = self.git("status", cwd=work, kind="shared")
        self.assertEqual(status.returncode, 0, status.stderr)

    def test_project_clone_keeps_symlink(self) -> None:
        work = self.clone("--depth", "1")
        self.assertTrue((work / "link").is_symlink())
        self.assertNotIn("符号链接", self.last_stderr)

    def test_shared_init_and_existing_repo_fill_missing_config(self) -> None:
        result = self.git("init", "fresh", kind="shared")
        self.assertEqual(result.returncode, 0, result.stderr)
        fresh = self.root / "fresh"
        self.assertEqual(self.config(fresh, (b"core",), b"filemode"), b"false")
        self.assertEqual(self.config(fresh, (b"core",), b"ignorecase"), b"true")
        self.assertEqual(self.config(fresh, (b"core",), b"symlinks"), b"false")

        self.assertEqual(self.git("init", "existing").returncode, 0)
        existing = self.root / "existing"
        from dulwich.repo import Repo

        with Repo(str(existing)) as repo:
            config = repo.get_config()
            config.set((b"core",), b"symlinks", b"true")
            config.write_to_path()
        result = self.git("status", cwd=existing, kind="shared")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.config(existing, (b"core",), b"symlinks"), b"true")  # 显式设置不覆盖
        self.assertEqual(self.config(existing, (b"core",), b"ignorecase"), b"true")
        self.assertIsNotNone(self.config(existing, (b"core",), b"filemode"))


class HttpCloneFetchScopeTest(CloneFetchScopeTest):
    """同一组用例改经 127.0.0.1 上的 dulwich 智能 HTTP 服务跑，覆盖 HTTP 客户端的 ref_prefix、剥离标签与二次抓取路径。

    dulwich 自带服务端只支持协议 v0，并要求客户端在 shallow 行之后发送 deepen
    （server.UploadPackHandler._handle_shallow_request），向浅仓库做不带 --depth 的 fetch 会返回 500；
    git 的 upload-pack 没有这一限制。因此这里的“克隆后 fetch”用不带 --depth 的单分支克隆。
    """

    fetch_clone_options = ("--single-branch",)
    fetch_expected_tags = {"v1", "v2-light", "v3", "v4"}

    def setUp(self) -> None:
        super().setUp()
        import threading
        from wsgiref.simple_server import WSGIRequestHandler, make_server

        from dulwich.repo import Repo
        from dulwich.server import DictBackend
        from dulwich.web import make_wsgi_chain

        class QuietHandler(WSGIRequestHandler):
            def log_message(self, *args) -> None:
                pass

        served = Repo(str(self.remote))
        self.addCleanup(served.close)
        # dulwich 服务端只在后端对象带 .repo 属性时才响应 include-tag（server.UploadPackHandler.get_tagged），
        # 补上后与 git upload-pack 一样随包附上指向所发提交的附注标签。
        served.repo = served
        self.served = served
        server = make_server("127.0.0.1", 0, make_wsgi_chain(DictBackend({"/": served})), handler_class=QuietHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.remote_url = "http://127.0.0.1:%d/" % server.server_port

    def test_plain_clone_takes_all_branches_and_tags_but_not_pull_refs(self) -> None:
        work = self.clone()
        self.assertEqual(self.refs_of(work), ({"main", "feature"}, {"v1", "v2-light", "v3", "feat-annotated", "feat-light"}))
        self.assertFalse(self.has_object(work, "p1"))

    def test_tag_follow_second_fetch_sends_only_tag_objects(self) -> None:
        # 不响应 include-tag 的服务端（dulwich 在协议 v2 也不请求 include-tag）走第二次抓取补齐附注标签。
        del self.served.repo
        work = self.clone("--single-branch", "-b", "feature")
        self.assertEqual(self.refs_of(work), ({"feature"}, {"v1", "feat-annotated", "feat-light"}))
        from dulwich.objects import Tag
        from dulwich.repo import Repo

        with Repo(str(work)) as repo:
            packs = [list(pack) for pack in repo.object_store.packs]
            tag_packs = [ids for ids in packs if all(isinstance(repo[sha], Tag) for sha in ids)]
            self.assertEqual([len(ids) for ids in tag_packs], [2], packs)


if __name__ == "__main__":
    unittest.main()
