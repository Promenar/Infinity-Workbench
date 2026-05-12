package com.promenar.nexara.data.remote.protocol

import com.promenar.nexara.data.local.inference.GenerateConfig
import com.promenar.nexara.data.local.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LocalProtocol(
    private val engine: LocalInferenceEngine,
    private val modelName: String = ""
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.Local

    private val promptTemplate: PromptTemplate by lazy {
        detectTemplate(modelName)
    }

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
        val formattedPrompt = buildString {
            append(promptTemplate.format(
                messages = request.messages,
                tools = request.tools
            ))
            
            // Append image markers from all messages if any
            request.messages.forEach { msg ->
                msg.imageUrls?.forEach { img ->
                    append("\n[Image: ${img.mimeType}]")
                }
            }
        }

        val genConfig = GenerateConfig(
            maxTokens = request.maxTokens ?: 512,
            temperature = (request.temperature ?: 0.7).toFloat(),
            topP = (request.topP ?: 0.9).toFloat()
        )

        engine.generate(formattedPrompt, genConfig).collect { tokenText ->
            emit(StreamChunk.TextDelta(content = tokenText))
        }

        emit(StreamChunk.Done)
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val formattedPrompt = buildString {
            append(promptTemplate.format(
                messages = request.messages,
                tools = request.tools
            ))
            
            // Append image markers
            request.messages.forEach { msg ->
                msg.imageUrls?.forEach { img ->
                    append("\n[Image: ${img.mimeType}]")
                }
            }
        }

        val genConfig = GenerateConfig(maxTokens = request.maxTokens ?: 512)
        val result = StringBuilder()

        engine.generate(formattedPrompt, genConfig).collect { tokenText ->
            result.append(tokenText)
        }

        return PromptResponse(content = result.toString())
    }

    override suspend fun listModels(): List<String> {
        return listOf(modelName.ifEmpty { "Local Model" })
    }

    override fun cancel() {
        // Kotlin Flow cancellation handles this automatically
    }

    private fun detectTemplate(name: String): PromptTemplate {
        val lower = name.lowercase()
        return when {
            "llama-3" in lower || "llama3" in lower -> Llama3Template()
            "qwen" in lower -> ChatMLTemplate()
            "mistral" in lower -> MistralTemplate()
            "gemma" in lower -> GemmaTemplate()
            else -> ChatMLTemplate()
        }
    }
}

sealed class PromptTemplate {
    abstract fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String
}

class ChatMLTemplate : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val sb = StringBuilder()
        for (msg in messages) {
            sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}

class Llama3Template : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val sb = StringBuilder("<|begin_of_text|>")
        for (msg in messages) {
            sb.append("<|start_header_id|>${msg.role}<|end_header_id|>\n\n")
            sb.append("${msg.content}<|eot_id|>")
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }
}

class MistralTemplate : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val sb = StringBuilder("<s>")
        val systemMsg = messages.find { it.role == "system" }
        val userMsgs = messages.filter { it.role == "user" }

        if (systemMsg != null) {
            sb.append("[INST] ${systemMsg.content}\n\n")
        } else {
            sb.append("[INST] ")
        }

        val lastUser = userMsgs.lastOrNull()
        if (lastUser != null) {
            sb.append("${lastUser.content} [/INST]")
        }

        return sb.toString()
    }
}

class GemmaTemplate : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val sb = StringBuilder("<bos>")
        for (msg in messages) {
            val role = if (msg.role == "assistant") "model" else msg.role
            sb.append("<start_of_turn>$role\n${msg.content}<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
