package com.promenar.infinity.testserver;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.forward.TcpForwardingFilter;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/** 只处理合成测试命令，不执行宿主 shell，不读取用户密钥。 */
public final class SshTestServer {
    private static final String USER = "infinity-test";

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length || !java.util.Set.of("--port", "--echo-port", "--root").contains(args[i])
                    || options.put(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("参数：--port <高位端口> --echo-port <高位端口> --root <空目录>");
            }
        }
        int port = port(options.getOrDefault("--port", "2222"));
        int echoPort = port(options.getOrDefault("--echo-port", "2223"));
        if (port == echoPort) throw new IllegalArgumentException("SSH 与回显端口必须不同");
        Path sessionDir = Path.of("build/ssh-test-server").toAbsolutePath();
        Files.createDirectories(sessionDir);
        Files.setPosixFilePermissions(sessionDir, PosixFilePermissions.fromString("rwx------"));
        Path sessionFile = sessionDir.resolve("session.json");
        // 文件锁防止第二个夹具覆盖正在使用的会话。
        try (var lockChannel = java.nio.channels.FileChannel.open(sessionDir.resolve("server.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = lockChannel.tryLock()) {
            if (lock == null) throw new IllegalStateException("当前构建目录已有 SSH 测试服务");
            Files.deleteIfExists(sessionFile);
            boolean ownRoot = !options.containsKey("--root");
            Path root = ownRoot ? Files.createTempDirectory("infinity-ssh-device-")
                    : Path.of(options.get("--root")).toAbsolutePath().normalize();
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("根目录必须是普通目录");
                try (var files = Files.list(root)) {
                    if (files.findAny().isPresent()) throw new IllegalArgumentException("根目录必须为空");
                }
            } else Files.createDirectories(root);
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            java.util.Arrays.fill(random, (byte) 0);
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            var hostKey = generator.generateKeyPair();
            AtomicInteger activeCommands = new AtomicInteger();
            SshServer server = SshServer.setUpDefaultServer();
            server.setHost("127.0.0.1");
            server.setPort(port);
            server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
            server.setPasswordAuthenticator((user, candidate, session) -> USER.equals(user)
                    && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8)));
            server.setFileSystemFactory(new VirtualFileSystemFactory(root));
            server.setSubsystemFactories(java.util.List.of(new SftpSubsystemFactory.Builder().build()));
            server.setCommandFactory((channel, command) -> new FixtureCommand(command, password, activeCommands));
            server.setShellFactory(channel -> new FixtureCommand(null, password, activeCommands));
            server.setForwardingFilter(new RejectAllForwardingFilter() {
                @Override public boolean canConnect(TcpForwardingFilter.Type type, SshdSocketAddress address, Session session) {
                    return type == TcpForwardingFilter.Type.Direct && "127.0.0.1".equals(address.getHostName())
                            && address.getPort() == echoPort;
                }
            });
            ServerSocket echo = new ServerSocket(echoPort, 16, InetAddress.getByName("127.0.0.1"));
            CountDownLatch stopped = new CountDownLatch(1);
            var cleaned = new java.util.concurrent.atomic.AtomicBoolean();
            Thread shutdown = new Thread(() -> {
                if (!cleaned.compareAndSet(false, true)) return;
                try { server.stop(true); } catch (Exception ignored) { }
                try { echo.close(); } catch (Exception ignored) { }
                try { Files.deleteIfExists(sessionFile); } catch (Exception ignored) { }
                if (ownRoot) {
                    try (var paths = Files.walk(root)) {
                        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                            try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                        });
                    } catch (Exception ignored) { }
                }
                stopped.countDown();
            }, "ssh-fixture-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                Thread accept = new Thread(() -> {
                    while (!echo.isClosed()) {
                        try {
                            Socket socket = echo.accept();
                            Thread client = new Thread(() -> {
                                try (socket) {
                                    socket.setSoTimeout(10_000);
                                    var in = socket.getInputStream();
                                    var out = socket.getOutputStream();
                                    byte[] buffer = new byte[4096];
                                    int size;
                                    while ((size = in.read(buffer)) != -1) { out.write(buffer, 0, size); out.flush(); }
                                } catch (Exception ignored) { }
                            }, "ssh-fixture-echo");
                            client.setDaemon(true);
                            client.start();
                        } catch (Exception ignored) { if (echo.isClosed()) return; }
                    }
                }, "ssh-fixture-accept");
                accept.setDaemon(true);
                accept.start();
                server.start();
                // 密码只落入 0600 临时会话；不写 stdout、stderr、主机密钥文件或日志。
                Files.createFile(sessionFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(sessionFile, "{\"port\":" + port + ",\"echoPort\":" + echoPort
                        + ",\"username\":\"" + USER + "\",\"fingerprint\":\"" + KeyUtils.getFingerPrint(hostKey.getPublic())
                        + "\",\"password\":\"" + password + "\",\"transport\":\"adb-reverse\"}\n");
                System.out.println("SSH 测试服务已就绪，仅监听 127.0.0.1；会话：build/ssh-test-server/session.json；Ctrl-C 退出。");
                // Gradle 关闭前台输入时也回收资源，不依赖信号穿透多个 JVM。
                Thread input = new Thread(() -> {
                    try { while (System.in.read() != -1) { } } catch (Exception ignored) { }
                    shutdown.run();
                }, "ssh-fixture-input");
                input.setDaemon(true);
                input.start();
                // 会话文件是服务租约；删除它可在受限宿主环境中安全停止夹具。
                while (!stopped.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (!Files.isRegularFile(sessionFile, LinkOption.NOFOLLOW_LINKS)) shutdown.run();
                }
            } finally {
                shutdown.run();
                try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
            }
        }
    }

    private static int port(String value) {
        int result = Integer.parseInt(value);
        if (result < 1024 || result > 65535) throw new IllegalArgumentException("端口必须在 1024–65535 之间");
        return result;
    }

    private static final class FixtureCommand implements Command {
        private final String command;
        private final String password;
        private final AtomicInteger active;
        private InputStream input;
        private OutputStream output;
        private OutputStream error;
        private ExitCallback callback;
        private Thread worker;
        FixtureCommand(String command, String password, AtomicInteger active) {
            this.command = command; this.password = password; this.active = active;
        }
        @Override public void setInputStream(InputStream value) { input = value; }
        @Override public void setOutputStream(OutputStream value) { output = value; }
        @Override public void setErrorStream(OutputStream value) { error = value; }
        @Override public void setExitCallback(ExitCallback value) { callback = value; }
        @Override public void start(ChannelSession channel, Environment environment) {
            worker = new Thread(() -> {
                boolean sleeping = "device-sleep".equals(command);
                if (sleeping) active.incrementAndGet();
                int exit = 0;
                try {
                    if (command == null) {
                        output.write(("pty-ready:" + environment.getEnv().getOrDefault(Environment.ENV_TERM, "none") + "\n").getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
                        String line;
                        while ((line = reader.readLine()) != null && !line.equals("exit")) {
                            output.write(("echo:" + line + "\n").getBytes(StandardCharsets.UTF_8)); output.flush();
                        }
                    } else switch (command) {
                        case "device-exec" -> {
                            output.write("stdout-ok\n".getBytes(StandardCharsets.UTF_8));
                            error.write("stderr-ok\n".getBytes(StandardCharsets.UTF_8)); exit = 7;
                        }
                        case "device-sleep" -> Thread.sleep(30_000);
                        case "device-active" -> output.write((active.get() + "\n").getBytes(StandardCharsets.UTF_8));
                        case "device-secret" -> {
                            output.write(("secret:" + password + ":end\n").getBytes(StandardCharsets.UTF_8));
                            error.write(("secret:" + password + ":end\n").getBytes(StandardCharsets.UTF_8));
                        }
                        default -> { error.write("不支持的测试命令\n".getBytes(StandardCharsets.UTF_8)); exit = 127; }
                    }
                    output.flush(); error.flush();
                } catch (Exception ignored) { exit = 130; }
                finally { if (sleeping) active.decrementAndGet(); callback.onExit(exit); }
            }, "ssh-fixture-command");
            worker.setDaemon(true);
            worker.start();
        }
        @Override public void destroy(ChannelSession channel) {
            if (worker != null) worker.interrupt();
            try { input.close(); } catch (Exception ignored) { }
        }
    }
}
