package com.promenar.nexara.data.model.catalog

import java.util.Collections

class ModelMetadataResolver private constructor(
    records: Collection<ModelMetadataRecord>,
    catalogAliases: Map<String, ModelMetadataRecord>,
) {
    constructor() : this(
        records = NEXARA_EXACT_MODEL_OVERRIDES.values.toSet(),
        catalogAliases = NEXARA_EXACT_MODEL_OVERRIDES,
    )

    internal constructor(exactRecords: Collection<ModelMetadataRecord>) : this(
        records = exactRecords,
        catalogAliases = emptyMap(),
    )

    private val recordSnapshots = records.map(ModelMetadataRecord::defensiveCopy)
    private val catalogAliasSnapshots = catalogAliases.mapValues { (_, record) -> record.defensiveCopy() }

    private val exactCandidatesById: Map<String, List<ModelMetadataRecord>> = buildMap {
        catalogAliasSnapshots.forEach { (alias, record) -> addCandidate(alias, record) }
        recordSnapshots.forEach { record ->
            addCandidate(record.canonicalModelId, record)
            record.exactAliases.forEach { alias -> addCandidate(alias, record) }
        }
    }.mapValues { (_, candidates) -> candidates.distinct() }

    fun resolve(
        remoteModelId: String,
        providerId: String? = null,
        providerMetadata: ModelMetadataOverride? = null,
        userOverride: ModelMetadataOverride? = null,
    ): ResolvedModelMetadata {
        val normalizedId = normalizeRemoteModelId(remoteModelId)
        val remoteId = remoteModelId.substringAfter("::", remoteModelId)
        val allCandidates = exactCandidatesById[normalizedId].orEmpty()
        val exactRecords = exactRecordsFor(allCandidates)
        val diagnostics = if (isTopPriorityAmbiguous(allCandidates)) {
            setOf(AMBIGUOUS_EXACT_MATCH)
        } else {
            emptySet()
        }

        val displayName = resolveField(
            catalog = exactField(exactRecords) { it.displayName },
            providerValue = providerMetadata?.displayName,
            userValue = userOverride?.displayName,
            fallback = remoteId,
        )
        val workload = resolveField(
            catalog = exactField(exactRecords) { it.workload },
            providerValue = providerMetadata?.workload,
            userValue = userOverride?.workload,
            fallback = ModelWorkload.UNKNOWN,
        )
        val contextTokens = resolveNullableField(
            catalog = exactField(exactRecords) { it.contextTokens },
            providerValue = providerMetadata?.contextTokens,
            userValue = userOverride?.contextTokens,
        )
        val outputTokens = resolveNullableField(
            catalog = exactField(exactRecords) { it.outputTokens },
            providerValue = providerMetadata?.outputTokens,
            userValue = userOverride?.outputTokens,
        )
        val capabilityResolution = ModelCapability.values().associateWith { capability ->
            resolveCapability(capability, exactRecords, providerMetadata, userOverride)
        }
        val exactFamily = exactField(exactRecords) { it.familyName }
        val familyName = exactFamily.value ?: familyNameFor(normalizedId)
        val familySource = when {
            exactFamily.value != null -> exactFamily.source
            familyName != null -> MetadataSource.FAMILY
            else -> MetadataSource.FALLBACK
        }
        val canonical = exactRecords.firstOrNull()
        val inputTokens = exactField(exactRecords) { it.inputTokens }
        val knowledgeCutoff = exactField(exactRecords) { it.knowledgeCutoff }

        return ResolvedModelMetadata(
            remoteModelId = remoteId,
            canonicalModelId = canonical?.canonicalModelId,
            displayName = displayName.value,
            familyName = familyName,
            workload = workload.value,
            capabilities = immutableMap(capabilityResolution.mapValues { it.value.value }),
            contextTokens = contextTokens.value,
            inputTokens = inputTokens.value,
            outputTokens = outputTokens.value,
            knowledgeCutoff = knowledgeCutoff.value,
            sourceByField = immutableMap(buildMap {
                put("canonicalModelId", canonical?.source ?: MetadataSource.FALLBACK)
                put("displayName", displayName.source)
                put("familyName", familySource)
                put("workload", workload.source)
                put("contextTokens", contextTokens.source)
                put(
                    "inputTokens",
                    inputTokens.source,
                )
                put("outputTokens", outputTokens.source)
                put(
                    "knowledgeCutoff",
                    knowledgeCutoff.source,
                )
                capabilityResolution.forEach { (capability, resolution) ->
                    put("capabilities.${capability.name}", resolution.source)
                }
            }),
            diagnostics = immutableSet(diagnostics),
        )
    }

    internal fun resolveExactOrNull(remoteModelId: String): ResolvedModelMetadata? {
        val resolved = resolve(remoteModelId)
        return resolved.takeIf {
            it.canonicalModelId != null && AMBIGUOUS_EXACT_MATCH !in it.diagnostics
        }
    }

    private fun MutableMap<String, MutableList<ModelMetadataRecord>>.addCandidate(
        alias: String,
        record: ModelMetadataRecord,
    ) {
        getOrPut(normalizeRemoteModelId(alias)) { mutableListOf() }.add(record)
    }

    private fun resolveCapability(
        capability: ModelCapability,
        exactRecords: List<ModelMetadataRecord>,
        providerMetadata: ModelMetadataOverride?,
        userOverride: ModelMetadataOverride?,
    ): FieldResolution<SupportState> {
        var result = exactField(exactRecords) { it.capabilities[capability] }
            .withFallback(SupportState.UNKNOWN)
        if (providerMetadata?.capabilities?.containsKey(capability) == true) {
            result = FieldResolution(providerMetadata.capabilities.getValue(capability), MetadataSource.PROVIDER)
        }
        if (userOverride?.capabilities?.containsKey(capability) == true) {
            result = FieldResolution(userOverride.capabilities.getValue(capability), MetadataSource.USER)
        }
        return result
    }

    private fun <T : Any> resolveField(
        catalog: FieldResolution<T?>,
        providerValue: T?,
        userValue: T?,
        fallback: T,
    ): FieldResolution<T> {
        var result = catalog.withFallback(fallback)
        if (providerValue != null) result = FieldResolution(providerValue, MetadataSource.PROVIDER)
        if (userValue != null) result = FieldResolution(userValue, MetadataSource.USER)
        return result
    }

    private fun <T> resolveNullableField(
        catalog: FieldResolution<T?>,
        providerValue: T?,
        userValue: T?,
    ): FieldResolution<T?> {
        var result = catalog
        if (providerValue != null) result = FieldResolution(providerValue, MetadataSource.PROVIDER)
        if (userValue != null) result = FieldResolution(userValue, MetadataSource.USER)
        return result
    }

    private fun familyNameFor(normalizedId: String): String? {
        val modelName = normalizedId.substringAfterLast('/')
        return if (
            modelName == "deepseek" ||
            modelName.startsWith("deepseek-") ||
            modelName.startsWith("deepseek_") ||
            modelName.startsWith("deepseek.")
        ) {
            "DeepSeek"
        } else {
            null
        }
    }

    private fun exactSourcePriority(source: MetadataSource): Int = when (source) {
        MetadataSource.NEXARA_OVERRIDE -> 2
        MetadataSource.MODELS_DEV -> 1
        else -> 0
    }

    private data class FieldResolution<T>(val value: T, val source: MetadataSource)

    private fun <T> exactField(
        records: List<ModelMetadataRecord>,
        selector: (ModelMetadataRecord) -> T?,
    ): FieldResolution<T?> {
        val record = records.firstOrNull { selector(it) != null }
        return FieldResolution(record?.let(selector), record?.source ?: MetadataSource.FALLBACK)
    }

    private fun <T : Any> FieldResolution<T?>.withFallback(fallback: T): FieldResolution<T> =
        if (value == null) FieldResolution(fallback, MetadataSource.FALLBACK) else FieldResolution(value, source)

    private fun exactRecordsFor(candidates: List<ModelMetadataRecord>): List<ModelMetadataRecord> {
        if (isTopPriorityAmbiguous(candidates)) return emptyList()
        return candidates
            .groupBy { exactSourcePriority(it.source) }
            .toSortedMap(compareByDescending { it })
            .values
            .mapNotNull { it.singleOrNull() }
    }

    private fun isTopPriorityAmbiguous(candidates: List<ModelMetadataRecord>): Boolean {
        val highestPriority = candidates.maxOfOrNull { exactSourcePriority(it.source) } ?: return false
        return candidates.count { exactSourcePriority(it.source) == highestPriority } > 1
    }

    private companion object {
        const val AMBIGUOUS_EXACT_MATCH = "ambiguous_exact_match"
    }
}

internal fun normalizeRemoteModelId(value: String): String = value
    .trim()
    .substringAfter("::", value.trim())
    .removePrefix("models/")
    .lowercase()

private fun ModelMetadataRecord.defensiveCopy(): ModelMetadataRecord = copy(
    exactAliases = immutableSet(exactAliases),
    capabilities = immutableMap(capabilities),
)

private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))

private fun <T> immutableSet(source: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(source))
