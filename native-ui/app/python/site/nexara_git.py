"""Nexara 内置 git：把常用 git 子命令转交 dulwich 命令行。

不支持的子命令、全局选项与 SSH 远端返回 GIT_UNSUPPORTED 与指引，退出码 2；
其它失败输出单行 fatal 信息，退出码 128，与 git 一致。
"""

import io
import os
import re
import sys

UNSUPPORTED_EXIT = 2
FATAL_EXIT = 128

# 只开放日常版本管理所需的子命令；服务端、维护与需要外部程序的子命令不开放。
SUPPORTED = (
    "add", "blame", "branch", "cat-file", "checkout", "cherry-pick", "clean", "clone", "commit", "config",
    "describe", "diff", "fetch", "for-each-ref", "grep", "init", "log", "ls-files", "ls-remote", "ls-tree",
    "merge", "merge-base", "mv", "pull", "push", "rebase", "reflog", "remote", "reset", "restore", "rev-list",
    "rev-parse", "revert", "rm", "shortlog", "show", "show-ref", "stash", "status", "switch", "tag",
)
# 会写入提交对象的子命令：没有身份时像 git 一样拒绝，而不是落成 u0_aXXX@localhost。
NEEDS_IDENTITY = ("commit", "merge", "cherry-pick", "revert", "rebase", "stash")
NETWORK = ("clone", "fetch", "pull", "push", "ls-remote")
FALLBACK = "完整 git 请改用 termux_exec 或 ssh_exec。"
_SCP_LIKE = re.compile(r"^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:")


def _unsupported(message):
    print("git: GIT_UNSUPPORTED: " + message, file=sys.stderr)
    return UNSUPPORTED_EXIT


def _fatal(message):
    print("fatal: " + message, file=sys.stderr)
    return FATAL_EXIT


def _version():
    from dulwich import __version__

    version = __version__ if isinstance(__version__, str) else ".".join(str(part) for part in __version__)
    return "git version dulwich-" + version + " (Nexara builtin)"


def _usage():
    return "\n".join([
        "用法：git [-C <目录>] <子命令> [参数]（Nexara 内置，基于 dulwich）",
        "支持的子命令：" + " ".join(SUPPORTED),
        "clone [--depth <N>] [-b <分支>] [--single-branch | --no-single-branch] [--no-tags] [--bare] <地址> [<目录>]",
        "  --depth 隐含 --single-branch：只取远端默认分支（或 -b 指定的分支），标签只跟随指向已抓取提交的标签；",
        "  --no-single-branch 取全部分支与标签；--no-tags 不取标签。单分支克隆后 fetch 仍只取该分支。",
        "fetch [<远端>] [--depth <N>] [--tags | --no-tags]：省略远端时取当前分支的上游远端或 origin。",
        "工作区：私有工作区计入应用备份上限且卸载即删除，clone 前会提示改用项目文件夹或共享文件夹；",
        "  共享文件夹的仓库自动设置 core.symlinks=false、core.filemode=false、core.ignorecase=true，",
        "  符号链接检出为普通文件，仅大小写不同的路径只保留一个。",
        "远端只支持 HTTPS；推送私有仓库需在 URL 中提供令牌。" + FALLBACK,
    ])


def _ssh_url(value):
    return value.startswith("ssh://") or value.startswith("git+ssh://") or bool(_SCP_LIKE.match(value))


def _has_identity():
    from dulwich.repo import Repo

    def configured(kind):
        name = os.environ.get("GIT_%s_NAME" % kind)
        email = os.environ.get("GIT_%s_EMAIL" % kind)
        return bool(name and email)

    if configured("AUTHOR") and configured("COMMITTER"):
        return True
    try:
        config = Repo.discover(".").get_config_stack()
    except Exception:
        return True  # 不在仓库中时交给 dulwich 报告原因
    try:
        return bool(config.get((b"user",), b"name")) and bool(config.get((b"user",), b"email"))
    except KeyError:
        return False


