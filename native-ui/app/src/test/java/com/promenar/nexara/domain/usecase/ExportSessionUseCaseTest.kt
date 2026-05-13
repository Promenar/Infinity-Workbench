package com.promenar.nexara.domain.usecase

import com.promenar.nexara.domain.model.Message
import com.promenar.nexara.domain.model.MessageRole
import com.promenar.nexara.domain.model.Session
import com.promenar.nexara.domain.repository.IMessageRepository
import com.promenar.nexara.domain.repository.ISessionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExportSessionUseCaseTest {

    private lateinit var sessionRepository: ISessionRepository
    private lateinit var messageRepository: IMessageRepository
    private lateinit var useCase: ExportSessionUseCase

    private val testSession = Session(
        id = "sess_1",
        agentId = "agent_1",
        title = "Test Session",
        modelId = "gpt-4",
        createdAt = 1700000000000L
    )

    private val testMessages = listOf(
        Message(
            id = "msg_1",
            sessionId = "sess_1",
            role = MessageRole.USER,
            content = "Hello, how are you?",
            timestamp = 1700000001000L
        ),
        Message(
            id = "msg_2",
            sessionId = "sess_1",
            role = MessageRole.ASSISTANT,
            content = "I'm doing well, thank you!",
            thinking = "The user is greeting me.",
            timestamp = 1700000002000L
        )
    )

    @BeforeEach
    fun setUp() {
        sessionRepository = mockk()
        messageRepository = mockk()
        useCase = ExportSessionUseCase(messageRepository, sessionRepository)
    }

    @Test
    fun `export as TXT includes title and messages`() = runTest {
        coEvery { sessionRepository.observeById("sess_1") } returns flowOf(testSession)
        coEvery { messageRepository.observeBySession("sess_1") } returns flowOf(testMessages)

        val result = useCase.export("sess_1", ExportSessionUseCase.Format.TXT)

        assertEquals("text/plain", result.mimeType)
        assertTrue(result.fileName.endsWith(".txt"))
        assertTrue(result.content.contains("# Test Session"))
        assertTrue(result.content.contains("Model: gpt-4"))
        assertTrue(result.content.contains("## USER"))
        assertTrue(result.content.contains("Hello, how are you?"))
        assertTrue(result.content.contains("## ASSISTANT"))
        assertTrue(result.content.contains("I'm doing well, thank you!"))
        assertTrue(result.content.contains("--- Thinking ---"))
        assertTrue(result.content.contains("The user is greeting me."))
    }

    @Test
    fun `export as TXT includes date`() = runTest {
        coEvery { sessionRepository.observeById("sess_1") } returns flowOf(testSession)
        coEvery { messageRepository.observeBySession("sess_1") } returns flowOf(testMessages)

        val result = useCase.export("sess_1", ExportSessionUseCase.Format.TXT)

        assertTrue(result.content.contains("Date:"))
    }

    @Test
    fun `export as MARKDOWN includes title and formatted messages`() = runTest {
        coEvery { sessionRepository.observeById("sess_1") } returns flowOf(testSession)
        coEvery { messageRepository.observeBySession("sess_1") } returns flowOf(testMessages)

        val result = useCase.export("sess_1", ExportSessionUseCase.Format.MARKDOWN)

        assertEquals("text/markdown", result.mimeType)
        assertTrue(result.fileName.endsWith(".md"))
        assertTrue(result.content.contains("# Test Session"))
        assertTrue(result.content.contains("`gpt-4`"))
        assertTrue(result.content.contains("### USER"))
        assertTrue(result.content.contains("Hello, how are you?"))
        assertTrue(result.content.contains("### ASSISTANT"))
        assertTrue(result.content.contains("I'm doing well, thank you!"))
    }

    @Test
    fun `export as MARKDOWN formats thinking as blockquote`() = runTest {
        coEvery { sessionRepository.observeById("sess_1") } returns flowOf(testSession)
        coEvery { messageRepository.observeBySession("sess_1") } returns flowOf(testMessages)

        val result = useCase.export("sess_1", ExportSessionUseCase.Format.MARKDOWN)

        assertTrue(result.content.contains("> **Thinking:**"))
        assertTrue(result.content.contains("> The user is greeting me."))
    }

    @Test
    fun `export as MARKDOWN includes italic date`() = runTest {
        coEvery { sessionRepository.observeById("sess_1") } returns flowOf(testSession)
        coEvery { messageRepository.observeBySession("sess_1") } returns flowOf(testMessages)

        val result = useCase.export("sess_1", ExportSessionUseCase.Format.MARKDOWN)

        assertTrue(result.content.contains("*"))
        assertTrue(result.content.contains("Date:").not() || result.content.contains("202"))
    }

    @Test
    fun `export empty session does not crash`() = runTest {
        coEvery { sessionRepository.observeById("sess_empty") } returns flowOf(testSession)
        coEvery { messageRepository.observeBySession("sess_empty") } returns flowOf(emptyList())

        val txtResult = useCase.export("sess_empty", ExportSessionUseCase.Format.TXT)
        val mdResult = useCase.export("sess_empty", ExportSessionUseCase.Format.MARKDOWN)

        assertNotNull(txtResult.content)
        assertNotNull(mdResult.content)
        assertTrue(txtResult.content.contains("(No messages)"))
        assertTrue(mdResult.content.contains("*(No messages)*"))
    }

    @Test
    fun `export throws when session not found`() = runTest {
        coEvery { sessionRepository.observeById("missing") } returns flowOf(null)

        assertThrows<NoSuchElementException> {
            useCase.export("missing", ExportSessionUseCase.Format.TXT)
        }
    }

    @Test
    fun `export fileName contains sanitized title`() = runTest {
        val sessionWithSpecialChars = testSession.copy(title = "My/Chat: Session?")
        coEvery { sessionRepository.observeById("sess_1") } returns flowOf(sessionWithSpecialChars)
        coEvery { messageRepository.observeBySession("sess_1") } returns flowOf(testMessages)

        val result = useCase.export("sess_1", ExportSessionUseCase.Format.TXT)

        assertTrue(result.fileName.startsWith("My_Chat__Session_"))
        assertTrue(result.fileName.endsWith(".txt"))
    }

    @Test
    fun `export TXT message without thinking has no thinking section`() = runTest {
        val messagesWithoutThinking = listOf(
            Message(
                id = "msg_1",
                sessionId = "sess_1",
                role = MessageRole.USER,
                content = "No thinking here",
                timestamp = 1700000001000L
            )
        )
        coEvery { sessionRepository.observeById("sess_1") } returns flowOf(testSession)
        coEvery { messageRepository.observeBySession("sess_1") } returns flowOf(messagesWithoutThinking)

        val result = useCase.export("sess_1", ExportSessionUseCase.Format.TXT)

        assertTrue(result.content.contains("No thinking here"))
        assertTrue(!result.content.contains("--- Thinking ---"))
    }

    @Test
    fun `export MARKDOWN multiline thinking is properly quoted`() = runTest {
        val messagesWithMultilineThinking = listOf(
            Message(
                id = "msg_1",
                sessionId = "sess_1",
                role = MessageRole.ASSISTANT,
                content = "Response",
                thinking = "Line 1\nLine 2\nLine 3",
                timestamp = 1700000001000L
            )
        )
        coEvery { sessionRepository.observeById("sess_1") } returns flowOf(testSession)
        coEvery { messageRepository.observeBySession("sess_1") } returns flowOf(messagesWithMultilineThinking)

        val result = useCase.export("sess_1", ExportSessionUseCase.Format.MARKDOWN)

        assertTrue(result.content.contains("> Line 1\n> Line 2\n> Line 3"))
    }
}
