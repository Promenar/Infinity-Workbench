package com.promenar.infinity.data.git.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.exec.AndroidProcessGroupSignaller
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.data.exec.BuiltinExecEnvironment
import com.promenar.infinity.data.exec.BuiltinExecService
import com.promenar.infinity.data.exec.PackagedPython
import com.promenar.infinity.data.exec.SystemCaBundle
import com.promenar.infinity.data.repository.WorkspaceChangeReport
import com.promenar.infinity.data.repository.WorkspaceCommandHost
import com.promenar.infinity.data.repository.WorkspaceCommandOutcome
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.domain.tool.CommandToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 在设备上用打包 Python 跑内置 git 的远端认证全链路：
 * - HTTPS：本机 dulwich 智能 HTTP 服务充当上游，经 [GitCredentialProxy] clone/push，Authorization 只出现在代理转发一侧；
 * - SSH：infinity_git 的 SSH vendor 经 [GitSshBridge] 交给本地执行器，执行器以 dulwich 服务端处理 upload-pack/receive-pack，
 *   覆盖真实的 Python 客户端与 Kotlin 桥之间的握手与帧协议（不连接真实 SSH 服务器）。
 * 设备没有打包 Python 时跳过。
 */
@RunWith(AndroidJUnit4::class)
class GitRemoteAuthDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var base: File
    private lateinit var workspace: File
    private lateinit var served: File
    private lateinit var environment: BuiltinExecEnvironment
    private lateinit var pythonEnv: Map<String, String>
    private lateinit var service: BuiltinExecService
    private lateinit var broker: GitRemoteBroker
    private lateinit var proxy: GitCredentialProxy
    private var upstream: Process? = null
    private var upstreamPort = 0
    private val nonces = GitNonceRegistry()
    private val forwarded = CopyOnWriteArrayList<Pair<String, String?>>()
    private val forwardedNonces = CopyOnWriteArrayList<String?>()
    private val sshCommands = CopyOnWriteArrayList<String>()

    private val host = object : WorkspaceCommandHost {
        override suspend fun <T> runCommandInWorkspace(workspaceRootUuid: String, sessionId: String?, block: suspend (File) -> T) =
            WorkspaceCommandOutcome(block(workspace), WorkspaceChangeReport(), null)
    }

    private val secrets = object : SecretStore {
        val values = mutableMapOf<String, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id.value] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id.value]?.copyOf()
        override fun contains(id: SecretId): Boolean = id.value in values
        override fun remove(id: SecretId) { values.remove(id.value) }
    }

    @Before
    fun setUp() {
        val python = PackagedPython.from(context)
        assumeTrue("当前设备架构没有打包 Python，跳过 Git 远端认证设备测试", python.isAvailable)
        base = File(context.cacheDir, "git-remote-device-${UUID.randomUUID()}").apply { mkdirs() }
        workspace = File(base, "ws").apply { mkdirs() }
        served = File(base, "srv/owner").apply { mkdirs() }
        environment = BuiltinExecEnvironment(
            python = python,
            homeDirectory = File(base, "home"),
            tempDirectory = File(base, "tmp"),
            caBundle = SystemCaBundle(File(base, "ca"))::ensure,
        )
        pythonEnv = environment.forPython(base)
        python(SEED, File(served, "repo.git").path, File(served, "ssh.git").path).also { (code, output) ->
            assertWithMessage("初始化上游仓库失败：$output").that(code).isEqualTo(0)
        }
        startUpstream()

        val credentials = SecretGitCredentialStore(secrets).apply { save("git.test", USER, TOKEN.toByteArray()) }
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val request = chain.request()
            forwarded += request.url.encodedPath to request.header("Authorization")
            forwardedNonces += request.header(GitCredentialProxy.NONCE_HEADER)
            chain.proceed(request)
        }.build()
        val mapping = object : GitUpstream {
            override fun url(hostKey: String): HttpUrl = "http://127.0.0.1:$upstreamPort/".toHttpUrl()
            override fun hostKey(url: HttpUrl): String? = if (url.host == "127.0.0.1" && url.port == upstreamPort) "git.test" else null
        }
        proxy = GitCredentialProxy(credentials, nonces, client, mapping)
        val forge = SshHost(alias = "forge", hostname = "forge.test", username = "git", trustedKeyType = "ssh-ed25519", trustedFingerprint = "SHA256:device-test")
        val bridge = GitSshBridge(nonces, { listOf(forge) }, localPackExecutor())
        broker = GitRemoteBroker(credentials, nonces, proxy, bridge)
        service = BuiltinExecService(
            workspace = host,
            runner = BuiltinCommandRunner(
                groupLauncher = listOf("/system/bin/setsid"),
                shell = "/system/bin/sh",
                signaller = AndroidProcessGroupSignaller,
                scratchDirectory = { File(base, "scratch") },
            ),
            environment = environment,
            shell = "/system/bin/sh",
            gitRemote = broker,
        )
    }

    @After
    fun tearDown() {
        if (::broker.isInitialized) broker.close()
        upstream?.destroy()
        if (::base.isInitialized) base.deleteRecursively()
    }

    @Test
    fun https经凭据代理clone与push且令牌只出现在转发一侧() = runBlocking<Unit> {
        val result = shell(
            "git clone https://git.test/owner/repo.git work && cd work && git config user.name Tester && git config user.email t@example.com && " +
                "echo pushed > new.txt && git add new.txt && git commit -m device-push && git push origin main && " +
                "echo \"\$INFINITY_GIT_NONCE\" > ../nonce.txt && env | grep '^INFINITY_GIT_' | cut -d= -f1 | sort",
        )
        assertWithMessage(result.toJson().toString()).that(result.exitCode).isEqualTo(0)
        assertThat(File(workspace, "work/README").readText()).isEqualTo("device 1\n")
        assertThat(headMessage(File(served, "repo.git"))).isEqualTo("device-push")
        assertThat(result.stdout.lines().filter { it.startsWith("INFINITY_GIT_") })
            .containsExactly("INFINITY_GIT_CREDENTIAL_HOSTS", "INFINITY_GIT_NONCE", "INFINITY_GIT_PROXY", "INFINITY_GIT_SSH_BRIDGE").inOrder()

        // 转发一侧每个请求都带 Authorization，且不转发 nonce；推送确实经过 receive-pack。
        val basic = Credentials.basic(USER, TOKEN)
        assertThat(forwarded).isNotEmpty()
        assertThat(forwarded.map { it.second }.toSet()).containsExactly(basic)
        assertThat(forwarded.map { it.first }).contains("/owner/repo.git/git-receive-pack")
        assertThat(forwardedNonces.toSet()).containsExactly(null)
        val upstreamLog = File(base, "upstream.log").readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertThat(upstreamLog.map { it.getValue("auth").jsonPrimitive.content }.toSet()).containsExactly(basic)
        assertThat(upstreamLog.all { it.getValue("nonce").toString() == "null" }).isTrue()

        // 令牌不进入命令输出、子进程环境、工作区与 HOME 下的任何文件（含仓库 config）。
        assertNoToken(result.toJson().toString())
        assertThat(File(workspace, "work/.git/config").readText()).contains("https://git.test/owner/repo.git")
        listOf(workspace, File(base, "home"), File(base, "tmp")).forEach(::assertNoTokenInFiles)

        // 命令结束后 nonce 立即失效：以同一 nonce 访问代理被拒绝，上游不再收到请求。
        val nonce = File(workspace, "nonce.txt").readText().trim()
        assertThat(nonce).hasLength(GitNonceRegistry.NONCE_LENGTH)
        assertThat(nonces.activeCount).isEqualTo(0)
        val before = forwarded.size
        val replay = withContext(Dispatchers.IO) {
            OkHttpClient().newCall(
                Request.Builder().url("http://127.0.0.1:${proxy.port}/git.test/owner/repo.git/info/refs?service=git-upload-pack")
                    .header(GitCredentialProxy.NONCE_HEADER, nonce).build(),
            ).execute().use { it.code to it.header(GitCredentialProxy.ERROR_HEADER) }
        }
        assertThat(replay).isEqualTo(403 to "NONCE_REJECTED")
        assertThat(forwarded).hasSize(before)
    }

    @Test
    fun 未配置凭据的主机保持直连不经代理() = runBlocking<Unit> {
        val result = shell("git clone http://127.0.0.1:$upstreamPort/owner/repo.git direct && cat direct/README")
        assertWithMessage(result.toJson().toString()).that(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("device 1")
        assertThat(forwarded).isEmpty()
        val upstreamLog = File(base, "upstream.log").readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertThat(upstreamLog).isNotEmpty()
        assertThat(upstreamLog.all { it.getValue("auth").toString() == "null" }).isTrue()
    }

    @Test
    fun ssh远端经桥clone与push且未配置主机给出指引() = runBlocking<Unit> {
        val result = shell(
            "git clone git@forge.test:owner/ssh.git sshwork && cd sshwork && git config user.name Tester && git config user.email t@example.com && " +
                "echo over-ssh > s.txt && git add s.txt && git commit -m ssh-push && git push origin main",
        )
        assertWithMessage(result.toJson().toString()).that(result.exitCode).isEqualTo(0)
        assertThat(File(workspace, "sshwork/README").readText()).isEqualTo("device 1\n")
        assertThat(headMessage(File(served, "ssh.git"))).isEqualTo("ssh-push")
        assertThat(sshCommands).containsAtLeast("git-upload-pack 'owner/ssh.git'", "git-receive-pack 'owner/ssh.git'")
        assertThat(sshCommands.all { GitSshCommand.parse(it)?.render() == it }).isTrue()
        assertNoToken(result.toJson().toString())

        val refused = shell("git clone git@github.com:octocat/Hello-World.git nope")
        assertThat(refused.exitCode).isNotEqualTo(0)
        assertThat(refused.stderr).contains("SSH_HOST_NOT_CONFIGURED")
        assertThat(refused.stderr).contains("github.com")
        assertThat(nonces.activeCount).isEqualTo(0)
    }

    private suspend fun shell(command: String, timeout: Int = 180): CommandToolResult =
        service.runShell("root", "s", command, null, timeout)

    private fun assertNoToken(text: String) {
        assertThat(text).doesNotContain(TOKEN)
        assertThat(text).doesNotContain(Credentials.basic(USER, TOKEN).removePrefix("Basic "))
    }

    private fun assertNoTokenInFiles(root: File) {
        val needle = TOKEN.toByteArray()
        root.walkTopDown().filter(File::isFile).forEach { file ->
            val bytes = file.readBytes()
            val found = (0..bytes.size - needle.size).any { start -> needle.indices.all { bytes[start + it] == needle[it] } }
            assertWithMessage("${file.path} 含令牌").that(found).isFalse()
        }
    }

    /** 以打包 Python 同步运行一段脚本，返回退出码与合并输出。 */
    private fun python(script: String, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf(environment.pythonLauncher.path, "-c", script) + args)
            .directory(base).redirectErrorStream(true)
            .apply { environment().clear(); environment().putAll(pythonEnv) }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun headMessage(repository: File): String = python(HEAD_MESSAGE, repository.path).let { (code, output) ->
        assertWithMessage(output).that(code).isEqualTo(0)
        output.trim()
    }

    private fun startUpstream() {
        val portFile = File(base, "upstream.port")
        val process = ProcessBuilder(
            listOf(environment.pythonLauncher.path, "-c", UPSTREAM, File(served, "repo.git").path, portFile.path, File(base, "upstream.log").path),
        ).directory(base).redirectErrorStream(true).redirectOutput(File(base, "upstream.out"))
            .apply { environment().clear(); environment().putAll(pythonEnv) }
            .start()
        upstream = process
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!portFile.isFile && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
        assertWithMessage("上游服务未启动：" + File(base, "upstream.out").takeIf(File::isFile)?.readText().orEmpty())
            .that(portFile.isFile).isTrue()
        upstreamPort = portFile.readText().trim().toInt()
    }

    /** 以 dulwich 服务端处理器在本机执行 git-upload-pack/receive-pack，充当 SSH 远端。 */
    private fun localPackExecutor() = GitSshExecutor { _, command, _, stdin, stdout, stderr, onStarted ->
        sshCommands += command
        val parsed = requireNotNull(GitSshCommand.parse(command))
        val repository = File(served.parentFile, parsed.path.trimStart('/', '~'))
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(listOf(environment.pythonLauncher.path, "-c", SERVE_PACK, parsed.service, repository.path))
                .directory(base)
                .apply { environment().clear(); environment().putAll(pythonEnv) }
                .start()
            onStarted()
            // 进程 stdin 是带缓冲的管道流：每段都要 flush，否则客户端的 want/done 滞留在缓冲区，双方互等。
            thread(isDaemon = true) {
                val buffer = ByteArray(65_536)
                try {
                    while (true) {
                        val count = stdin.read(buffer)
                        if (count < 0) break
                        process.outputStream.write(buffer, 0, count)
                        process.outputStream.flush()
                    }
                } catch (_: IOException) {
                } finally { runCatching { process.outputStream.close() } }
            }
            val errors = thread(isDaemon = true) { runCatching { process.errorStream.copyTo(stderr) } }
            process.inputStream.copyTo(stdout)
            errors.join(10_000)
            process.waitFor()
        }
    }

    private companion object {
        const val USER = "device-user"
        const val TOKEN = "nxt_DeviceSyntheticToken_7f3a9c21"

        val SEED = """
            import sys
            from dulwich.repo import Repo
            from dulwich.objects import Blob, Commit, Tree
            for path in sys.argv[1:]:
                repo = Repo.init_bare(path, mkdir=True)
                blob = Blob.from_string(b"device 1\n")
                tree = Tree()
                tree.add(b"README", 0o100644, blob.id)
                commit = Commit()
                commit.tree = tree.id
                commit.author = commit.committer = b"Seed <seed@example.invalid>"
                commit.author_time = commit.commit_time = 0
                commit.author_timezone = commit.commit_timezone = 0
                commit.message = b"seed\n"
                for item in (blob, tree, commit):
                    repo.object_store.add_object(item)
                repo.refs[b"refs/heads/main"] = commit.id
                repo.refs.set_symbolic_ref(b"HEAD", b"refs/heads/main")
                repo.close()
        """.trimIndent()

        val HEAD_MESSAGE = """
            import sys
            from dulwich.repo import Repo
            with Repo(sys.argv[1]) as repo:
                print(repo[repo.refs[b"refs/heads/main"]].message.decode().strip())
        """.trimIndent()

        /** 智能 HTTP 上游：记录每个请求的路径、Authorization 与是否收到 nonce 头。 */
        val UPSTREAM = """
            import json, os, sys, threading
            from wsgiref.simple_server import WSGIRequestHandler, make_server
            from dulwich.repo import Repo
            from dulwich.server import DictBackend
            from dulwich.web import make_wsgi_chain
            path, port_file, log_file = sys.argv[1:4]
            application = make_wsgi_chain(DictBackend({"/owner/repo.git": Repo(path)}))
            lock = threading.Lock()
            def recording(environ, start_response):
                entry = {"path": environ.get("PATH_INFO"), "auth": environ.get("HTTP_AUTHORIZATION"),
                         "nonce": environ.get("HTTP_X_INFINITY_GIT_NONCE")}
                with lock, open(log_file, "a") as handle:
                    handle.write(json.dumps(entry) + "\n")
                return application(environ, start_response)
            class Quiet(WSGIRequestHandler):
                def log_message(self, *args):
                    pass
            server = make_server("127.0.0.1", 0, recording, handler_class=Quiet)
            with open(port_file + ".tmp", "w") as handle:
                handle.write(str(server.server_port))
            os.replace(port_file + ".tmp", port_file)
            server.serve_forever()
        """.trimIndent()

        /** dulwich.cli 的 receive-pack 从管道缓冲读取会等到 EOF；按 TCP 服务端的方式提供 recv。 */
        val SERVE_PACK = """
            import os, sys
            from dulwich.protocol import ReceivableProtocol
            from dulwich.server import FileSystemBackend, ReceivePackHandler, UploadPackHandler
            def write(data):
                view = memoryview(data)
                while view:
                    view = view[os.write(1, view):]
            service, path = sys.argv[1], sys.argv[2]
            handler = ReceivePackHandler if service == "git-receive-pack" else UploadPackHandler
            handler(FileSystemBackend(path), [path], ReceivableProtocol(lambda size: os.read(0, size), write)).handle()
        """.trimIndent()
    }
}
