package com.promenar.nexara.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.recovery.LegacyAttachmentCodec
import com.promenar.nexara.data.local.db.recovery.LegacyAttachmentItem
import com.promenar.nexara.data.local.db.recovery.LegacyAttachmentOpenResult
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * 在真实 Android Compose 语义树上覆盖历史附件的用户可见入口。
 *
 * 这组测试不启动外部查看器；data URL 的检查只通过 FileProvider URI 读回副本。
 */
class LegacyAttachmentDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun legacyAttachmentsExposeImagePdfAudioAndVideoEntries() {
        val payload = listOf(
            attachmentJson(
                uri = "data:image/png;base64,QQ==",
                mimeType = "image/png",
                fileName = "legacy-image.png",
                type = "IMAGE",
            ),
            attachmentJson(
                uri = "data:application/pdf;base64,QQ==",
                mimeType = "application/pdf",
                fileName = "legacy-document.pdf",
                type = "DOCUMENT",
            ),
            attachmentJson(
                uri = "data:audio/mpeg;base64,QQ==",
                mimeType = "audio/mpeg",
                fileName = "legacy-audio.mp3",
                type = "AUDIO",
            ),
            attachmentJson(
                uri = "data:video/mp4;base64,QQ==",
                mimeType = "video/mp4",
                fileName = "legacy-video.mp4",
                type = "VIDEO",
            ),
        ).joinToString(prefix = "[", postfix = "]")

        composeRule.setContent {
            NexaraTheme {
                UserMessageBubble(
                    message = userMessage(legacyAttachmentsPayload = payload),
                    fontSize = 14,
                )
            }
        }

        waitForLegacyEntries(4)
        composeRule.onNodeWithTag(UiTags.CHAT_LEGACY_ATTACHMENTS, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag(
            UiTags.CHAT_LEGACY_ATTACHMENT_ENTRY,
            useUnmergedTree = true,
        ).assertCountEquals(4)

        listOf(
            "legacy-image.png",
            "legacy-document.pdf",
            "legacy-audio.mp3",
            "legacy-video.mp4",
            "image/png",
            "application/pdf",
            "audio/mpeg",
            "video/mp4",
        ).forEach { visibleText ->
            composeRule.onNodeWithText(visibleText, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun userImageWithSameUriIsShownOnceAndRemovedFromLegacyEntries() {
        val imageUri =
            "data:image/png;base64," +
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val payload = """[
            ${attachmentJson(imageUri, "image/png", "duplicate-image.png", "IMAGE")},
            ${attachmentJson("data:application/pdf;base64,QQ==", "application/pdf", "kept-document.pdf", "DOCUMENT")}
        ]""".trimIndent()

        composeRule.setContent {
            NexaraTheme {
                UserMessageBubble(
                    message = userMessage(
                        legacyAttachmentsPayload = payload,
                        userImages = listOf(imageUri),
                    ),
                    fontSize = 14,
                )
            }
        }

        waitForLegacyEntries(1)
        composeRule.onAllNodesWithTag(
            UiTags.CHAT_LEGACY_ATTACHMENT_ENTRY,
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onNodeWithText("duplicate-image.png", useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("kept-document.pdf", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.chat_cd_attached_image),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun damagedPayloadKeepsEntryVisibleWithExplicitUnreadablePrompt() {
        composeRule.setContent {
            NexaraTheme {
                UserMessageBubble(
                    message = userMessage(legacyAttachmentsPayload = "{not-json"),
                    fontSize = 14,
                )
            }
        }

        waitForLegacyEntries(1)
        composeRule.onNodeWithTag(UiTags.CHAT_LEGACY_ATTACHMENTS, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("历史附件", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.chat_legacy_attachment_unreadable),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun dataUrlPreviewCanBeReadBackWithoutLaunchingExternalIntent() {
        val bytes = "synthetic legacy attachment".toByteArray()
        val dataUri = "data:application/octet-stream;base64," +
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        val item = LegacyAttachmentItem(
            uri = dataUri,
            mimeType = "application/octet-stream",
            fileName = "synthetic.bin",
            sizeBytes = bytes.size.toLong(),
            type = "DOCUMENT",
        )

        val result = LegacyAttachmentCodec.open(context, item)
        assertThat(result).isInstanceOf(LegacyAttachmentOpenResult.Ready::class.java)
        val ready = result as LegacyAttachmentOpenResult.Ready
        val previewUri = requireNotNull(ready.intent.data)
        assertThat(previewUri.scheme).isEqualTo("content")

        val restored = context.contentResolver.openInputStream(previewUri)!!.use { it.readBytes() }
        assertThat(restored).isEqualTo(bytes)
    }

    @Test
    fun privateDatabaseAndSharedPreferencesPathsAreRejected() {
        val database = context.getDatabasePath("legacy-device-test.db")
        val preferences = File(
            context.applicationInfo.dataDir,
            "shared_prefs/legacy-device-test.xml",
        )
        listOf(database, preferences).forEach { file ->
            file.parentFile?.mkdirs()
            file.writeText("private")
        }

        try {
            listOf(database, preferences).forEach { file ->
                val result = LegacyAttachmentCodec.open(context, fileItem(file))
                assertThat(result).isInstanceOf(LegacyAttachmentOpenResult.Unreadable::class.java)
            }
        } finally {
            database.delete()
            preferences.delete()
        }
    }

    private fun waitForLegacyEntries(expected: Int) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(
                UiTags.CHAT_LEGACY_ATTACHMENT_ENTRY,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size == expected
        }
    }

    private fun userMessage(
        legacyAttachmentsPayload: String,
        userImages: List<String>? = null,
    ) = Message(
        id = "legacy-attachment-device-test",
        role = MessageRole.USER,
        content = "",
        legacyAttachmentsPayload = legacyAttachmentsPayload,
        userImages = userImages,
    )

    private fun attachmentJson(
        uri: String,
        mimeType: String,
        fileName: String,
        type: String,
    ): String = """{"uri":"$uri","mimeType":"$mimeType","fileName":"$fileName","sizeBytes":1,"type":"$type"}"""

    private fun fileItem(file: File) = LegacyAttachmentItem(
        uri = Uri.fromFile(file).toString(),
        mimeType = "text/plain",
        fileName = file.name,
        sizeBytes = file.length(),
        type = "DOCUMENT",
    )
}
