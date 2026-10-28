package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDataMonitorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(root: File, path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("before") }
    private fun changes(value: kotlinx.serialization.json.JsonObject) = value["runtime_changes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    @Test fun `技能文件修改新增删除均报告`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val skill = file(root, "files/agent_skills/foo/SKILL.md")
        val removed = file(root, "files/agent_skills/foo/removed.md")
        val monitor = RuntimeDataMonitor(root)
        val before = monitor.capture()
        skill.appendText(" changed")
        removed.delete()
        file(root, "files/agent_skills/foo/new.md")
        assertThat(changes(monitor.changesSince(before))).containsExactly(
            "files/agent_skills/foo/SKILL.md", "files/agent_skills/foo/removed.md (deleted)", "files/agent_skills/foo/new.md (added)",
        )
    }

    @Test fun `技能内容等长且仅mtime变化时也报告`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val skill = file(root, "files/agent_skills/foo/SKILL.md")
        val monitor = RuntimeDataMonitor(root)
        val before = monitor.capture()
        check(skill.setLastModified(skill.lastModified() + 10_000))
        assertThat(changes(monitor.changesSince(before))).containsExactly("files/agent_skills/foo/SKILL.md")
    }

    @Test fun `运行后才超限也跳过分组且不误报删除`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        file(root, "files/agent_skills/a.md")
        val monitor = RuntimeDataMonitor(root, maxEntries = 3)
        val before = monitor.capture()
        file(root, "files/agent_skills/b.md")
        file(root, "files/agent_skills/c.md")
        val after = monitor.changesSince(before)
        assertThat(changes(after)).isEmpty()
        assertThat(after["runtime_scan_notes"]!!.toString()).contains("files/agent_skills")
    }

    @Test fun `忽略的数据库日志也消耗遍历预算`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        repeat(5) { file(root, "databases/db$it-wal") }
        val monitor = RuntimeDataMonitor(root, maxEntries = 3)
        val result = monitor.changesSince(monitor.capture())
        assertThat(changes(result)).isEmpty()
        assertThat(result["runtime_scan_notes"]?.toString()).contains("databases")
    }

    @Test fun `数据库删除或替换报告身份变化且不混入内容变化`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val database = file(root, "databases/infinity_v2.db")
        val removed = file(root, "databases/removed.db")
        val monitor = RuntimeDataMonitor(root)
        val before = monitor.capture()
        Files.move(database.toPath(), File(root, "old.db").toPath())
        file(root, "databases/infinity_v2.db")
        removed.delete()
        file(root, "databases/unknown.db")
        assertThat(changes(monitor.changesSince(before))).containsExactly(
            "databases/infinity_v2.db (replaced)", "databases/removed.db (deleted)", "databases/unknown.db (added)",
        )
    }

    @Test fun `正常数据库偏好日志写入和 pip 缓存变化不报告`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val database = file(root, "databases/infinity_v2.db")
        val prefs = file(root, "shared_prefs/settings.xml")
        val log = file(root, "files/infinity_logs.txt")
        val cache = file(root, "no_backup/python-pip/a")
        val monitor = RuntimeDataMonitor(root)
        val before = monitor.capture()
        database.appendText("database write")
        prefs.appendText("prefs write")
        log.appendText("log write")
        file(root, "databases/infinity_v2.db-wal")
        file(root, "databases/infinity_v2.db-shm")
        file(root, "databases/infinity_v2.db-journal")
        cache.delete()
        file(root, "no_backup/python-pip/b")
        file(root, "no_backup/exec-ca/cert")
        file(root, "no_backup/tailnet/state")
        file(root, "files/python-user/package.py")
        assertThat(monitor.changesSince(before)).isEmpty()
    }

    @Test fun `偏好文件只报告删除，应用提交时的改名重建与新建不报告`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val prefs = file(root, "shared_prefs/settings.xml")
        val removed = file(root, "shared_prefs/removed.xml")
        val monitor = RuntimeDataMonitor(root)
        val before = monitor.capture()
        // 与 SharedPreferences 提交顺序一致：原文件改名为 .bak，新建同名文件后删除 .bak。
        Files.move(prefs.toPath(), File(root, "shared_prefs/settings.xml.bak").toPath())
        file(root, "shared_prefs/settings.xml")
        file(root, "shared_prefs/first-use.xml")
        removed.delete()
        assertThat(changes(monitor.changesSince(before))).containsExactly("shared_prefs/removed.xml (deleted)")
    }

    @Test fun `日志首次创建不报告，删除报告`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val diagnostics = file(root, "files/infinity_diagnostics.jsonl")
        val monitor = RuntimeDataMonitor(root)
        val before = monitor.capture()
        file(root, "files/infinity_logs.txt")
        diagnostics.delete()
        assertThat(changes(monitor.changesSince(before))).containsExactly("files/infinity_diagnostics.jsonl (deleted)")
    }

    @Test fun `no_backup 只报告顶层删除替换且忽略内部变化和新建`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val content = file(root, "no_backup/skill_state/a")
        val old = file(root, "no_backup/background-jobs/state").parentFile
        val removed = file(root, "no_backup/external-versions/state").parentFile
        val monitor = RuntimeDataMonitor(root)
        val before = monitor.capture()
        content.appendText(" changed")
        Files.move(old.toPath(), File(root, "old-jobs").toPath())
        file(root, "no_backup/background-jobs/new")
        removed.deleteRecursively()
        file(root, "no_backup/new-top/new")
        assertThat(changes(monitor.changesSince(before))).containsExactly(
            "no_backup/background-jobs (replaced)", "no_backup/external-versions (deleted)",
        )
    }

    @Test fun `超限分组跳过并注明而其它分组继续检测`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        file(root, "files/agent_skills/foo/SKILL.md")
        val db = file(root, "databases/infinity_v2.db")
        val monitor = RuntimeDataMonitor(root, maxEntries = 2)
        val before = monitor.capture()
        db.delete()
        val result = monitor.changesSince(before)
        assertThat(changes(result)).containsExactly("databases/infinity_v2.db (deleted)")
        assertThat(result["runtime_scan_notes"]!!.toString()).contains("files/agent_skills")
    }

    @Test fun `技能符号链接不递归且外部文件写入不报告`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val external = file(root, "outside/a")
        val skills = File(root, "files/agent_skills").apply { mkdirs() }
        Files.createSymbolicLink(File(skills, "linked").toPath(), external.parentFile.toPath())
        val monitor = RuntimeDataMonitor(root)
        val before = monitor.capture()
        external.appendText(" changed")
        assertThat(monitor.changesSince(before)).isEmpty()
    }
}
