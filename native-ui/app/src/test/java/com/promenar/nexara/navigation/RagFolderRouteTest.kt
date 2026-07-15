package com.promenar.nexara.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RagFolderRouteTest {
    @Test
    fun `文件夹 route 只使用安全标识且不携带展示名称`() {
        listOf(
            "需求?草案",
            "阶段#2",
            "100% Ready",
            "含 空格 名称",
            "中文资料库",
        ).forEach { folderName ->
            val route = NavDestinations.ragFolder("folder-uuid")

            assertThat(route).isEqualTo("rag_folder/folder-uuid")
            assertThat(route).doesNotContain(folderName)
        }
    }
}
