package com.promenar.nexara

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.promenar.nexara.data.local.inference.LocalInferenceEngine
import com.promenar.nexara.data.local.inference.SlotType
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.data.rag.ImageService
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.rag.MicroGraphExtractor
import com.promenar.nexara.data.rag.MicroGraphKgAdapter
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.RerankClient
import com.promenar.nexara.data.rag.RecursiveCharacterTextSplitter
import com.promenar.nexara.data.rag.VectorStore
import com.promenar.nexara.data.remote.protocol.ProtocolId
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.MessageRepository
import com.promenar.nexara.data.repository.SessionRepository
import com.promenar.nexara.ui.chat.manager.WebSearchContextProvider
import com.promenar.nexara.ui.chat.manager.WebSearchProvider
import com.promenar.nexara.ui.chat.manager.registry.DefaultSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.ModularSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.UserSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.ui.chat.manager.skills.CalculatorSkill
import com.promenar.nexara.ui.chat.manager.skills.CurrentTimeSkill
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.KgProvider
import com.promenar.nexara.ui.chat.manager.skills.WebSearchSkill
import com.promenar.nexara.ui.chat.manager.skills.WebSearchSearXNGSkill
import com.promenar.nexara.ui.chat.manager.skills.WebSearchTavilySkill
import com.promenar.nexara.ui.chat.manager.skills.WeatherSkill
import com.promenar.nexara.ui.chat.manager.skills.CreateToolSkill
import com.promenar.nexara.data.repository.SkillRepository
import com.promenar.nexara.data.repository.ISkillRepository
import com.promenar.nexara.util.LocaleHelper
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder

class NexaraApplication : Application(), SingletonImageLoader.Factory {
    val database: NexaraDatabase by lazy {
        Room.databaseBuilder(this, NexaraDatabase::class.java, "nexara.db")
            .addMigrations(NexaraDatabase.MIGRATION_4_5)
            .build()
    }

    val localInferenceEngine: LocalInferenceEngine by lazy {
        LocalInferenceEngine(this)
    }

    val sessionRepository: ISessionRepository by lazy {
        SessionRepository(database.sessionDao(), database.messageDao())
    }

    val messageRepository: IMessageRepository by lazy {
        MessageRepository(database.messageDao())
    }

    val chatStore: ChatStore by lazy { ChatStore() }
    
    val skillRepository: ISkillRepository by lazy {
        com.promenar.nexara.data.repository.SkillRepository(database.skillDao())
    }

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    val presetSkillRegistry: DefaultSkillRegistry by lazy {
        DefaultSkillRegistry().apply {
            register(CurrentTimeSkill())
            register(CalculatorSkill())
            register(WebSearchSkill(this@NexaraApplication, httpClient))
            register(WebSearchTavilySkill(this@NexaraApplication, httpClient))
            register(WebSearchSearXNGSkill(this@NexaraApplication, httpClient))
            register(WeatherSkill(httpClient))
            register(CreateToolSkill(database.skillDao()))
        }
    }

    val userSkillRegistry: UserSkillRegistry by lazy {
        UserSkillRegistry(skillRepository as SkillRepository)
    }

    val mcpSkillRegistry: McpSkillRegistry by lazy {
        McpSkillRegistry(skillRepository as SkillRepository, httpClient)
    }

    val skillRegistry: SkillRegistry by lazy {
        ModularSkillRegistry(
            listOf(presetSkillRegistry, userSkillRegistry, mcpSkillRegistry)
        )
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("nexara_provider", MODE_PRIVATE)
    }

    private lateinit var _llmProvider: MutableStateFlow<LlmProvider>
    val llmProvider: LlmProvider get() = _llmProvider.value
    val llmProviderFlow: StateFlow<LlmProvider> get() = _llmProvider

