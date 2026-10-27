package com.promenar.nexara.data.branch

import com.promenar.nexara.data.workspace.LinuxSessionPaths
import com.promenar.nexara.domain.storage.ExternalFolderException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BranchLinuxFilePathsTest {
    private val cwd = "/workspace/branches/foo"

    @Test fun `相对路径使用可信分支cwd而绝对workspace路径保持父命名空间`() {
        assertEquals("$cwd/probe", LinuxSessionPaths.normalize("probe", cwd))
        assertEquals("/workspace/probe", LinuxSessionPaths.normalize("/workspace/probe", cwd))
    }

    @Test fun `缺省列目录和创建父目录均使用分支cwd`() {
        for (tool in listOf("list_files", "create_file", "create_directory")) {
            val key = if (tool == "list_files") "path" else "parentPath"
            assertEquals(cwd, LinuxSessionPaths.normalizeArguments(tool, JsonObject(emptyMap()), cwd).getValue(key).jsonPrimitive.content)
        }
    }

    @Test fun `执行参数归一对所有相对路径使用同一cwd且不接受参数指定cwd`() {
        val args = Json.parseToJsonElement("""{"path":"probe","parentPath":"dir","newParentPath":"target","workingDirectory":"/other"}""").jsonObject
        val normalized = LinuxSessionPaths.normalizeArguments("move_file", args, cwd)
        assertEquals("$cwd/probe", normalized.getValue("path").jsonPrimitive.content)
        assertEquals("$cwd/dir", normalized.getValue("parentPath").jsonPrimitive.content)
        assertEquals("$cwd/target", normalized.getValue("newParentPath").jsonPrimitive.content)
    }

    @Test fun `rootfs和必要挂载内的分支相对路径也按可信cwd解析`() {
        for (directory in listOf("/root/branches/foo", "/mnt/repo/branches/foo")) {
            assertEquals("$directory/probe", LinuxSessionPaths.normalize("probe", directory))
        }
    }

    @Test fun `相对越界和不规范cwd拒绝`() {
        for (path in listOf("../probe", "a/../probe", "./probe")) {
            assertThrows(ExternalFolderException::class.java) { LinuxSessionPaths.normalize(path, cwd) }
        }
        assertThrows(ExternalFolderException::class.java) { LinuxSessionPaths.normalize("probe", "relative") }
    }
}
