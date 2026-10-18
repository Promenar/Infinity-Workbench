"""Nexara 内置 git：把常用 git 子命令转交 dulwich 命令行。

不支持的子命令、全局选项与 SSH 远端返回 GIT_UNSUPPORTED 与指引，退出码 2；
其它失败输出单行 fatal 信息，退出码 128，与 git 一致。
"""

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


def _rewrite(command, args):
    """把常见 git 写法转换为 dulwich 接受的形式；无法表达时返回错误信息。"""
    if command == "add":
        return [("." if arg in ("-A", "--all") else arg) for arg in args], None
    if command == "clone":
        # 本地源像 git 一样记为绝对路径，否则在克隆目录中 pull/fetch 会按错误的相对位置查找。
        rewritten = list(args)
        for index, arg in enumerate(rewritten):
            if arg.startswith("-"):
                continue
            if index > 0 and rewritten[index - 1] in ("--depth", "-b", "--branch", "--refspec", "--filter", "--protocol"):
                continue
            if "://" not in arg and os.path.isdir(arg):
                rewritten[index] = os.path.abspath(arg)
            break
        return rewritten, None
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

    # 没有终端与编辑器：需要编辑器的路径立即失败，而不是等待超时。
    os.environ.setdefault("GIT_EDITOR", "false")
    os.environ.setdefault("GIT_TERMINAL_PROMPT", "0")
    from dulwich import cli

    try:
        result = cli.main(["--no-pager", command, *rest])
    except KeyboardInterrupt:
        return 130
    except SystemExit:
        raise
    except FileNotFoundError as error:
        if "ssh" in str(error):
            return _unsupported("内置 git 不支持 SSH 远端，请把 remote 改为 HTTPS 地址。" + FALLBACK)
        return _fatal(str(error))
    except Exception as error:  # dulwich 以异常报告大多数失败
        detail = str(error).strip() or type(error).__name__
        return _fatal("%s: %s" % (type(error).__name__, detail.splitlines()[0]))
    return result or 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