def _has_message(args):
    for arg in args:
        if arg in ("-m", "--message", "-C", "--reuse-message", "-c", "--reedit-message", "--amend"):
            return True
        if arg.startswith("--message=") or (arg.startswith("-") and not arg.startswith("--") and "m" in arg[1:]):
            return True
    return False


class _ProgressCollapser(io.RawIOBase):
    """只保留以回车刷新的进度行的最终状态：输出不接终端，逐次刷新会把真正的结果挤出输出尾部。"""

    def __init__(self, raw):
        super().__init__()
        self._raw = raw
        self._line = bytearray()
        self._carriage = False

    def writable(self):
        return True

    def write(self, data):
        for byte in bytes(data):
            if self._carriage:
                self._carriage = False
                if byte != 10:
                    self._line.clear()
            if byte == 13:
                self._carriage = True
            elif byte == 10:
                self._raw.write(bytes(self._line) + b"\n")
                self._line.clear()
            else:
                self._line.append(byte)
        return len(data)

    def finish(self):
        if self._line:
            self._raw.write(bytes(self._line) + b"\n")
            self._line.clear()
        self._raw.flush()


def _collapse_progress():
    """返回结束时调用的收尾函数；必须在导入 dulwich.cli 之前替换，其参数默认值在导入时绑定 sys.stderr.buffer。"""
    if sys.stderr is None or sys.stderr.isatty():
        return lambda: None
    original = sys.stderr
    collapser = _ProgressCollapser(original.buffer)
    sys.stderr = io.TextIOWrapper(io.BufferedWriter(collapser), encoding=original.encoding or "utf-8",
                                  errors="backslashreplace", line_buffering=True)

    def finish():
        sys.stderr.flush()
        collapser.finish()
        sys.stderr = original

    return finish


_COUNT_SHORTHAND = re.compile(r"^-([0-9]+)$")
HEAD_REF = b"HEAD"
HEADS_PREFIX = b"refs/heads/"
TAGS_PREFIX = b"refs/tags/"
PEELED_SUFFIX = b"^{}"
# git 默认只取分支与标签；dulwich 的 clone/fetch 默认要远端通告的全部引用，
# GitHub 会通告每个 PR 的 refs/pull/*，--depth 1 克隆热门仓库也会下载数百 MB。
DEFAULT_FETCH_REFS = (HEAD_REF, HEADS_PREFIX, TAGS_PREFIX)
# dulwich.cli clone 不认识的 git 选项，由包装层消化后剔除。
CLONE_WRAPPER_FLAGS = ("--single-branch", "--no-single-branch", "--no-tags")


class _FetchPolicy:
    """一次 clone/fetch 的抓取范围，由包装层按 git 语义算出，供 dulwich fetch 钩子使用。

    single_branch 为真时只抓 branches 列出的分支；branches 为 None 表示远端 HEAD 指向的默认分支。
    tags 取 "all"（全部标签）、"follow"（只跟随指向已抓取对象的标签）或 "none"。
    """

    def __init__(self, single_branch=False, branches=None, tags="all", clone=False, shared=False):
        self.single_branch = single_branch
        self.branches = branches
        self.tags = tags
        self.clone = clone
        self.shared = shared
        self.clone_path = None
        self.bare = False


_POLICY = None


class _RemoteRefMissing(Exception):
    pass