    var hapticEnabled: Boolean = true
        internal set

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }

    override fun attachBaseContext(base: Context) {
        val lang = LocaleHelper.getSavedLanguage(base)
        super.attachBaseContext(LocaleHelper.applyLanguage(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        _llmProvider = MutableStateFlow(buildProviderFromPrefs())

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val mainPath = localInferenceEngine.mainSlot.value.modelPath
                if (mainPath != null) {
                    prefs.edit().putString("last_local_model", mainPath).apply()
                }
            }
        })

        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        hapticEnabled = settingsPrefs.getBoolean("haptic_enabled", true)
        if (settingsPrefs.getBoolean("local_models_enabled", false) &&
            settingsPrefs.getBoolean("local_auto_load", false)) {
            val lastModel = prefs.getString("last_local_model", null)
            if (lastModel != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    localInferenceEngine.loadModel(SlotType.MAIN, lastModel)
                }
            }
        }
    }

    val embeddingClient: EmbeddingClient by lazy {
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("embedding_base_url", "") ?: ""
        val apiKey = prefs.getString("embedding_api_key", "") ?: ""
        val presetModel = settingsPrefs.getString("preset_embedding_model", "") ?: ""
        val model = prefs.getString("embedding_model", "")?.ifBlank { presetModel } ?: presetModel
        EmbeddingClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
    }

    val rerankClient: RerankClient by lazy {
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("embedding_base_url", "") ?: ""
        val apiKey = prefs.getString("embedding_api_key", "") ?: ""
        val presetModel = settingsPrefs.getString("preset_rerank_model", "") ?: ""
        RerankClient(baseUrl = baseUrl, apiKey = apiKey, modelId = presetModel)
    }

    val imageService: ImageService by lazy {
        ImageService(
            context = this,
            embeddingClient = embeddingClient,
            vectorStore = vectorStore,
            textSplitter = textSplitter
        )
    }

    val vectorStore: VectorStore by lazy {
        VectorStore(
            vectorDao = database.vectorDao(),
            kgNodeDao = database.kgNodeDao(),
            kgEdgeDao = database.kgEdgeDao(),
            documentDao = database.documentDao()
        )
    }

    val graphStore: GraphStore by lazy {
        GraphStore(
            kgNodeDao = database.kgNodeDao(),
            kgEdgeDao = database.kgEdgeDao()
        )
    }

    val keywordSearcher: KeywordSearcher by lazy {
        KeywordSearcher(vectorDao = database.vectorDao())
    }

    val memoryManager: MemoryManager by lazy {
        MemoryManager(
            vectorStore = vectorStore,
            keywordSearcher = keywordSearcher,
            graphStore = graphStore,
            embeddingClient = embeddingClient,
            ragConfig = RagConfiguration()
        )
    }

    val textSplitter: RecursiveCharacterTextSplitter by lazy {
        RecursiveCharacterTextSplitter(chunkSize = 800, chunkOverlap = 100)
    }

    val microGraphExtractor: MicroGraphExtractor by lazy {
        MicroGraphExtractor(
            protocol = llmProvider.protocol,
            graphStore = graphStore,
            jitCacheDao = database.kgJitCacheDao(),
            modelId = getSavedProviderConfig()?.model
        )
    }

    val kgProvider: KgProvider by lazy {
        MicroGraphKgAdapter(microGraphExtractor)
    }

    val webSearchContextProvider: WebSearchProvider by lazy {
        WebSearchContextProvider(this, httpClient)
    }

    val graphExtractor: com.promenar.nexara.data.rag.GraphExtractor by lazy {
        com.promenar.nexara.data.rag.GraphExtractor(
            protocol = llmProvider.protocol,
            graphStore = graphStore,
            modelId = getSavedProviderConfig()?.model
        )
    }

    val vectorizationQueue: com.promenar.nexara.data.rag.VectorizationQueue by lazy {
        com.promenar.nexara.data.rag.VectorizationQueue(
            vectorStore = vectorStore,
            embeddingClient = embeddingClient,
            graphExtractor = graphExtractor,
            documentDao = database.documentDao(),
            vectorDao = database.vectorDao(),
            vectorizationTaskDao = database.vectorizationTaskDao()
        )
    }

    val documentImporter: com.promenar.nexara.data.rag.DocumentImporter by lazy {
        com.promenar.nexara.data.rag.DocumentImporter(
            context = this,
            documentDao = database.documentDao(),
            vectorizationQueue = vectorizationQueue
        )
    }

    val defaultAgents: List<com.promenar.nexara.data.model.Agent> by lazy {
        listOf(
            com.promenar.nexara.data.model.Agent("super", "Nexara 超级助手", "原生加速版，支持实时流式响应", "", "", "✨", "#C0C1FF", null, true),
            com.promenar.nexara.data.model.Agent("coder", "编程专家", "精通全栈开发与架构设计", "", "", "💻", "#6366F1", null, false),
            com.promenar.nexara.data.model.Agent("writer", "创意写作", "文学创作、翻译与润色", "", "", "📝", "#10B981", null, false)
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
        _llmProvider.value = when (protocolId) {
            ProtocolId.LOCAL -> LlmProvider.local(localInferenceEngine, model)
            else -> LlmProvider.builder()
                .protocolId(protocolId)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .build()
        }
    }

    fun switchToLocalProvider(modelName: String = "") {
        _llmProvider.value = LlmProvider.local(localInferenceEngine, modelName)
    }

    fun getSavedProviderConfig(): ProviderConfig? {
        val protocolName = prefs.getString("protocol_id", null) ?: return null
        return ProviderConfig(
            protocolId = try { ProtocolId.valueOf(protocolName) } catch (_: Exception) { ProtocolId.OPENAI },
            baseUrl = prefs.getString("base_url", "") ?: "",
            apiKey = prefs.getString("api_key", "") ?: "",
            model = prefs.getString("model", "") ?: "",
            name = prefs.getString("provider_name", null)
        )
    }

    private fun buildProviderFromPrefs(): LlmProvider {
        val config = getSavedProviderConfig()
        return if (config != null) {
            when (config.protocolId) {
                ProtocolId.LOCAL -> LlmProvider.local(localInferenceEngine, config.model)
                else -> LlmProvider.builder()
                    .protocolId(config.protocolId)
                    .baseUrl(config.baseUrl)
                    .apiKey(config.apiKey)
                    .model(config.model)
                    .build()
            }
        } else {
            LlmProvider.builder()
                .protocolId(ProtocolId.OPENAI)
                .baseUrl("")
                .apiKey("")
                .model("")
                .build()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                CoroutineScope(Dispatchers.IO).launch {
                    localInferenceEngine.unloadModel(SlotType.RERANK)
                    localInferenceEngine.unloadModel(SlotType.EMBEDDING)
                }
            }
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
