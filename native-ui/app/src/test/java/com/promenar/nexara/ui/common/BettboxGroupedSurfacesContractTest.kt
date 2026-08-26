package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 锁定 Bettbox 圆角分组面必须贯通设置深层页与两类首页业务列表。 */
class BettboxGroupedSurfacesContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/nexara/ui")

    private fun source(relativePath: String): String = Files.readAllBytes(
        sourceRoot.resolve(relativePath),
    ).toString(Charsets.UTF_8)

    @Test
    fun `共享分组面应使用无描边填色与二十 dp 圆角`() {
        val group = source("common/BettboxListGroup.kt")

        assertThat(group).contains("RoundedCornerShape(20.dp)")
        assertThat(group).contains("MaterialTheme.colorScheme.surfaceContainerLow")
        assertThat(group).doesNotContain("BorderStroke")
        assertThat(group).doesNotContain(".border(")
    }

    @Test
    fun `账户身份头部不应再显示账户分区标签`() {
        val settings = source("hub/UserSettingsHomeScreen.kt")

        val accountBlock = settings.substringAfter("UserSettingsHomeScreenContent(")
            .substringBefore("settings_section_general")
        assertThat(accountBlock).doesNotContain("settings_section_account")
    }

    @Test
    fun `设置二三级页面应显式消费 Bettbox 分组面而非裸列表`() {
        val deepPages = listOf(
            "settings/BackupSettingsScreen.kt",
            "settings/LocalModelsScreen.kt",
            "settings/SkillsScreen.kt",
            "settings/TokenUsageScreen.kt",
            "settings/ProviderFormScreen.kt",
            "settings/ProviderListScreen.kt",
            "settings/ProviderModelsScreen.kt",
            "settings/DefaultModelsScreen.kt",
            "settings/ThemeScreen.kt",
            "hub/AgentAdvancedRetrievalScreen.kt",
            "hub/AgentEditScreen.kt",
            "hub/AgentRagConfigScreen.kt",
            "rag/GlobalRagConfigScreen.kt",
            "rag/AdvancedRetrievalScreen.kt",
            "rag/RagAdvancedScreen.kt",
            "rag/RagDebugScreen.kt",
            "rag/KnowledgeGraphScreen.kt",
        )

        deepPages.forEach { path ->
            val page = source(path)
            assertWithMessage(path).that(
                page.contains("BettboxListGroup(") ||
                    page.contains("BettboxListGroup {") ||
                    page.contains("bettboxListGroup()") ||
                    page.contains("NexaraSettingsSection("),
            ).isTrue()
        }
    }

    @Test
    fun `Agent 与会话列表应位于统一 Bettbox 分组面内`() {
        val agentHub = source("hub/AgentHubScreen.kt")
        val sessions = source("hub/AgentSessionsScreen.kt")

        assertThat(agentHub).contains(".bettboxListGroup()")
        assertThat(sessions).contains(".bettboxListGroup()")
        assertThat(agentHub).contains("NexaraSpacing.ScreenHorizontal")
        assertThat(sessions).contains("NexaraSpacing.ScreenHorizontal")
    }

    @Test
    fun `记忆设置应按语义使用多个分组面且不使用装饰分割线`() {
        val globalRag = source("rag/GlobalRagConfigScreen.kt")
        val content = globalRag.substringAfter("internal fun GlobalRagConfigScreenContent(")

        assertThat(globalRag).doesNotContain("HorizontalDivider")
        assertThat(content.split("BettboxListGroup")).hasSize(5)
        assertThat(globalRag).doesNotContain(".verticalScroll(rememberScrollState())\n                .padding(contentPadding)\n                .bettboxListGroup()")
    }
}