def _clone_policy(args, shared=False):
    """按 git clone 语义解析抓取范围：--depth 隐含 --single-branch，single-branch 时标签改为自动跟随。"""
    depth = branch = single = None
    no_tags = bare = False
    index = 0
    while index < len(args):
        arg = args[index]
        name, has_value, value = arg.partition("=")
        if arg in ("--depth", "-b", "--branch") and index + 1 < len(args):
            name, value, has_value = arg, args[index + 1], True
            index += 1
        if has_value and name == "--depth":
            depth = value
        elif has_value and name in ("-b", "--branch"):
            branch = value
        elif arg == "--single-branch":
            single = True
        elif arg == "--no-single-branch":
            single = False
        elif arg == "--no-tags":
            no_tags = True
        elif arg == "--bare":
            bare = True
        index += 1
    if single is None:
        try:
            single = depth is not None and int(depth) > 0
        except ValueError:
            single = False
    policy = _FetchPolicy(
        single_branch=single,
        branches=[branch.encode()] if single and branch else None,
        tags="none" if no_tags else ("follow" if single else "all"),
        clone=True,
        shared=shared,
    )
    policy.bare = bare
    return policy


def _fetch_location(args):
    """返回 fetch 参数中的远端位置（第一个位置参数），没有时返回 None。"""
    index = 0
    while index < len(args):
        arg = args[index]
        if arg in ("--depth", "--shallow-since", "--shallow-exclude"):
            index += 2
            continue
        if not arg.startswith("-"):
            return arg
        index += 1
    return None


def _default_remote():
    """像 git 一样取当前分支的上游远端，没有时用 origin。"""
    from dulwich.repo import Repo

    try:
        repo = Repo.discover(".")
    except Exception:
        return "origin"
    try:
        head = repo.refs.read_ref(HEAD_REF) or b""
        if head.startswith(b"ref: " + HEADS_PREFIX):
            branch = head[len(b"ref: " + HEADS_PREFIX):].strip()
            return repo.get_config().get((b"branch", branch), b"remote").decode()
    except (KeyError, ValueError):
        pass
    finally:
        repo.close()
    return "origin"


def _fetch_policy(args, shared=False):
    """按远端配置的 fetch refspec 与 tagOpt 决定 fetch 范围：单分支克隆后的 fetch 仍只取该分支。"""
    from dulwich.repo import Repo

    policy = _FetchPolicy(shared=shared)
    location = _fetch_location(args)
    tag_option = None
    refspecs = []
    if location is not None and "--all" not in args:
        try:
            repo = Repo.discover(".")
        except Exception:
            repo = None
        if repo is not None:
            try:
                config = repo.get_config()
                section = (b"remote", location.encode())
                if config.has_section(section):  # URL 或未配置的远端：保持全部分支与标签
                    try:
                        refspecs = list(config.get_multivar(section, b"fetch"))
                    except KeyError:
                        pass
                    try:
                        tag_option = config.get(section, b"tagOpt")
                    except KeyError:
                        pass
            finally:
                repo.close()
    branches = []
    for refspec in refspecs:
        source = refspec.lstrip(b"+").split(b":", 1)[0]
        if not source.startswith(HEADS_PREFIX):
            continue
        if b"*" in source:
            branches = []
            break
        branches.append(source[len(HEADS_PREFIX):])
    if branches:
        policy.single_branch = True
        policy.branches = branches
        policy.tags = "follow"
    if "--tags" in args or (tag_option == b"--tags" and "--no-tags" not in args):
        policy.tags = "all"
    elif "--no-tags" in args or tag_option == b"--no-tags":
        policy.tags = "none"
    return policy


def _wanted_refs(policy, refs):
    """从远端通告的引用中挑出本次要抓取的引用。"""
    wanted = {}
    for name, sha in refs.items():
        if sha is None or name.endswith(PEELED_SUFFIX):
            continue
        if name.startswith(TAGS_PREFIX):
            if policy.tags == "all":
                wanted[name] = sha
        elif not policy.single_branch and (name == HEAD_REF or name.startswith(HEADS_PREFIX)):
            wanted[name] = sha
    if not policy.single_branch:
        return wanted
    if policy.branches is None:
        if refs.get(HEAD_REF) is not None:
            wanted[HEAD_REF] = refs[HEAD_REF]
        return wanted
    for branch in policy.branches:
        candidates = [HEADS_PREFIX + branch]
        if policy.clone:
            candidates.append(TAGS_PREFIX + branch)  # git clone -b 也接受标签
        for name in candidates:
            if refs.get(name) is not None:
                wanted[name] = refs[name]
                break
        else:
            if policy.clone:
                raise _RemoteRefMissing("Remote branch %s not found in upstream origin" % branch.decode())
    return wanted


