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


class InspectUsageTest(unittest.TestCase):
    """检查入口是 App 内部接口，不出现在给模型看的用法与支持列表中。"""

    def test_inspect_entry_is_hidden_from_model(self) -> None:
        self.assertNotIn("inspect", nexara_git._usage())
        self.assertNotIn("--nexara-inspect", nexara_git.SUPPORTED)

    def test_argument_errors_exit_2_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "out.json"
            for argv in (
                ["status"],
                ["bogus", "--output", str(output)],
                ["read", "--output", str(output)],
                ["commit", "--output", str(output)],
                ["log", "--limit", "0", "--output", str(output)],
            ):
                stderr = io.StringIO()
                original = sys.stderr
                sys.stderr = stderr
                try:
                    code = nexara_git.main(["--nexara-inspect", *argv])
                finally:
                    sys.stderr = original
                self.assertEqual(code, 2, argv)
                self.assertIn("--nexara-inspect", stderr.getvalue())
                self.assertFalse(output.exists(), argv)

    def test_tree_path_rejects_escape(self) -> None:
        for path in ("", "/etc/passwd", "../x", "a/../b", "a//b", "a/./b", "a\\b", "a\0b", "a/"):
            with self.assertRaises(nexara_git._InspectError, msg=path):
                nexara_git._tree_path(path)
        self.assertEqual(nexara_git._tree_path("src/中文.txt"), "src/中文.txt".encode())


