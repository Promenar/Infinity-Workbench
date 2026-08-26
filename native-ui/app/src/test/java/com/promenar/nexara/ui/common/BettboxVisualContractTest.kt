package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class BettboxVisualContractTest {
    private fun common(fileName: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/ui/common/$fileName"),
    ).toString(Charsets.UTF_8)

    private fun ui(fileName: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/ui/$fileName"),
    ).toString(Charsets.UTF_8)

    @Test
    fun `设置分组应使用 Bettbox 式 surfaceContainer 圆角连续列表`() {
        val section = common("NexaraSettingsSection.kt")

        assertThat(section).contains("MaterialTheme.colorScheme.surfaceContainer")
        assertThat(section).contains("RoundedCornerShape(20.dp)")
        assertThat(section).contains("groupHorizontalPadding")
        assertThat(section).contains("NexaraSpacing.Small")
    }

    @Test
    fun `设置行应使用标准 ListItem 与二十四 dp 前导图标`() {
        val item = common("NexaraSettingsItem.kt")

        assertThat(item).contains("ListItem(")
        assertThat(item).contains("modifier = Modifier.size(24.dp)")
    }

    @Test
    fun `底栏应使用 Bettbox 式静态选中容器而非水滴形变`() {
        val navigation = ui("MainTabScaffold.kt")

        assertThat(navigation).contains("RoundedCornerShape(36.dp)")
        assertThat(navigation).contains("MaterialTheme.colorScheme.surfaceContainer")
        assertThat(navigation).contains("MaterialTheme.colorScheme.secondaryContainer")
        assertThat(navigation).contains("tween(durationMillis = 250)")
        assertThat(navigation).doesNotContain("fluidNavigationIndicatorScale")
    }
}