def _fetch_ref_prefix(policy):
    if not policy.single_branch:
        return [prefix for prefix in DEFAULT_FETCH_REFS if policy.tags == "all" or prefix != TAGS_PREFIX]
    prefixes = [HEAD_REF]
    if policy.branches is None:
        # 默认分支名要等 symref 才知道；通告里必须带上它，dulwich 才会建立远端跟踪引用与本地分支。
        prefixes.append(HEADS_PREFIX)
    for branch in policy.branches or ():
        prefixes += [HEADS_PREFIX + branch, TAGS_PREFIX + branch]
    if policy.tags == "all":
        prefixes.append(TAGS_PREFIX)
    return prefixes


def _remote_tags(client, path, protocol_version):
    """返回远端标签及其剥离后指向的对象；本地远端的通告不含剥离值，直接打开仓库剥离。"""
    from dulwich.client import LocalGitClient

    listing = client.get_refs(path, protocol_version=protocol_version, ref_prefix=[TAGS_PREFIX])
    tags, peeled = {}, {}
    for name, sha in listing.refs.items():
        if sha is None or not name.startswith(TAGS_PREFIX):
            continue
        if name.endswith(PEELED_SUFFIX):
            peeled[name[:-len(PEELED_SUFFIX)]] = sha
        else:
            tags[name] = sha
    if isinstance(client, LocalGitClient):
        with client._open_repo(path) as repo:
            for name in tags:
                try:
                    peeled[name] = repo.get_peeled(name)
                except KeyError:
                    pass
    return tags, peeled


def _follow_tags(original, client, path, target, kwargs, fetched):
    """git 的标签自动跟随：只取指向本地已有对象（已抓取历史）的标签。

    附注标签对象不在第一次抓取的包里，需要第二次抓取；此时 graph walker 以已抓到的提交为 have，
    服务端只发送标签对象本身，不会重新发送它指向的快照。
    """
    from dulwich.client import LocalGitClient
    from dulwich.objects import Tag

    store = target.object_store
    tags, peeled = _remote_tags(client, path, kwargs.get("protocol_version"))
    followed, pending = {}, {}
    for name, sha in tags.items():
        if sha in store:
            followed[name] = sha
        elif peeled.get(name) is not None and peeled[name] in store:
            pending[name] = sha
    if not pending:
        return followed
    if isinstance(client, LocalGitClient):
        # dulwich 的本地服务端遇到浅仓库会丢弃 have（repo.find_missing_objects），第二次抓取会重发整份快照；
        # 本地远端直接复制标签对象链即可。
        with client._open_repo(path) as source:
            for sha in set(pending.values()):
                while sha not in store:
                    item = source.object_store[sha]
                    store.add_object(item)
                    if not isinstance(item, Tag):
                        break
                    sha = item.object[1]
        followed.update(pending)
        return followed
    heads = [sha for sha in fetched if sha in store]
    heads += [sha for sha in target.refs.as_dict(HEADS_PREFIX.rstrip(b"/")).values() if sha in store]
    walker = type(target).get_graph_walker
    target.get_graph_walker = lambda heads_arg=None: walker(target, heads=heads_arg or heads)
    try:
        original(
            client, path, target,
            determine_wants=lambda refs, depth=None: sorted(set(pending.values())),
            progress=kwargs.get("progress"),
            ref_prefix=[TAGS_PREFIX],
            protocol_version=kwargs.get("protocol_version"),
        )
    finally:
        del target.get_graph_walker
    followed.update({name: sha for name, sha in pending.items() if sha in store})
    return followed


