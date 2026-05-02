package com.promenar.nexara.native.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native 全局状态管理器
 */
object NexaraBridge {
    
    // 1. Agent 列表 (参数顺序校正: icon, color, isPinned)
    private val _agents = MutableStateFlow<List<Agent>>(listOf(
        Agent("super", "Nexara 超级助手", "原生加速版，支持实时流式响应", "", "gpt-4o", "✨", "#C0C1FF", true),
        Agent("coder", "编程专家", "精通全栈开发与架构设计", "", "gpt-4o", "💻", "#6366F1", false),
        Agent("writer", "创意写作", "文学创作、翻译与润色", "", "gpt-4o", "📝", "#10B981", false)
    ))
    val agents: StateFlow<List<Agent>> = _agents

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions

    val currentSessionId = MutableStateFlow<String?>(null)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    /**
     * 手动解析 Agent 列表
     */
    fun updateAgents(jsonString: String) {
        try {
            val arr = JSONArray(jsonString)
            if (arr.length() == 0) return
            val list = mutableListOf<Agent>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Agent(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    description = obj.optString("description"),
                    systemPrompt = obj.optString("systemPrompt", ""),
                    model = obj.optString("model", "gpt-4o"),
                    icon = obj.optString("icon", "✨"),
                    color = obj.optString("color", "#C0C1FF"),
                    isPinned = obj.optBoolean("isPinned", false)
                ))
            }
            _agents.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateSessions(jsonString: String) {
        try {
            val arr = JSONArray(jsonString)
            val list = mutableListOf<ChatSession>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(ChatSession(
                    id = obj.optString("id"),
                    agentId = obj.optString("agentId"),
                    title = obj.optString("title"),
                    lastMessage = obj.optString("lastMessage")
                ))
            }
            _sessions.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateMessages(sessionId: String, msgList: List<ChatMessage>) {
        currentSessionId.value = sessionId
        _messages.value = msgList
    }
}

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: String,
    val isStreaming: Boolean = false
)
