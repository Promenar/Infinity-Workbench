package com.promenar.nexara.data.remote

import com.promenar.nexara.data.remote.parser.ToolCall

enum class MessageRole {
    SYSTEM, USER, ASSISTANT, TOOL;

    fun toApiString(): String = name.lowercase()

    companion object {
        fun fromString(value: String): MessageRole =
            entries.first { it.name.equals(value, ignoreCase = true) }
    }
}

data class FileAttachment(
    val uri: String,
    val mimeType: String,
    val name: String? = null
)

data class FormatterMessage(
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val thoughtSignature: String? = null,
    val files: List<FileAttachment>? = null
)

data class LlmChatMessage(
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val thoughtSignature: String? = null,
    val files: List<FileAttachment>? = null
)

interface MessageFormatter {
    fun formatHistory(messages: List<FormatterMessage>, contextWindow: Int? = null): List<LlmChatMessage>
    fun shouldStripHangingToolCalls(message: FormatterMessage): Boolean
    fun supportsReasoningInHistory(): Boolean
}

abstract class BaseMessageFormatter : MessageFormatter {

    abstract override fun formatHistory(
        messages: List<FormatterMessage>,
        contextWindow: Int?
    ): List<LlmChatMessage>

    override fun shouldStripHangingToolCalls(message: FormatterMessage): Boolean = false

    override fun supportsReasoningInHistory(): Boolean = false

    protected fun convertMessage(message: FormatterMessage): LlmChatMessage =
        LlmChatMessage(
            role = message.role,
            content = message.content,
            reasoning = message.reasoning,
            name = message.name,
            toolCallId = message.toolCallId,
            toolCalls = message.toolCalls,
            thoughtSignature = message.thoughtSignature,
            files = message.files
        )
}

/**
 * OpenAI Formatter
 */
class OpenAIFormatter : BaseMessageFormatter() {
    override fun formatHistory(messages: List<FormatterMessage>, contextWindow: Int?): List<LlmChatMessage> {
        return messages.map { msg ->
            convertMessage(msg).copy(reasoning = null) // OpenAI不支持reasoning回传
        }
    }

    override fun shouldStripHangingToolCalls(message: FormatterMessage): Boolean = true

    override fun supportsReasoningInHistory(): Boolean = false
}

/**
 * DeepSeek Formatter
 */
class DeepSeekFormatter(private val modelName: String = "") : BaseMessageFormatter() {
    override fun formatHistory(messages: List<FormatterMessage>, contextWindow: Int?): List<LlmChatMessage> {
        return messages.map { msg ->
            val chatMsg = convertMessage(msg)
            if (chatMsg.role == MessageRole.SYSTEM) {
                chatMsg.copy(content = enhanceSystemPrompt(chatMsg.content))
            } else {
                chatMsg
            }
        }
    }

    override fun shouldStripHangingToolCalls(message: FormatterMessage): Boolean = true

    override fun supportsReasoningInHistory(): Boolean = true

    private fun enhanceSystemPrompt(originalPrompt: String): String {
        return originalPrompt + """

## 🔧 工具调用规范

在调用工具时，请务必提供简短的文本说明您正在执行的操作。

示例：
- 调用 manage_task 创建任务时，输出："已创建任务计划，准备执行"
- 调用 web_search 时，输出："正在搜索相关信息..."
- 调用 query_vector_db 时，输出："正在查询知识库..."
- 调用 toast 时，输出："正在弹出通知"

## ⚡ 任务执行流程（重要）

当用户要求"规划任务"时，正确的流程是：

1. 首先调用 manage_task({ action: "create", steps: [...] }) 创建任务
2. **然后立即执行第一个步骤的实际工具**（如 web_search、query_vector_db 等）
3. 执行完毕后，调用 manage_task({ action: "update", steps: [{"id": "...", "status": "completed"}] })
4. 继续执行下一个步骤，重复步骤2-3
5. 全部完成后，调用 manage_task({ action: "complete" })

**关键规则**：
- ❌ 错误：创建任务后等待，或在下一轮重复创建任务
- ✅ 正确：创建任务后，立即调用第一个步骤对应的工具（如search/query等）

**示例（完整流程）**：
用户："规划任务：查询玄鸟号，总结，弹出toast"

第1轮：create任务
  → manage_task(action="create", steps=[...])

第2轮：执行第一步
  → query_vector_db(query="玄鸟号")
  → manage_task(action="update", steps=[{"id":"search", "status":"completed"}])

第3轮：执行第二步
  → (总结内容)
  → manage_task(action="update", steps=[{"id":"summarize", "status":"completed"}])

第4轮：执行第三步
  → toast(message="...")
  → manage_task(action="update", steps=[{"id":"toast", "status":"completed"}])

第5轮：完成
  → manage_task(action="complete")
"""
    }
}

