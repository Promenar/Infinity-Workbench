package com.promenar.infinity.data.branch

import com.promenar.infinity.data.exec.linuxExecSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class BranchLinuxCommandWorkspaceTest {
    private val base = File("/host/parent-repo")
    private val child = File(base, "branches/child")
    private val home = File("/host/private-child")
    private val skills = File("/host/skills")

    @Test fun `分支规格保留创建worktree的父挂载且默认cwd进入分支`() {
        val spec = linuxExecSpec("git status", child, "project", emptyList(), skills, home,
            BranchLinuxCommandWorkspace(base, "/workspace/branches/child"))
        assertEquals(base, spec.workspace)
        assertEquals("/workspace/branches/child", spec.workingDirectory)
        assertEquals("/workspace/branches/child", spec.environment["INFINITY_WORKSPACE"])
        assertEquals(home, spec.sessionHome)
        assertEquals(listOf("/bin/sh", "-c", "git status"), spec.argv)
    }

    @Test fun `rootfs内分支仍保留原workspace绑定且cwd使用Linux目录`() {
        val rootfsChild = File("/host/rootfs/root/branches/child")
        val spec = linuxExecSpec("pwd", rootfsChild, "project", emptyList(), skills, home,
            BranchLinuxCommandWorkspace(base, "/root/branches/child"))
        assertEquals(base, spec.workspace)
        assertEquals("/root/branches/child", spec.workingDirectory)
        assertEquals("/root/branches/child", spec.environment["INFINITY_WORKSPACE"])
        assertEquals(home, spec.sessionHome)
    }

    @Test fun `普通会话规格的工作区环境与默认cwd保持原契约`() {
        val spec = linuxExecSpec("pwd", base, "private", emptyList(), skills, home)
        assertEquals(base, spec.workspace)
        assertNull(spec.workingDirectory)
        assertEquals("/workspace", spec.environment["INFINITY_WORKSPACE"])
        assertEquals("private", spec.environment["INFINITY_WORKSPACE_KIND"])
        assertEquals(home, spec.sessionHome)
        assertEquals(listOf("/mnt/skills"), spec.mounts.map { it.guestPath })
    }

    @Test fun `分支启动不把父目录或分支目录用作child私有home`() {
        val spec = linuxExecSpec("pwd", child, "project", emptyList(), skills, home,
            BranchLinuxCommandWorkspace(base, "/workspace/branches/child"))
        assertEquals(home, spec.sessionHome)
        assertEquals(skills, spec.mounts.single().host)
        assertEquals("/mnt/skills", spec.environment["INFINITY_SKILLS"])
        assertEquals("linux", spec.environment["INFINITY_BACKEND"])
    }

    @Test fun `拒绝宿主相对根与不规范guest目录`() {
        assertThrows(IllegalArgumentException::class.java) { BranchLinuxCommandWorkspace(File("parent"), "/workspace/child") }
        for (path in listOf("", "child", "/", "/workspace/../child", "/workspace//child", "/workspace/child/", "/workspace/child\n")) {
            assertThrows(path, IllegalArgumentException::class.java) { BranchLinuxCommandWorkspace(base, path) }
        }
    }
}