def _policy_fetch(original, client, path, target, policy, kwargs):
    if policy.clone:
        policy.clone_path = getattr(target, "path", None)
        if policy.shared:
            # clone 在 fetch 之后才检出并重新读取仓库配置，这里写入才能让 core.symlinks=false 对首次检出生效。
            _write_shared_config(target, force=True)
    selected = {}

    def determine_wants(refs, depth=None):
        selected.clear()
        selected.update(_wanted_refs(policy, refs))
        return target.object_store.determine_wants_all(selected, depth=depth)

    kwargs["ref_prefix"] = _fetch_ref_prefix(policy)
    if policy.tags == "follow" and hasattr(client, "_fetch_capabilities"):
        # 协议 v0/v1 下让服务端随包附上指向所发对象的附注标签（git 自动跟随的主路径）；
        # dulwich 在协议 v2 不发送 include-tag，由 _follow_tags 的第二次抓取补齐。
        from dulwich.protocol import CAPABILITY_INCLUDE_TAG

        client._fetch_capabilities.add(CAPABILITY_INCLUDE_TAG)
    result = original(client, path, target, determine_wants=determine_wants, **kwargs)
    store = target.object_store
    if policy.single_branch and policy.branches is None and HEAD_REF in selected:
        head_target = result.symrefs.get(HEAD_REF)
        if head_target and head_target.startswith(HEADS_PREFIX):
            selected[head_target] = selected[HEAD_REF]  # 抓取的正是 HEAD 指向的提交
    # 只交回本次选中且对象已在本地的引用：dulwich 会把交回的分支与标签全部导入为远端跟踪引用和本地标签。
    refs = {name: sha for name, sha in selected.items() if sha in store}
    if policy.tags == "follow":
        try:
            refs.update(_follow_tags(original, client, path, target, kwargs, list(refs.values())))
        except Exception as error:  # 标签跟随失败不影响分支本身
            print("warning: 跳过标签自动跟随：%s" % (str(error).strip() or type(error).__name__), file=sys.stderr)
    result.refs = refs
    if policy.clone and policy.single_branch:
        _record_single_branch(target, policy, refs)
    return result


def _record_single_branch(target, policy, refs):
    """像 git clone --single-branch 一样把 fetch refspec 收窄为该分支，--no-tags 记为 tagOpt。"""
    branches = [name[len(HEADS_PREFIX):] for name in refs if name.startswith(HEADS_PREFIX)]
    config = target.get_config()
    section = (b"remote", b"origin")
    if not config.has_section(section):
        return
    if len(branches) == 1:
        branch = branches[0]
        config.set(section, b"fetch", b"+refs/heads/" + branch + b":refs/remotes/origin/" + branch)
    if policy.tags == "none":
        config.set(section, b"tagOpt", b"--no-tags")
    config.write_to_path()


def _limit_fetched_refs():
    from dulwich.client import GitClient, LocalGitClient

    # LocalGitClient 覆盖了 fetch 且忽略 ref_prefix，两处都要接管。
    for cls in (GitClient, LocalGitClient):
        original = cls.__dict__["fetch"]
        if not getattr(original, "_nexara_limited", False):
            cls.fetch = _wrap_fetch(original)


def _wrap_fetch(original):
    def fetch(self, path, target, *args, **kwargs):
        policy = _POLICY
        if args or kwargs.get("determine_wants") is not None or policy is None:
            # pull 等自带 determine_wants 的调用只限制通告范围。
            if not args and kwargs.get("ref_prefix") is None:
                kwargs["ref_prefix"] = list(DEFAULT_FETCH_REFS)
            return original(self, path, target, *args, **kwargs)
        kwargs.pop("determine_wants", None)
        return _policy_fetch(original, self, path, target, policy, kwargs)

    fetch._nexara_limited = True
    return fetch


