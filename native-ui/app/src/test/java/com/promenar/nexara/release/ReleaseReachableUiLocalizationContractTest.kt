package com.promenar.nexara.release

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class ReleaseReachableUiLocalizationContractTest {

    private val sourcePaths = listOf(
        "ui/chat/ResourceExplorerSheet.kt",
        "ui/chat/components/FilesPanel.kt",
        "ui/settings/ProviderModelsScreen.kt",
    )

    private val requiredKeys = listOf(
        "resource_explorer_title",
        "resource_explorer_search_placeholder",
        "resource_explorer_import_files",
        "resource_explorer_import_status",
        "resource_explorer_recycle_bin_count",
        "files_selected_count",
        "files_reindex",
        "files_extract_knowledge_graph",
        "files_view_graph",
        "files_move_to",
        "files_empty_subtitle",
        "files_time_minutes_ago",
        "provider_models_type_chat",
        "provider_models_capability_vision",
        "provider_models_field_model_id",
        "provider_models_field_display_name",
        "provider_models_output_tokens",
        "provider_models_knowledge_cutoff",
    )

    @Test
    fun `发行可达界面不保留已知用户可见硬编码`() {
        val sources = sourcePaths.associateWith(::source)
        val banned = listOf(
            "\"资源管理器\"", "\"搜索文件...\"", "\"正在导入\"", "\"导入文件\"", "\"回收站",
            "\"导入状态\"", "\"等待中\"", "\"已拒绝：", "\"工作区未就绪\"", "\"未知原因\"",
            "\"已选 ", "\"重索引\"", "\"重新索引\"", "\"提取知识图谱\"", "\"查看图谱\"",
            "\"查看目录图谱\"", "\"重命名\"", "\"移动到…\"", "\"多选模式\"", "\"新名称\"",
            "\"没有可用的目录\"", "\"根目录\"", "\"暂无文件\"", "\"刚刚\"", "分钟前\"", "小时前\"", "天前\"",
            "\"Model ID\"", "\"Display Name\"", "\"Optional\"", "\"tokens\"",
            "\"Output:", "\"截止:", "\"Chat\"", "\"Reasoning\"", "\"Vision\"",
            "\"Internet\"", "\"Audio In\"", "\"Audio Out\"", "\"Computer\"",
        )

        banned.forEach { literal ->
            assertThat(sources.values.joinToString("\n")).doesNotContain(literal)
        }
    }

    @Test
    fun `新增发行文案在英文和简体中文资源中成对存在`() {
        val english = resource("values/strings.xml")
        val chinese = resource("values-zh-rCN/strings.xml")

        requiredKeys.forEach { key ->
            assertThat(english).contains("name=\"$key\"")
            assertThat(chinese).contains("name=\"$key\"")
        }
        assertThat(valueOf(english, "resource_explorer_title")).isEqualTo("Resource Explorer")
        assertThat(valueOf(chinese, "resource_explorer_title")).isEqualTo("资源管理器")
        assertThat(valueOf(english, "provider_models_output_tokens")).contains("Output")
        assertThat(valueOf(chinese, "provider_models_output_tokens")).contains("输出")
    }

    private fun source(relative: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/$relative"),
    ).toString(Charsets.UTF_8)

    private fun resource(relative: String): String = Files.readAllBytes(
        Path.of("app/src/main/res/$relative"),
    ).toString(Charsets.UTF_8)

    private fun valueOf(xml: String, key: String): String {
        val pattern = Regex("<string name=\\\"${Regex.escape(key)}\\\">(.*?)</string>")
        return pattern.find(xml)?.groupValues?.get(1) ?: error("缺少资源键: $key")
    }
}
