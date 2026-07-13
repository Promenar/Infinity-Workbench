package com.promenar.nexara.release

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class ReleaseReachableUiLocalizationContractTest {

    private val sourcePaths = listOf(
        "ui/chat/ResourceExplorerSheet.kt",
        "ui/chat/components/FilesPanel.kt",
        "ui/chat/components/RagDetailsSheet.kt",
        "ui/chat/components/RecycleBinPanel.kt",
        "ui/chat/SessionSettingsSheet.kt",
        "ui/chat/PipelineBubble.kt",
        "ui/chat/ChatInlineComponents.kt",
        "ui/common/MarkdownText.kt",
        "ui/common/AgentAvatar.kt",
        "ui/rag/AdvancedRetrievalScreen.kt",
        "ui/rag/GlobalRagConfigScreen.kt",
        "ui/hub/AgentAdvancedRetrievalScreen.kt",
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
        "rag_details_title",
        "rag_details_tab_retrieved",
        "rag_details_tab_web_search",
        "rag_details_tab_knowledge_graph",
        "rag_details_section_retrieved",
        "rag_details_section_web_search",
        "rag_details_unknown_webpage",
        "rag_details_empty",
        "rag_details_score_vector",
        "rag_details_score_rerank",
        "rag_details_rank_up",
        "rag_details_rank_down",
        "rag_details_path_label",
        "rag_details_keywords",
        "rag_details_open_link",
        "recycle_bin_auto_cleanup",
        "recycle_bin_permanent_delete_title",
        "recycle_bin_permanent_delete_message",
        "recycle_bin_empty_title",
        "recycle_bin_empty_message",
        "recycle_bin_empty_confirm",
        "recycle_bin_restore_all",
        "recycle_bin_clear_count",
        "recycle_bin_original_path",
        "recycle_bin_restore",
        "recycle_bin_empty_state",
        "sheet_tool_economy_mode",
        "sheet_tool_gemini_grounding",
        "sheet_settings_rerank_unavailable",
        "retrieval_rerank_model_unconfigured",
        "retrieval_rerank_model_unavailable_message",
        "agent_retrieval_rerank_model_unavailable_message",
        "chat_tool_default_name",
        "chat_tool_error",
        "chat_tool_arguments",
        "chat_cd_tool_image",
        "chat_cd_attached_image",
        "chat_rag_phase_query_intent",
        "chat_rag_phase_embedding",
        "chat_rag_phase_memory",
        "chat_rag_phase_documents",
        "chat_rag_phase_keyword",
        "chat_rag_phase_hybrid",
        "chat_rag_phase_ranking",
        "chat_rag_phase_rerank",
        "chat_rag_phase_knowledge_graph",
        "chat_rag_phase_context_compress",
        "chat_rag_phase_prompt_build",
        "chat_rag_phase_context_ready",
        "chat_rag_phase_retrieved",
        "chat_rag_ready_references",
        "chat_rag_ready_web",
        "chat_rag_ready_knowledge",
        "chat_rag_ready_generic",
        "chat_rag_preparing",
        "chat_rag_done",
        "chat_postprocess_memory",
        "chat_postprocess_summary",
        "chat_approval_executed_at",
        "chat_cd_generating_response",
        "chat_cd_heading",
        "agent_avatar",
        "agent_avatar_edit",
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
            "\"引用内容\"", "\"知识检索\"", "\"联网搜索\"", "\"知识图谱\"",
            "\"检索片段 (Retrieved Chunks)\"", "\"联网引用 (Web Search Citations)\"",
            "\"未知网页\"", "\"暂无数据\"", "\"重排排名", "\"关键词:",
            "\"30 天后自动清理\"", "\"永久删除\"", "\"清空回收站\"",
            "\"恢复全部\"", "\"清空 (", "\"原始路径:", "\"恢复\"", "\"回收站为空\"",
            "\"Token 节约模式\"", "\"Gemini 联网 Grounding\"",
            "\"⚠️ 未配置默认重排模型", "\"未配置模型\"", "\"⚠️ 未检测到已配置的重排模型",
            "if (value == 0f) \"自动\"",
            "\"正在思考\"", "\"思考完成\"", "\"工具\"", "\"指令有误\"",
            "\"调用参数: ", "\"复制正文\"", "\"重发\"", "\"重新生成\"", "\"删除消息\"",
            "\"分析查询意图\"", "\"向量库检索\"", "\"关键词检索\"", "\"混合检索融合\"",
            "\"知识图谱关系检索\"", "\"相关性重排过滤\"", "\"上下文提示词压缩\"",
            "\"注入大模型上下文\"", "\"✓ 引用内容就绪\"", "\"✓ 联网搜索就绪\"",
            "\"✓ 知识检索就绪\"", "\"正在准备检索上下文...\"", "\"✓ 检索就绪\"",
            "text = \"Done\"", "text = \"Active\"", "-> \"Memory\"", "-> \"Summary\"",
            "\"Approved by You at ", "contentDescription = \"Generating response\"",
            "contentDescription = \"标题: ",
        )

        banned.forEach { literal ->
            assertThat(sources.values.joinToString("\n")).doesNotContain(literal)
        }
    }

    @Test
    fun `聊天展示层不伪造工具耗时且交互语义可本地化`() {
        val pipeline = source("ui/chat/PipelineBubble.kt")
        val inline = source("ui/chat/ChatInlineComponents.kt")
        val avatar = source("ui/common/AgentAvatar.kt")

        assertThat(inline).doesNotContain("1.2s")
        assertThat(inline).doesNotContain("Mock for now")
        assertThat(pipeline).contains("stringResource(R.string.chat_action_copy)")
        assertThat(pipeline).contains("stringResource(R.string.chat_action_regenerate)")
        assertThat(pipeline).contains("stringResource(R.string.chat_action_delete)")
        assertThat(avatar).contains("Role.Button")
        assertThat(avatar).contains("R.string.agent_avatar_edit")
        assertThat(avatar).contains("onClickLabel = avatarDescription")
        assertThat(inline).contains("\"ready\" -> R.string.chat_rag_phase_context_ready")
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
        assertThat(valueOf(english, "rag_details_title")).isEqualTo("References")
        assertThat(valueOf(chinese, "rag_details_title")).isEqualTo("引用内容")
        assertThat(valueOf(english, "recycle_bin_restore")).isEqualTo("Restore")
        assertThat(valueOf(chinese, "recycle_bin_restore")).isEqualTo("恢复")
        assertThat(valueOf(english, "retrieval_rerank_model_unconfigured")).doesNotContain("未配置")
        assertThat(valueOf(chinese, "retrieval_rerank_model_unconfigured")).contains("未配置")

        requiredKeys.forEach { key ->
            assertThat(valueOf(english, key)).doesNotMatch(".*[\\u4E00-\\u9FFF].*")
            assertThat(formatArguments(valueOf(english, key)))
                .containsExactlyElementsIn(formatArguments(valueOf(chinese, key)))
        }
    }

    @Test
    fun `英文和简体中文字符串资源键完全一致`() {
        val english = resource("values/strings.xml")
        val chinese = resource("values-zh-rCN/strings.xml")

        assertThat(resourceKeys(english)).containsExactlyElementsIn(resourceKeys(chinese))
    }

    @Test
    fun `回收站图标操作提供至少48dp触控目标和可本地化语义`() {
        val recycleBin = source("ui/chat/components/RecycleBinPanel.kt")
        val accessibleIconButtons = Regex(
            "IconButton\\s*\\([\\s\\S]{0,220}?Modifier\\.size\\(48\\.dp\\)",
        ).findAll(recycleBin).count()

        assertThat(accessibleIconButtons).isAtLeast(2)
        assertThat(recycleBin).contains("contentDescription = stringResource(R.string.recycle_bin_restore)")
        assertThat(recycleBin).contains("contentDescription = stringResource(R.string.recycle_bin_permanent_delete_title)")
        assertThat(recycleBin).contains("R.string.files_time_minutes_ago")
        assertThat(recycleBin).contains("R.string.files_time_hours_ago")
        assertThat(recycleBin).contains("R.string.files_time_days_ago")
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

    private fun resourceKeys(xml: String): Set<String> =
        Regex("<string name=\\\"([^\\\"]+)\\\"")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toSet()

    private fun formatArguments(value: String): List<String> =
        Regex("%\\d+\\$[0-9.]*[a-zA-Z]")
            .findAll(value)
            .map { it.value }
            .toList()
}
