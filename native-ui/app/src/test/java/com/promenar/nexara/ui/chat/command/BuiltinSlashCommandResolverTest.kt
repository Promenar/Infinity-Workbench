package com.promenar.nexara.ui.chat.command

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.domain.usecase.ExportSessionUseCase
import org.junit.Test

class BuiltinSlashCommandResolverTest {
    private val idle = SlashCommandContext(hasMessages = true)
    private fun resolve(command: BuiltinSlashCommand, argument: String = "", context: SlashCommandContext = idle) =
        BuiltinSlashCommandResolver.resolve(command, argument, context)

    @Test fun `new 新建会话并忽略参数`() {
        assertThat(resolve(BuiltinSlashCommand.NEW)).isEqualTo(SlashCommandAction.CreateSession)
        assertThat(resolve(BuiltinSlashCommand.NEW, "随便", SlashCommandContext())).isEqualTo(SlashCommandAction.CreateSession)
    }

    @Test fun `rename 带标题直接重命名，缺少标题打开对话框`() {
        assertThat(resolve(BuiltinSlashCommand.RENAME, "  周报整理 ")).isEqualTo(SlashCommandAction.Rename("周报整理"))
        assertThat(resolve(BuiltinSlashCommand.RENAME, "第一行\n 第二行")).isEqualTo(SlashCommandAction.Rename("第一行 第二行"))
        assertThat(resolve(BuiltinSlashCommand.RENAME)).isEqualTo(SlashCommandAction.OpenRenameDialog)
        assertThat(resolve(BuiltinSlashCommand.RENAME, " \n ")).isEqualTo(SlashCommandAction.OpenRenameDialog)
    }

    @Test fun `prompt 带内容保存，缺少内容打开编辑器`() {
        assertThat(resolve(BuiltinSlashCommand.PROMPT, "用中文回答\n保持简短")).isEqualTo(SlashCommandAction.SetSessionPrompt("用中文回答\n保持简短"))
        assertThat(resolve(BuiltinSlashCommand.PROMPT)).isEqualTo(SlashCommandAction.OpenPromptEditor)
    }

    @Test fun `compact 生成中或压缩中拒绝，其余执行`() {
        assertThat(resolve(BuiltinSlashCommand.COMPACT)).isEqualTo(SlashCommandAction.Compact)
        assertThat(resolve(BuiltinSlashCommand.COMPACT, context = idle.copy(isGenerating = true)))
            .isEqualTo(SlashCommandAction.Reject(SlashCommandRejection.COMPACT_GENERATING))
        assertThat(resolve(BuiltinSlashCommand.COMPACT, context = idle.copy(isGenerating = true, isCompressing = true)))
            .isEqualTo(SlashCommandAction.Reject(SlashCommandRejection.COMPACT_RUNNING))
    }

    @Test fun `clear 只打开确认框，没有消息时拒绝`() {
        assertThat(resolve(BuiltinSlashCommand.CLEAR)).isEqualTo(SlashCommandAction.ConfirmClearHistory)
        assertThat(resolve(BuiltinSlashCommand.CLEAR, "现在", idle.copy(isGenerating = true))).isEqualTo(SlashCommandAction.ConfirmClearHistory)
        assertThat(resolve(BuiltinSlashCommand.CLEAR, context = SlashCommandContext()))
            .isEqualTo(SlashCommandAction.Reject(SlashCommandRejection.NOTHING_TO_CLEAR))
    }

    @Test fun `mode 识别中英文模式名，缺少参数打开工具设置，未知参数拒绝`() {
        mapOf(
            "auto" to ExecutionMode.AUTO, "AUTO" to ExecutionMode.AUTO, "自动" to ExecutionMode.AUTO,
            "semi" to ExecutionMode.SEMI, "半自动" to ExecutionMode.SEMI,
            "manual" to ExecutionMode.MANUAL, "手动" to ExecutionMode.MANUAL,
        ).forEach { (argument, mode) ->
            assertThat(resolve(BuiltinSlashCommand.MODE, argument)).isEqualTo(SlashCommandAction.SetExecutionMode(mode))
        }
        assertThat(resolve(BuiltinSlashCommand.MODE)).isEqualTo(SlashCommandAction.OpenToolSettings)
        assertThat(resolve(BuiltinSlashCommand.MODE, "fast"))
            .isEqualTo(SlashCommandAction.Reject(SlashCommandRejection.UNKNOWN_MODE, "fast"))
    }

    @Test fun `tools 缺少参数时切换当前状态，on 与 off 显式设置，未知参数拒绝`() {
        assertThat(resolve(BuiltinSlashCommand.TOOLS, context = idle.copy(toolsEnabled = true))).isEqualTo(SlashCommandAction.SetToolsEnabled(false))
        assertThat(resolve(BuiltinSlashCommand.TOOLS, context = idle.copy(toolsEnabled = false))).isEqualTo(SlashCommandAction.SetToolsEnabled(true))
        listOf("on", "ON", "开", "开启").forEach {
            assertThat(resolve(BuiltinSlashCommand.TOOLS, it, idle.copy(toolsEnabled = true))).isEqualTo(SlashCommandAction.SetToolsEnabled(true))
        }
        listOf("off", "关", "关闭").forEach {
            assertThat(resolve(BuiltinSlashCommand.TOOLS, it, idle.copy(toolsEnabled = false))).isEqualTo(SlashCommandAction.SetToolsEnabled(false))
        }
        assertThat(resolve(BuiltinSlashCommand.TOOLS, "maybe"))
            .isEqualTo(SlashCommandAction.Reject(SlashCommandRejection.UNKNOWN_TOGGLE, "maybe"))
    }

    @Test fun `export 缺少参数导出 Markdown，txt 导出纯文本，没有消息或格式未知时拒绝`() {
        assertThat(resolve(BuiltinSlashCommand.EXPORT)).isEqualTo(SlashCommandAction.Export(ExportSessionUseCase.Format.MARKDOWN))
        assertThat(resolve(BuiltinSlashCommand.EXPORT, "MD")).isEqualTo(SlashCommandAction.Export(ExportSessionUseCase.Format.MARKDOWN))
        assertThat(resolve(BuiltinSlashCommand.EXPORT, "txt")).isEqualTo(SlashCommandAction.Export(ExportSessionUseCase.Format.TXT))
        assertThat(resolve(BuiltinSlashCommand.EXPORT, "纯文本")).isEqualTo(SlashCommandAction.Export(ExportSessionUseCase.Format.TXT))
        assertThat(resolve(BuiltinSlashCommand.EXPORT, "pdf"))
            .isEqualTo(SlashCommandAction.Reject(SlashCommandRejection.UNKNOWN_EXPORT_FORMAT, "pdf"))
        assertThat(resolve(BuiltinSlashCommand.EXPORT, context = SlashCommandContext()))
            .isEqualTo(SlashCommandAction.Reject(SlashCommandRejection.NOTHING_TO_EXPORT))
    }
}
