package com.promenar.nexara.data.git

import java.io.File

/** 测试用打包 Python：只提供路径与环境，不真正启动解释器。 */
internal class FakePythonRuntime(override var available: Boolean = true) : GitPythonRuntime {
    override val launcher: File = File("/fake/lib/libnxpython.so")
    val environments = mutableListOf<Pair<File, String>>()

    override fun environment(workspace: File, kind: String): Map<String, String> {
        environments += workspace to kind
        return mapOf("NEXARA_WORKSPACE" to workspace.path, "NEXARA_WORKSPACE_KIND" to kind)
    }
}

/**
 * 测试用检查命令运行器：按操作名返回 JSON 并写入 `--output` 指定的文件，记录每次调用。
 * [respond] 返回 null 时不写文件。
 */
internal class FakeInspectRunner(
    var exitCode: Int? = 0,
    var timedOut: Boolean = false,
    var stderr: String = "",
    var respond: (operation: String, args: List<String>) -> String? = { _, _ -> null },
) : GitInspectCommandRunner {
    /** 面板视图模型会并行读取状态与提交列表，记录需线程安全。 */
    val commands: MutableList<GitInspectCommand> = java.util.concurrent.CopyOnWriteArrayList()

    override suspend fun run(command: GitInspectCommand): GitInspectProcessResult {
        commands += command
        val argv = command.argv
        val flag = argv.indexOf(GitInspector.FLAG)
        val operation = argv[flag + 1]
        val output = File(argv[argv.indexOf("--output") + 1])
        respond(operation, argv.drop(flag + 2))?.let { output.writeText(it) }
        return GitInspectProcessResult(exitCode, timedOut, stderr)
    }

    fun operations(): List<String> = commands.map { it.argv[it.argv.indexOf(GitInspector.FLAG) + 1] }

    /** 某次调用中某个选项的全部取值。 */
    fun values(index: Int, option: String): List<String> {
        val argv = commands[index].argv
        return argv.indices.filter { argv[it] == option }.map { argv[it + 1] }
    }
}

internal object InspectFixtures {
    const val SHA_A = "1111111111111111111111111111111111111111"
    const val SHA_B = "2222222222222222222222222222222222222222"

    val branches = """
        {"version":1,"repository":true,"truncated":false,"branches":[
         {"name":"main","current":true,"short_sha":"2222222","author_time":1700000000,"title":"第二个提交",
          "upstream":"origin/main","ahead":2,"behind":1,"upstream_missing":false,"tracking_truncated":false},
         {"name":"topic","current":false,"short_sha":"1111111","author_time":1690000000,"title":"第一个提交",
          "upstream":null,"ahead":null,"behind":null,"upstream_missing":false,"tracking_truncated":false}]}
    """.trimIndent()

    val status = """
        {"version":1,"repository":true,"branch":"main","detached":false,"head":"$SHA_B","unborn":false,
         "staged":[{"path":"src/App.kt","change":"modified"},{"path":"docs/new.md","change":"added"}],
         "staged_truncated":false,
         "unstaged":[{"path":"src/App.kt","change":"modified"},{"path":"old.txt","change":"deleted"}],
         "unstaged_truncated":true,
         "untracked":[{"path":"notes.txt","change":"added"}],"untracked_truncated":false,"future_field":1}
    """.trimIndent()

    val log = """
        {"version":1,"repository":true,"truncated":true,"commits":[
          {"sha":"$SHA_B","short_sha":"2222222","author_name":"测试者","author_email":"t@example.com",
           "author_time":1700000000,"author_tz":28800,"title":"第二次提交","parents":["$SHA_A"]},
          {"sha":"$SHA_A","short_sha":"1111111","author_name":"测试者","author_email":"t@example.com",
           "author_time":1699990000,"author_tz":0,"title":"init","parents":[]}]}
    """.trimIndent()

    val commit = """
        {"version":1,"repository":true,"files_truncated":false,
         "commit":{"sha":"$SHA_B","short_sha":"2222222","author_name":"测试者","author_email":"t@example.com",
           "author_time":1700000000,"author_tz":28800,"title":"第二次提交","parents":["$SHA_A"],"message":"第二次提交\n\n正文"},
         "files":[{"path":"src/App.kt","change":"modified"},{"path":"src/New.kt","change":"renamed","old_path":"src/Old.kt"},
                  {"path":"bin/data","change":"copied"}]}
    """.trimIndent()

    fun blob(rev: String, path: String, text: String?, exists: Boolean = true, binary: Boolean = false, truncated: Boolean = false, size: Long = text?.length?.toLong() ?: 0) =
        """{"rev":${quote(rev)},"path":${quote(path)},"exists":$exists,"mode":"100644","symlink":false,"submodule":false,
            "binary":$binary,"size":$size,"truncated":$truncated,"text":${text?.let(::quote) ?: "null"}}"""

    fun read(vararg blobs: String) = """{"version":1,"repository":true,"blobs":[${blobs.joinToString(",")}]}"""

    /** 按 `--blob 版本:路径` 参数回显内容为 `<版本>:<路径>` 的文本，便于断言两侧顺序。 */
    fun echoRead(args: List<String>): String {
        val specs = args.indices.filter { args[it] == "--blob" }.map { args[it + 1] }
        return read(*specs.map { spec -> blob(spec.substringBefore(':'), spec.substringAfter(':'), "$spec\n") }.toTypedArray())
    }

    private fun quote(value: String) = kotlinx.serialization.json.JsonPrimitive(value).toString()
}