/**
 * GLM (智谱AI) Formatter
 */
class GLMFormatter : BaseMessageFormatter() {
    override fun formatHistory(messages: List<FormatterMessage>, contextWindow: Int?): List<LlmChatMessage> {
        return messages.map { msg ->
            convertMessage(msg).copy(reasoning = null) // GLM不支持reasoning回传
        }
    }

    override fun shouldStripHangingToolCalls(message: FormatterMessage): Boolean = true

    override fun supportsReasoningInHistory(): Boolean = false
}

/**
 * Moonshot (KIMI) Formatter
 */
class MoonshotFormatter : BaseMessageFormatter() {
    override fun formatHistory(messages: List<FormatterMessage>, contextWindow: Int?): List<LlmChatMessage> {
        return messages.map { msg ->
            convertMessage(msg).copy(reasoning = null) // KIMI基本不支持reasoning回传
        }
    }

    override fun shouldStripHangingToolCalls(message: FormatterMessage): Boolean = true

    override fun supportsReasoningInHistory(): Boolean = false
}

/**
 * Gemini Formatter
 */
class GeminiFormatter(private val modelName: String = "") : BaseMessageFormatter() {
    override fun formatHistory(messages: List<FormatterMessage>, contextWindow: Int?): List<LlmChatMessage> {
        return messages.map { msg ->
            val chatMsg = convertMessage(msg)
            if (chatMsg.role == MessageRole.SYSTEM) {
                chatMsg.copy(content = enhanceSystemPrompt(chatMsg.content))
            } else {
                chatMsg
            }
        }
    }

    override fun shouldStripHangingToolCalls(message: FormatterMessage): Boolean = false

    override fun supportsReasoningInHistory(): Boolean = false

    private fun enhanceSystemPrompt(originalPrompt: String): String {
        if (!modelName.lowercase().contains("pro")) {
            return originalPrompt
        }

        return originalPrompt + """

## 📋 manage_task 工具使用规范

创建任务时，步骤**必须**使用详细对象格式，包含 id、description、status 三个字段：

✅ 正确示例：
{
  "action": "create",
  "title": "任务标题",
  "steps": [
    {
      "id": "search_kb",
      "description": "查询全局知识库关于XX的信息",
      "status": "pending"
    },
    {
      "id": "summarize",
      "description": "总结信息并弹出Toast通知",
      "status": "pending"
    }
  ]
}

❌ 错误示例（避免使用简化格式）：
{
  "steps": ["动作1", "动作2"]
}
{
  "steps": ["查询知识库", "弹出通知"]
}

### 步骤命名规则
- id: 使用英文蛇形命名，如 search_kb、summarize、show_toast
- description: 详细描述具体操作，避免"动作1"、"动作2"等抽象名称
- status: 初始创建时统一使用 "pending"

### 步骤更新
完成每个步骤后，使用 update action 更新状态：
{
  "action": "update",
  "steps": [{"id": "search_kb", "status": "completed"}]
}
"""
    }
}