WORKSPACE_KINDS = ("private", "project", "shared")
SHARED_CORE_CONFIG = ((b"symlinks", b"false"), (b"filemode", b"false"), (b"ignorecase", b"true"))
PRIVATE_CLONE_HINT = (
    "提示：当前是会话私有工作区，内容计入应用备份上限（16 MiB / 10 000 项），"
    "克隆产生的 .git 对象会使备份超限失败，且卸载应用即删除。"
    "建议改用自选工作区（项目文件夹或共享文件夹）后再克隆。"
)


def _workspace_kind():
    kind = os.environ.get("NEXARA_WORKSPACE_KIND", "").strip().lower()
    return kind if kind in WORKSPACE_KINDS else "private"


def _write_shared_config(repo, force):
    """共享存储大小写不敏感、不支持符号链接、chmod 无效；force 为假时只补写缺失项。"""
    config = repo.get_config()
    changed = False
    for name, value in SHARED_CORE_CONFIG:
        if not force:
            try:
                config.get((b"core",), name)
                continue
            except KeyError:
                pass
        config.set((b"core",), name, value)
        changed = True
    if changed:
        config.write_to_path()


def _open_repo(path, discover):
    from dulwich.repo import Repo

    try:
        return Repo.discover(path) if discover else Repo(path)
    except Exception:
        return None


def _apply_shared_config(path, discover, force):
    repo = _open_repo(path, discover)
    if repo is None:
        return
    try:
        _write_shared_config(repo, force=force)
    finally:
        repo.close()


def _init_path(args):
    for arg in args:
        if not arg.startswith("-"):
            return arg
    return "."


def _shared_checkout_notes(path, discover=True):
    """共享存储上符号链接检出为普通文件、仅大小写不同的路径互相覆盖，检出后说明受影响的路径。"""
    import stat as stat_module

    from dulwich.object_store import iter_tree_contents
    from dulwich.objects import Commit

    repo = _open_repo(path, discover)
    if repo is None:
        return
    try:
        if repo.bare:
            return
        try:
            head = repo[HEAD_REF]
            while not isinstance(head, Commit):
                head = repo[head.object[1]]
        except (KeyError, AttributeError):
            return
        links, names = [], {}
        for entry in iter_tree_contents(repo.object_store, head.tree):
            if stat_module.S_ISLNK(entry.mode):
                links.append(entry.path.decode("utf-8", "replace"))
            parts = entry.path.split(b"/")
            for depth in range(1, len(parts) + 1):
                prefix = b"/".join(parts[:depth])
                names.setdefault(prefix.lower(), set()).add(prefix)
    finally:
        repo.close()
    collisions = sorted(sorted(group) for group in names.values() if len(group) > 1)
    if links:
        print(
            "提示：共享存储不支持符号链接，%d 个符号链接已检出为内容是链接目标的普通文件：%s"
            % (len(links), _sample(links)),
            file=sys.stderr,
        )
    if collisions:
        shown = [" 与 ".join(name.decode("utf-8", "replace") for name in group) for group in collisions]
        print(
            "提示：共享存储大小写不敏感，%d 组路径仅大小写不同，工作区中每组只会保留一个：%s"
            % (len(collisions), _sample(shown, separator="；")),
            file=sys.stderr,
        )


def _sample(items, limit=5, separator="、"):
    shown = separator.join(items[:limit])
    return shown + (" 等 %d 项" % len(items) if len(items) > limit else "")


