#!/usr/bin/env python3
"""使用系统 ssh/sftp 客户端自测回环夹具，密码仅通过临时 askpass 进程提供。"""

import argparse
import importlib.util
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", type=Path, default=root / "native-ui/app/build/ssh-test-server/session.json")
    args = parser.parse_args()
    session_path = args.session.resolve()
    spec = importlib.util.spec_from_file_location("ssh_device_session", Path(__file__).with_name("ssh-device-session.py"))
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    session = helper.load_session(session_path)
    options = ["-F", "/dev/null", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
               "-o", "IdentityAgent=none", "-o", "PubkeyAuthentication=no", "-o", "PreferredAuthentications=password",
               "-o", "NumberOfPasswordPrompts=1", "-o", "ConnectTimeout=5"]
    destination = session["username"] + "@127.0.0.1"
    with tempfile.TemporaryDirectory(prefix="nexara-ssh-selftest-") as temporary:
        temporary = Path(temporary)
        askpass = temporary / "askpass"
        askpass.write_text("#!/usr/bin/env python3\nimport json\n"
                           f"with open({str(session_path)!r}) as source:\n"
                           "    print(json.load(source)['password'])\n", encoding="utf-8")
        askpass.chmod(0o700)
        environment = dict(os.environ, SSH_ASKPASS=str(askpass), SSH_ASKPASS_REQUIRE="force",
                           DISPLAY="nexara-ssh-test", TERM="xterm-256color")
        environment.pop("SSH_AUTH_SOCK", None)
        ssh = ["ssh", *options, "-p", str(session["port"])]

        def run(command, expected=0, input_text=None):
            result = subprocess.run(command, input=input_text, stdin=None if input_text is not None else subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                    env=environment, timeout=15)
            if result.returncode != expected:
                raise RuntimeError("系统客户端返回了非预期退出码；原始输出为保护认证材料不回显")
            return result

        executed = run([*ssh, destination, "device-exec"], expected=7)
        assert executed.stdout == "stdout-ok\n" and "stderr-ok" in executed.stderr
        print("PASS exec：独立 stdout/stderr 与退出码 7")

        source = temporary / "source.txt"
        received = temporary / "received.txt"
        source.write_text("系统 SFTP 往返😀\n", encoding="utf-8")
        batch = temporary / "batch.txt"
        batch.write_text(f'put "{source}" /host-selftest.txt\nls /\nget /host-selftest.txt "{received}"\nrm /host-selftest.txt\n', encoding="utf-8")
        listing = run(["sftp", *options, "-o", "BatchMode=no", "-P", str(session["port"]), "-b", str(batch), destination])
        assert received.read_bytes() == source.read_bytes() and "host-selftest.txt" in listing.stdout
        print("PASS SFTP：上传、列目录、下载逐字节相等与删除")

        terminal = run([*ssh, "-tt", destination], input_text="host-pty-probe\nexit\n")
        assert "pty-ready:xterm-256color" in terminal.stdout and "echo:host-pty-probe" in terminal.stdout
        print("PASS PTY：申请终端、发送、回显与关闭")

        with socket.socket() as reserve:
            reserve.bind(("127.0.0.1", 0))
            local_port = reserve.getsockname()[1]
        forwarding = subprocess.Popen([*ssh, "-o", "ExitOnForwardFailure=yes", "-N", "-L",
                                       f"127.0.0.1:{local_port}:127.0.0.1:{session['echoPort']}", destination],
                                      stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=environment)
        try:
            deadline = time.monotonic() + 10
            while True:
                try:
                    with socket.create_connection(("127.0.0.1", local_port), timeout=1) as connection:
                        payload = b"host-forward-echo"
                        connection.settimeout(3)
                        connection.sendall(payload)
                        received = bytearray()
                        while len(received) < len(payload):
                            part = connection.recv(len(payload) - len(received))
                            if not part:
                                raise RuntimeError("回显转发提前关闭")
                            received.extend(part)
                        assert received == payload
                    break
                except (ConnectionRefusedError, TimeoutError):
                    if forwarding.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError("系统 SSH 转发未就绪") from None
                    time.sleep(0.05)
            print("PASS direct-tcpip：系统 ssh -L 到宿主回显端口往返相等")
        finally:
            forwarding.terminate()
            try:
                forwarding.wait(timeout=5)
            except subprocess.TimeoutExpired:
                forwarding.kill()
                forwarding.wait()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # 异常对象可能携带客户端正文或认证材料，只返回稳定错误。
        print("FAIL SSH 宿主自测；请检查回环服务、会话文件和系统 ssh/sftp 客户端。")
        raise SystemExit(1)
