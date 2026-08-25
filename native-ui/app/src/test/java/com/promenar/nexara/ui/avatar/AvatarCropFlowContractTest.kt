package com.promenar.nexara.ui.avatar

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class AvatarCropFlowContractTest {
    private fun source(path: String): String =
        Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/$path"))
            .toString(Charsets.UTF_8)

    private val agentEdit = source("ui/hub/AgentEditScreen.kt")
    private val userSettings = source("ui/hub/UserSettingsHomeScreen.kt")
    private val agentAvatar = source("ui/common/AgentAvatar.kt")
    private val cropActivity = source("ui/avatar/AvatarCropActivity.kt")

    @Test
    fun `agent and user avatar imports share the same crop launcher`() {
        assertThat(agentEdit).contains("rememberAvatarCropLauncher(")
        assertThat(userSettings).contains("rememberAvatarCropLauncher(")
        assertThat(agentEdit).doesNotContain("ActivityResultContracts.GetContent()")
        assertThat(userSettings).doesNotContain("UCrop.of(")
    }

    @Test
    fun `custom agent image always fills the circular avatar`() {
        val customImageBranch = agentAvatar.substringAfter("if (customImageUri != null)")
            .substringBefore("} else if (icon != null)")

        assertThat(customImageBranch).contains("contentScale = ContentScale.Crop")
    }

    @Test
    fun `crop activity exposes an explicit text completion action`() {
        assertThat(cropActivity).contains("R.string.avatar_crop_use_photo")
        assertThat(cropActivity).contains("AppCompatTextView(this)")
        assertThat(cropActivity).contains("setOnClickListener { cropAndSaveImage() }")
        assertThat(cropActivity).contains("cropAction?.isVisible = false")
    }
}
