package com.promenar.nexara.data.model.catalog

import android.app.Application
import com.promenar.nexara.utils.NexaraLogger
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BundledModelCatalog private constructor(records: Collection<ModelMetadataRecord>) {
    val records: List<ModelMetadataRecord> = Collections.unmodifiableList(
        records.map(ModelMetadataRecord::immutableCopy),
    )

    private val exactCandidatesById: Map<String, List<ModelMetadataRecord>> = Collections.unmodifiableMap(
        buildMap<String, MutableList<ModelMetadataRecord>> {
            records.forEach { record ->
                addCandidate(record.canonicalModelId, record)
                record.exactAliases.forEach { alias -> addCandidate(alias, record) }
            }
        }.mapValues { (_, candidates) ->
            Collections.unmodifiableList(candidates.distinct().map(ModelMetadataRecord::immutableCopy))
        },
    )

    fun findExact(remoteModelId: String): ModelMetadataRecord? =
        exactCandidatesById[normalizeRemoteModelId(remoteModelId)]
            ?.singleOrNull()
            ?.immutableCopy()

    private fun MutableMap<String, MutableList<ModelMetadataRecord>>.addCandidate(
        alias: String,
        record: ModelMetadataRecord,
    ) {
        getOrPut(normalizeRemoteModelId(alias)) { mutableListOf() }.add(record)
    }

    companion object {
        private const val ASSET_PATH = "model-catalog/models-dev.normalized.json"

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun load(application: Application): BundledModelCatalog =
            fromJson(application.assets.open(ASSET_PATH).bufferedReader().use { it.readText() })

        internal fun fromJson(source: String): BundledModelCatalog = BundledModelCatalog(
            json.decodeFromString<List<BundledCatalogRecord>>(source).map(BundledCatalogRecord::toRecord),
        )

        internal fun fromRecords(records: Collection<ModelMetadataRecord>): BundledModelCatalog =
            BundledModelCatalog(records)
    }
}

object ModelCatalogRuntime {
    private val initializationLock = Any()
    private val testStateLock = ReentrantLock()

    @Volatile
    private var initialized = false

    @Volatile
    var resolver: ModelMetadataResolver = ModelMetadataResolver()
        private set

    fun initialize(application: Application) {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return
            resolver = runCatching {
                resolverFor(BundledModelCatalog.load(application))
            }.getOrElse { error ->
                NexaraLogger.logError("model_catalog_load_failed", error)
                resolverFor(null)
            }
            initialized = true
        }
    }

    internal fun resolverFor(catalog: BundledModelCatalog?): ModelMetadataResolver = ModelMetadataResolver(
        catalog?.records.orEmpty() + NEXARA_EXACT_MODEL_OVERRIDES.values,
    )

    internal fun <T> withTestResolver(
        replacement: ModelMetadataResolver,
        block: () -> T,
    ): T = withTestState(replacement = replacement, initialized = true, block = block)

    internal fun <T> withUninitializedTestRuntime(block: () -> T): T = withTestState(
        replacement = ModelMetadataResolver(),
        initialized = false,
        block = block,
    )

    private fun <T> withTestState(
        replacement: ModelMetadataResolver,
        initialized: Boolean,
        block: () -> T,
    ): T {
        testStateLock.lock()
        val previous = synchronized(initializationLock) {
            RuntimeState(resolver = resolver, initialized = this.initialized).also {
                resolver = replacement
                this.initialized = initialized
            }
        }
        return try {
            block()
        } finally {
            synchronized(initializationLock) {
                resolver = previous.resolver
                this.initialized = previous.initialized
            }
            testStateLock.unlock()
        }
    }

    private data class RuntimeState(
        val resolver: ModelMetadataResolver,
        val initialized: Boolean,
    )
}

@Serializable
private data class BundledCatalogRecord(
    val canonicalModelId: String,
    val displayName: String,
    val family: String? = null,
    val reasoning: Boolean? = null,
    val tool_call: Boolean? = null,
    val structured_output: Boolean? = null,
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val knowledge: String? = null,
    val modalities: BundledModalities? = null,
) {
    fun toRecord(): ModelMetadataRecord {
        val shortId = canonicalModelId.substringAfter('/', canonicalModelId)
        val outputModalities = modalities?.output.orEmpty()
        return ModelMetadataRecord(
            canonicalModelId = canonicalModelId,
            exactAliases = setOf(canonicalModelId, shortId),
            displayName = displayName,
            familyName = family,
            workload = outputModalities.toWorkload(),
            capabilities = buildMap {
                put(ModelCapability.REASONING, reasoning.toSupportState())
                put(ModelCapability.TOOL_CALLING, tool_call.toSupportState())
                put(ModelCapability.STRUCTURED_OUTPUT, structured_output.toSupportState())
                modalities?.input.orEmpty().forEach { modality ->
                    modality.inputCapability()?.let { put(it, SupportState.SUPPORTED) }
                }
                modalities?.output.orEmpty().forEach { modality ->
                    modality.outputCapability()?.let { put(it, SupportState.SUPPORTED) }
                }
            },
            contextTokens = contextTokens,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            knowledgeCutoff = knowledge,
            source = MetadataSource.MODELS_DEV,
        )
    }
}

@Serializable
private data class BundledModalities(
    val input: List<String> = emptyList(),
    val output: List<String> = emptyList(),
)

private fun Boolean?.toSupportState(): SupportState = when (this) {
    true -> SupportState.SUPPORTED
    false -> SupportState.UNSUPPORTED
    null -> SupportState.UNKNOWN
}

private fun String.inputCapability(): ModelCapability? = when (lowercase()) {
    "image" -> ModelCapability.VISION_INPUT
    "audio" -> ModelCapability.AUDIO_INPUT
    "video" -> ModelCapability.VIDEO_INPUT
    else -> null
}

private fun String.outputCapability(): ModelCapability? = when (lowercase()) {
    "audio" -> ModelCapability.AUDIO_OUTPUT
    else -> null
}

private fun Collection<String>.toWorkload(): ModelWorkload {
    val normalized = map(String::lowercase).toSet()
    return when {
        "text" in normalized -> ModelWorkload.GENERATIVE_TEXT
        normalized == setOf("image") -> ModelWorkload.IMAGE_GENERATION
        normalized == setOf("audio") -> ModelWorkload.AUDIO
        normalized == setOf("video") -> ModelWorkload.VIDEO
        else -> ModelWorkload.UNKNOWN
    }
}

private fun ModelMetadataRecord.immutableCopy(): ModelMetadataRecord = copy(
    exactAliases = Collections.unmodifiableSet(exactAliases.toSet()),
    capabilities = Collections.unmodifiableMap(capabilities.toMap()),
)