class InspectTest(unittest.TestCase):
    """经 python -m nexara_git --nexara-inspect 读取状态、内容与历史，断言 JSON 结构且不改动仓库。"""

    def setUp(self) -> None:
        try:
            self.dulwich_root = _load_dulwich()
        except ImportError:
            self.skipTest("未找到 dulwich：设置 NEXARA_DULWICH_PATH 指向解压后的 dulwich 与 urllib3 wheel 目录")
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        (self.root / "home").mkdir()
        self.work = self.root / "work"
        self.work.mkdir()
        self.clock = 1_700_000_000
        self.outputs = 0

    # —— 构造仓库 ——
    def init(self) -> None:
        from dulwich import porcelain

        porcelain.init(str(self.work)).close()

    def write(self, name: str, data: bytes | str) -> Path:
        target = self.work / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data.encode() if isinstance(data, str) else data)
        return target

    def add(self, *names: str) -> None:
        from dulwich import porcelain

        porcelain.add(str(self.work), paths=[str(self.work / name) for name in names])

    def commit(self, message: str) -> str:
        from dulwich import porcelain

        self.clock += 60
        sha = porcelain.commit(
            str(self.work), message=message.encode(), author=SIGNATURE, committer=SIGNATURE,
            author_timezone=0, commit_timezone=0, commit_timestamp=self.clock, author_timestamp=self.clock,
        )
        return sha.decode()

    def rm(self, *names: str) -> None:
        from dulwich import porcelain

        porcelain.remove(str(self.work), paths=[str(self.work / name) for name in names], cached=True)

    # —— 调用检查入口 ——
    def inspect(self, operation: str, *args: str, kind: str = "project", repo: Path | None = None) -> dict:
        import json

        self.outputs += 1
        output = self.root / ("out-%d.json" % self.outputs)
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.root / "home"),
            "LANG": "C.UTF-8",
            "PYTHONPATH": os.pathsep.join([str(SITE_DIR), self.dulwich_root]),
            "GIT_CONFIG_NOSYSTEM": "1",
            "NEXARA_WORKSPACE_KIND": kind,
        }
        result = subprocess.run(
            [sys.executable, "-m", "nexara_git", "--nexara-inspect", operation, "--output", str(output), *args],
            cwd=str(repo or self.work), env=env, capture_output=True, text=True, timeout=120,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(Path(str(output) + ".tmp").exists())
        data = json.loads(output.read_text(encoding="utf-8"))
        output.unlink()
        self.assertEqual(data["version"], 1)
        return data

    def paths(self, status: dict, group: str) -> dict:
        return {item["path"]: item["change"] for item in status[group]}

    def blob(self, rev: str, path: str) -> dict:
        data = self.inspect("read", "--blob", "%s:%s" % (rev, path))
        self.assertTrue(data["repository"], data)
        self.assertNotIn("error", data)
        (item,) = data["blobs"]
        self.assertEqual((item["rev"], item["path"]), (rev, path))
        return item

    # —— 用例 ——
    def test_non_repository_directory(self) -> None:
        self.assertEqual(self.inspect("status"), {"version": 1, "repository": False})
        self.assertFalse(self.inspect("log")["repository"])

    def test_subdirectory_of_repository_is_not_discovered_upwards(self) -> None:
        self.init()
        (self.work / "sub").mkdir()
        self.assertEqual(self.inspect("status", repo=self.work / "sub"), {"version": 1, "repository": False})

    def test_bare_repository_is_not_a_workspace_repository(self) -> None:
        from dulwich.repo import Repo

        Repo.init_bare(str(self.work / "bare.git"), mkdir=True).close()
        self.assertFalse(self.inspect("status", repo=self.work / "bare.git")["repository"])

    def test_empty_repository(self) -> None:
        self.init()
        status = self.inspect("status")
        self.assertTrue(status["repository"])
        self.assertTrue(status["unborn"])
        self.assertIsNone(status["head"])
        self.assertFalse(status["detached"])
        self.assertIn(status["branch"], ("master", "main"))
        for group in ("staged", "unstaged", "untracked"):
            self.assertEqual(status[group], [], group)
            self.assertFalse(status[group + "_truncated"])
        self.assertEqual(self.inspect("log"), {"version": 1, "repository": True, "commits": [], "truncated": False})
        self.assertFalse(self.blob("HEAD", "a.txt")["exists"])
        self.assertFalse(self.blob("INDEX", "a.txt")["exists"])

    def test_empty_repository_with_staged_and_untracked_files(self) -> None:
        self.init()
        self.write("a.txt", "a\n")
        self.write("b.txt", "b\n")
        self.add("a.txt")
        status = self.inspect("status")
        self.assertEqual(self.paths(status, "staged"), {"a.txt": "added"})
        self.assertEqual(self.paths(status, "untracked"), {"b.txt": "added"})
        self.assertEqual(status["unstaged"], [])
        self.assertEqual(self.blob("INDEX", "a.txt")["text"], "a\n")

    def test_staged_unstaged_and_untracked_groups(self) -> None:
        self.init()
        self.write("a.txt", "one\n")
        self.write("b.txt", "keep\n")
        self.write("c.txt", "remove me\n")
        self.write(".gitignore", "*.log\nbuild/\n")
        self.add("a.txt", "b.txt", "c.txt", ".gitignore")
        head = self.commit("init")
        index_file = self.work / ".git" / "index"
        config_file = self.work / ".git" / "config"

        self.write("a.txt", "one\ntwo\n")
        self.add("a.txt")
        self.write("a.txt", "one\ntwo\nthree\n")  # 同一文件既有已暂存也有未暂存改动
        (self.work / "b.txt").unlink()  # 工作区删除、未暂存
        self.rm("c.txt")  # 已暂存删除，工作区文件保留为未跟踪
        (self.work / "c.txt").unlink()
        self.write("new.txt", "fresh\n")
        self.write("docs/guide.md", "# 指南\n")
        self.write("debug.log", "ignored\n")
        self.write("build/out.bin", b"\0ignored")
        # 内容不变但 stat 漂移：只读检查不得回写索引的 stat 缓存。
        os.utime(self.work / ".gitignore", (self.clock + 10_000, self.clock + 10_000))
        before_index = index_file.read_bytes()
        before_config = config_file.read_bytes()

        status = self.inspect("status")
        self.assertEqual(status["head"], head)
        self.assertFalse(status["unborn"])
        self.assertEqual(self.paths(status, "staged"), {"a.txt": "modified", "c.txt": "deleted"})
        self.assertEqual(self.paths(status, "unstaged"), {"a.txt": "modified", "b.txt": "deleted"})
        self.assertEqual(self.paths(status, "untracked"), {"new.txt": "added", "docs/guide.md": "added"})
        self.assertEqual([item["path"] for item in status["untracked"]], ["docs/guide.md", "new.txt"])
        self.assertEqual(index_file.read_bytes(), before_index)
        self.assertEqual(config_file.read_bytes(), before_config)

        self.assertEqual(self.blob("HEAD", "a.txt")["text"], "one\n")
        self.assertEqual(self.blob("INDEX", "a.txt")["text"], "one\ntwo\n")
        self.assertFalse(self.blob("INDEX", "c.txt")["exists"])
        self.assertEqual(self.blob("HEAD", "c.txt")["text"], "remove me\n")
        both = self.inspect("read", "--blob", "HEAD:a.txt", "--blob", "INDEX:a.txt")["blobs"]
        self.assertEqual([item["text"] for item in both], ["one\n", "one\ntwo\n"])
        item = self.blob("HEAD", "a.txt")
        self.assertEqual((item["mode"], item["size"], item["binary"], item["truncated"]), ("100644", 4, False, False))

    def test_detached_head(self) -> None:
        self.init()
        self.write("a.txt", "a\n")
        self.add("a.txt")
        sha = self.commit("init")
        from dulwich.repo import Repo

        with Repo(str(self.work)) as repo:
            repo.refs.remove_if_equals(b"HEAD", None)
            repo.refs[b"HEAD"] = sha.encode()
        status = self.inspect("status")
        self.assertTrue(status["detached"])
        self.assertIsNone(status["branch"])
        self.assertEqual(status["head"], sha)

    def test_binary_and_oversized_files(self) -> None:
        self.init()
        self.write("image.png", b"\x89PNG\r\n\x1a\n\0\0\0data")
        large = ("x" * 1023 + "\n") * 600  # 600 KiB，超过 512 KiB 上限
        self.write("large.txt", large)
        self.write("edge.txt", "y" * (512 * 1024))  # 恰好等于上限
        self.write("latin.txt", b"caf\xe9\n")
        self.add("image.png", "large.txt", "edge.txt", "latin.txt")
        self.commit("assets")
        image = self.blob("HEAD", "image.png")
        self.assertEqual((image["exists"], image["binary"], image["text"], image["size"]), (True, True, None, 15))
        oversized = self.blob("INDEX", "large.txt")
        self.assertEqual((oversized["truncated"], oversized["text"], oversized["size"]), (True, None, len(large)))
        edge = self.blob("HEAD", "edge.txt")
        self.assertFalse(edge["truncated"])
        self.assertEqual(len(edge["text"]), 512 * 1024)
        self.assertEqual(self.blob("HEAD", "latin.txt")["text"], "caf�\n")

    def test_symlink_and_directory_entries(self) -> None:
        self.init()
        self.write("README", "hello\n")
        os.symlink("README", self.work / "link")
        self.write("dir/file.txt", "f\n")
        self.add("README", "link", "dir/file.txt")
        self.commit("links")
        link = self.blob("HEAD", "link")
        self.assertEqual((link["symlink"], link["text"], link["mode"]), (True, "README", "120000"))
        self.assertFalse(self.blob("HEAD", "dir")["exists"])
        self.assertFalse(self.blob("HEAD", "README/x")["exists"])

    def test_entry_limit_truncates_each_group(self) -> None:
        self.init()
        for number in range(3):
            self.write("u%d.txt" % number, "u\n")
        status = self.inspect("status", "--limit", "2")
        self.assertEqual(len(status["untracked"]), 2)
        self.assertTrue(status["untracked_truncated"])
        self.assertFalse(status["staged_truncated"])
        self.assertFalse(self.inspect("status", "--limit", "3")["untracked_truncated"])

    def test_log_lists_newest_first_with_limit(self) -> None:
        self.init()
        shas = []
        for number in range(3):
            self.write("a.txt", "v%d\n" % number)
            self.add("a.txt")
            shas.append(self.commit("提交 %d\n\n正文 %d" % (number, number)))
        log = self.inspect("log", "--limit", "2")
        self.assertTrue(log["truncated"])
        self.assertEqual([entry["sha"] for entry in log["commits"]], [shas[2], shas[1]])
        first = log["commits"][0]
        self.assertEqual(first["short_sha"], shas[2][:7])
        self.assertEqual((first["author_name"], first["author_email"]), ("Test", "test@example.com"))
        self.assertEqual(first["title"], "提交 2")
        self.assertEqual(first["parents"], [shas[1]])
        self.assertEqual(first["author_time"], self.clock)
        self.assertEqual(first["author_tz"], 0)
        full = self.inspect("log")
        self.assertFalse(full["truncated"])
        self.assertEqual(len(full["commits"]), 3)
        self.assertEqual(full["commits"][-1]["parents"], [])

    def test_commit_changes_and_both_sides(self) -> None:
        self.init()
        body = "".join("line %d of the moved file\n" % number for number in range(40))
        self.write("keep.txt", "one\n")
        self.write("gone.txt", "bye\n")
        self.write("old/name.txt", body)
        self.add("keep.txt", "gone.txt", "old/name.txt")
        root = self.commit("root")
        self.write("keep.txt", "one\ntwo\n")
        self.write("added.bin", b"\0\1\2")
        os.rename(self.work / "old/name.txt", self.work / "new-name.txt")
        self.add("keep.txt", "added.bin", "new-name.txt")
        self.rm("gone.txt", "old/name.txt")
        second = self.commit("second")

        root_commit = self.inspect("commit", "--commit", root)
        self.assertEqual(
            {item["path"]: item["change"] for item in root_commit["files"]},
            {"keep.txt": "added", "gone.txt": "added", "old/name.txt": "added"},
        )
        self.assertEqual(root_commit["commit"]["parents"], [])
        self.assertFalse(self.blob(root + "^", "keep.txt")["exists"])

        detail = self.inspect("commit", "--commit", second)
        self.assertEqual(detail["commit"]["sha"], second)
        self.assertEqual(detail["commit"]["message"], "second")
        self.assertFalse(detail["files_truncated"])
        files = {item["path"]: item for item in detail["files"]}
        self.assertEqual(files["keep.txt"]["change"], "modified")
        self.assertEqual(files["added.bin"]["change"], "added")
        self.assertEqual(files["gone.txt"]["change"], "deleted")
        self.assertEqual(files["new-name.txt"]["change"], "renamed")
        self.assertEqual(files["new-name.txt"]["old_path"], "old/name.txt")
        sides = self.inspect("read", "--blob", second + "^:keep.txt", "--blob", second + ":keep.txt")["blobs"]
        self.assertEqual([item["text"] for item in sides], ["one\n", "one\ntwo\n"])
        self.assertEqual(self.blob(second + "^", "old/name.txt")["text"], body)
        self.assertEqual(self.blob(second, "new-name.txt")["text"], body)
        self.assertTrue(self.blob(second, "added.bin")["binary"])
        limited = self.inspect("commit", "--commit", second, "--limit", "1")
        self.assertEqual(len(limited["files"]), 1)
        self.assertTrue(limited["files_truncated"])

    def test_invalid_arguments_are_reported_as_json_errors(self) -> None:
        self.init()
        self.write("a.txt", "a\n")
        self.add("a.txt")
        self.commit("init")
        cases = [
            (("read", "--blob", "HEAD:../escape"), "INVALID_ARGUMENT"),
            (("read", "--blob", "nocolon"), "INVALID_ARGUMENT"),
            (("read", "--blob", "main:a.txt"), "INVALID_ARGUMENT"),
            (("read", "--blob", "0" * 40 + ":a.txt"), "NOT_FOUND"),
            (("commit", "--commit", "abc"), "INVALID_ARGUMENT"),
            (("commit", "--commit", "0" * 40), "NOT_FOUND"),
        ]
        for args, code in cases:
            data = self.inspect(*args)
            self.assertTrue(data["repository"], args)
            self.assertEqual(data["error"]["code"], code, args)
            self.assertTrue(data["error"]["message"], args)

    def test_shared_storage_honours_ignorecase_and_symlinks_in_memory(self) -> None:
        self.init()
        self.write("Readme.txt", "hello\n")
        self.write("target.txt", "t\n")
        os.symlink("target.txt", self.work / "link")
        self.add("Readme.txt", "target.txt", "link")
        self.commit("init")
        # 模拟共享存储检出：符号链接是内容为目标的普通文件，文件名大小写与索引不同。
        (self.work / "link").unlink()
        self.write("link", "target.txt")
        os.rename(self.work / "Readme.txt", self.work / "readme-tmp")
        os.rename(self.work / "readme-tmp", self.work / "README.TXT")
        config_file = self.work / ".git" / "config"
        before_config = config_file.read_bytes()

        shared = self.inspect("status", kind="shared")
        self.assertNotIn("README.TXT", self.paths(shared, "untracked"))
        self.assertNotIn("link", self.paths(shared, "unstaged"))
        self.assertEqual(config_file.read_bytes(), before_config)  # 兼容配置不写回

        project = self.inspect("status", kind="project")
        self.assertIn("README.TXT", self.paths(project, "untracked"))

        # 仓库显式设置的值优先于共享存储默认值。
        from dulwich.repo import Repo

        with Repo(str(self.work)) as repo:
            config = repo.get_config()
            config.set((b"core",), b"ignorecase", b"false")
            config.write_to_path()
        explicit = self.inspect("status", kind="shared")
        self.assertIn("README.TXT", self.paths(explicit, "untracked"))


if __name__ == "__main__":
    unittest.main()
