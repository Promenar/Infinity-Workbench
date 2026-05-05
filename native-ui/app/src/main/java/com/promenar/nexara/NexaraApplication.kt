package com.promenar.nexara

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.RecursiveCharacterTextSplitter
import com.promenar.nexara.data.rag.VectorStore
import com.promenar.nexara.data.remote.protocol.ProtocolId
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.MessageRepository
import com.promenar.nexara.data.repository.SessionRepository
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.util.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NexaraApplication : Application() {
    val database: NexaraDatabase by lazy {
        Room.databaseBuilder(this, NexaraDatabase::class.java, "nexara.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val sessionRepository: ISessionRepository by lazy {
        SessionRepository(database.sessionDao(), database.messageDao())
    }

    val messageRepository: IMessageRepository by lazy {
        MessageRepository(database.messageDao())
    }

    val chatStore: ChatStore by lazy { ChatStore() }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("nexara_provider", MODE_PRIVATE)
    }

    private lateinit var _llmProvider: MutableStateFlow<LlmProvider>
    val llmProvider: LlmProvider get() = _llmProvider.value
    val llmProviderFlow: StateFlow<LlmProvider> get() = _llmProvider

    override fun attachBaseContext(base: Context) {
        val lang = LocaleHelper.getSavedLanguage(base)
        super.attachBaseContext(LocaleHelper.applyLanguage(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        _llmProvider = MutableStateFlow(buildProviderFromPrefs())
    }

    val embeddingClient: EmbeddingClient by lazy {
        val baseUrl = prefs.getString("embedding_base_url", "https://api.siliconflow.cn") ?: "https://api.siliconflow.cn"
        val apiKey = prefs.getString("embedding_api_key", "") ?: ""
        val model = prefs.getString("embedding_model", "BAAI/bge-m3") ?: "BAAI/bge-m3"
        EmbeddingClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
    }

    val vectorStore: VectorStore by lazy {
        VectorStore(
            vectorDao = database.vectorDao(),
            kgNodeDao = database.kgNodeDao(),
            kgEdgeDao = database.kgEdgeDao(),
            documentDao = database.documentDao()
        )
    }

    val textSplitter: RecursiveCharacterTextSplitter by lazy {
        RecursiveCharacterTextSplitter(chunkSize = 800, chunkOverlap = 100)
    }

    val defaultAgents: List<com.promenar.nexara.data.model.Agent> by lazy {
        listOf(
            com.promenar.nexara.data.model.Agent("super", "Nexara 超级助手", "原生加速版，支持实时流式响应", "", "gpt-4o", "✨", "#C0C1FF", true),
            com.promenar.nexara.data.model.Agent("coder", "编程专家", "精通全栈开发与架构设计", "", "gpt-4o", "💻", "#6366F1", false),
            com.promenar.nexara.data.model.Agent("writer", "创意写作", "文学创作、翻译与润色", "", "gpt-4o", "📝", "#10B981", false)
        )
    }

    fun updateProvider(
        protocolId: ProtocolId,
        baseUrl: String,
        apiKey: String,
        model: String,
        name: String? = null
    ) {
        prefs.edit()
            .putString("protocol_id", protocolId.name)
            .putString("base_url", baseUrl)
            .putString("api_key", apiKey)
            .putString("model", model)
            .apply()
        if (name != null) {
            prefs.edit().putString("provider_name", name).apply()
        }
        _llmProvider.value = LlmProvider.builder()
            .protocolId(protocolId)
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .model(model)
            .build()
    }

    fun getSavedProviderConfig(): ProviderConfig? {
        val protocolName = prefs.getString("protocol_id", null) ?: return null
        return ProviderConfig(
            protocolId = try { ProtocolId.valueOf(protocolName) } catch (_: Exception) { ProtocolId.OPENAI },
            baseUrl = prefs.getString("base_url", "") ?: "",
            apiKey = prefs.getString("api_key", "") ?: "",
            model = prefs.getString("model", "gpt-4o") ?: "gpt-4o",
            name = prefs.getString("provider_name", null)
        )
    }

    private fun buildProviderFromPrefs(): LlmProvider {
        val config = getSavedProviderConfig()
        return if (config != null) {
            LlmProvider.builder()
                .protocolId(config.protocolId)
                .baseUrl(config.baseUrl)
                .apiKey(config.apiKey)
                .model(config.model)
                .build()
        } else {
            LlmProvider.builder()
                .protocolId(ProtocolId.OPENAI)
                .baseUrl("")
                .apiKey("")
                .model("gpt-4o")
                .build()
        }
    }

    data class ProviderConfig(
        val protocolId: ProtocolId,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val name: String?
    )
}