def _rewrite(command, args):
    """把常见 git 写法转换为 dulwich 接受的形式；无法表达时返回错误信息。"""
    if command == "log":
        # git 的 -<N> 等同 -n <N>；dulwich 会把它当成路径，静默输出空结果。
        rewritten = []
        for arg in args:
            match = _COUNT_SHORTHAND.match(arg)
            rewritten += ["-n", match.group(1)] if match else [arg]
        return rewritten, None
    if command == "branch":
        aliases = {"-a": "--all", "-r": "--remotes"}
        return [aliases.get(arg, arg) for arg in args], None
    if command == "add":
        return [("." if arg in ("-A", "--all") else arg) for arg in args], None
    if command == "clone":
        # 抓取范围选项由包装层的 fetch 钩子实现，dulwich.cli 不认识，原样传入会报错。
        rewritten = [arg for arg in args if arg not in CLONE_WRAPPER_FLAGS]
        # 本地源像 git 一样记为绝对路径，否则在克隆目录中 pull/fetch 会按错误的相对位置查找。
        for index, arg in enumerate(rewritten):
            if arg.startswith("-"):
                continue
            if index > 0 and rewritten[index - 1] in ("--depth", "-b", "--branch", "--refspec", "--filter", "--protocol"):
                continue
            if "://" not in arg and os.path.isdir(arg):
                rewritten[index] = os.path.abspath(arg)
            break
        return rewritten, None
    if command == "fetch":
        # dulwich 的 fetch 必须给出远端；git 默认取当前分支的上游远端或 origin。
        if "--all" in args or _fetch_location(args) is not None:
            return list(args), None
        try:
            remote = _default_remote()
        except ImportError:
            remote = "origin"
        return list(args) + [remote], None
    if command == "commit":
        rewritten = []
        index = 0
        while index < len(args):
            arg = args[index]
            if arg in ("-F", "--file") and index + 1 < len(args):
                with open(args[index + 1], encoding="utf-8") as message:
                    rewritten += ["-m", message.read()]
                index += 2
                continue
            rewritten.append(arg)
            index += 1
        if not _has_message(rewritten):
            return None, "内置 git 没有编辑器，提交需用 -m <信息> 或 -F <文件> 提供提交信息。"
        return rewritten, None
    return args, None


def _remote(args):
    """dulwich 只实现 remote add；列出、查看与修改地址、删除由这里读写仓库配置。返回 None 表示交给 dulwich。"""
    if args and args[0] == "add":
        return None
    from dulwich.repo import Repo

    repo = Repo.discover(".")
    config = repo.get_config()
    remotes = [section[1].decode() for section in config.sections() if len(section) == 2 and section[0] == b"remote"]

    def url(name):
        try:
            return config.get((b"remote", name.encode()), b"url").decode()
        except KeyError:
            return None

    if not args or args in (["-v"], ["--verbose"]):
        for name in remotes:
            if args:
                print("%s\t%s (fetch)\n%s\t%s (push)" % (name, url(name), name, url(name)))
            else:
                print(name)
        return 0
    action, rest = args[0], args[1:]
    if action == "get-url" and len(rest) == 1:
        if url(rest[0]) is None:
            return _fatal("No such remote '%s'" % rest[0])
        print(url(rest[0]))
        return 0
    if action == "set-url" and len(rest) == 2:
        if url(rest[0]) is None:
            return _fatal("No such remote '%s'" % rest[0])
        if _ssh_url(rest[1]):
            return _unsupported("内置 git 不支持 SSH 远端，请改用 HTTPS 地址。" + FALLBACK)
        config.set((b"remote", rest[0].encode()), b"url", rest[1].encode())
        config.write_to_path()
        return 0
    if action in ("remove", "rm") and len(rest) == 1:
        if url(rest[0]) is None:
            return _fatal("No such remote '%s'" % rest[0])
        del config[(b"remote", rest[0].encode())]
        config.write_to_path()
        return 0
    return _unsupported("remote 只支持：remote [-v]、add、get-url、set-url、remove。" + FALLBACK)


def main(argv):
    args = list(argv)
    while args and args[0].startswith("-"):
        option = args.pop(0)
        if option in ("--version", "-v"):
            print(_version())
            return 0
        if option in ("-h", "--help"):
            print(_usage())
            return 0
        if option == "-C" and args:
            try:
                os.chdir(args.pop(0))
            except OSError as error:
                return _fatal("无法进入目录：%s" % error)
            continue
        if option in ("--no-pager", "-P"):
            continue
        return _unsupported("不支持全局选项 %s（只支持 -C <目录>）。%s" % (option, FALLBACK))
    if not args or args[0] == "help":
        print(_usage())
        return 0
    command, rest = args[0], args[1:]
    if command == "version":
        print(_version())
        return 0
    if command not in SUPPORTED:
        return _unsupported("子命令 %s 不在内置 git 支持范围内。支持：%s。%s" % (command, " ".join(SUPPORTED), FALLBACK))
    if command in NETWORK and any(_ssh_url(arg) for arg in rest):
        return _unsupported("内置 git 不支持 SSH 远端，请改用 HTTPS 地址。" + FALLBACK)
    if command in ("clone", "fetch") and any(arg in ("-h", "--help") for arg in rest):
        print(_usage())
        return 0
    original_rest = rest
    try:
        rest, problem = _rewrite(command, rest)
    except OSError as error:
        return _fatal("无法读取提交信息文件：%s" % error)
    if problem:
        return _unsupported(problem)
    if command in NEEDS_IDENTITY and not _has_identity():
        print(
            "git: GIT_IDENTITY_MISSING: 未设置提交身份。请先运行\n"
            "  git config user.name \"你的名字\"\n"
            "  git config user.email \"you@example.com\"\n"
            "（加 --global 写入 $HOME/.gitconfig，对所有工作区生效）",
            file=sys.stderr,
        )
        return FATAL_EXIT

    if command == "remote":
        try:
            handled = _remote(rest)
        except Exception as error:
            return _fatal("%s: %s" % (type(error).__name__, (str(error).strip() or type(error).__name__).splitlines()[0]))
        if handled is not None:
            return handled

    kind = _workspace_kind()
    shared = kind == "shared"
    if command == "clone" and kind == "private":
        print(PRIVATE_CLONE_HINT, file=sys.stderr)
    if shared and command not in ("clone", "init"):
        _apply_shared_config(".", discover=True, force=False)
    policy = None
    if command == "clone":
        policy = _clone_policy(original_rest, shared=shared)
    elif command == "fetch":
        policy = _fetch_policy(rest, shared=shared)

    # 没有终端与编辑器：需要编辑器的路径立即失败，而不是等待超时。
    os.environ.setdefault("GIT_EDITOR", "false")
    os.environ.setdefault("GIT_TERMINAL_PROMPT", "0")
    finish = _collapse_progress()
    try:
        result = _run_dulwich(command, rest, policy)
        if result == 0 and shared:
            _after_shared_command(command, rest, policy)
        return result
    finally:
        finish()


def _after_shared_command(command, rest, policy):
    if command == "init":
        _apply_shared_config(_init_path(rest), discover=False, force=True)
    elif command == "clone" and policy.clone_path:
        _apply_shared_config(policy.clone_path, discover=False, force=True)
        if not policy.bare:
            _shared_checkout_notes(policy.clone_path, discover=False)
    elif command in ("checkout", "switch"):
        _shared_checkout_notes(".")


def _run_dulwich(command, rest, policy=None):
    global _POLICY
    from dulwich import cli

    if command in ("clone", "fetch"):
        _limit_fetched_refs()

    _POLICY = policy
    try:
        result = cli.main(["--no-pager", command, *rest])
    except KeyboardInterrupt:
        return 130
    except SystemExit:
        raise
    except _RemoteRefMissing as error:
        return _fatal(str(error))
    except FileNotFoundError as error:
        if "ssh" in str(error):
            return _unsupported("内置 git 不支持 SSH 远端，请把 remote 改为 HTTPS 地址。" + FALLBACK)
        return _fatal(str(error))
    except Exception as error:  # dulwich 以异常报告大多数失败
        detail = str(error).strip() or type(error).__name__
        return _fatal("%s: %s" % (type(error).__name__, detail.splitlines()[0]))
    finally:
        _POLICY = None
    return result or 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
