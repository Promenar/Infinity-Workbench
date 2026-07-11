package com.promenar.nexara.data.backup

internal object BackupPreferencePolicy {
    private val deniedFragments = listOf(
        "api_key", "apikey", "authorization", "cookie", "token", "password", "passwd", "secret",
        "private_key", "privatekey", "service_account", "serviceaccount", "vertex_json", "vertexjson",
        "credential", "webdav_pass", "webdavpass", "automatic_backup_password", "automaticbackuppassword",
        "tavily_key", "tavilykey", "embedding_key", "embeddingkey",
    )

    private val exactAllowed = mapOf(
        "provider" to setOf("protocol_id", "protocol_id_name", "base_url", "model", "provider_name"),
        "settings" to setOf(
            "language", "theme_mode", "haptic_enabled", "loop_limit", "user_name", "user_avatar",
            "extra_providers_count", "extra_providers_ids", "all_models", "enabled_models", "all_models_order",
            "preset_summary_model", "preset_image_model", "preset_embedding_model", "preset_rerank_model",
            "local_models_enabled", "local_auto_load", "last_local_model", "preset_skills_migrated_v3",
            "enabled_skills",
        ),
        "search" to setOf(
            "web_search_enabled", "search_engine", "searxng_url", "search_depth", "result_count",
            "include_domains", "exclude_domains",
        ),
        "rag" to setOf(
            "doc_chunk_size", "chunk_overlap", "memory_chunk_size", "summary_template", "current_preset",
            "memory_limit", "memory_threshold", "doc_limit", "doc_threshold", "enable_rerank",
            "rerank_top_k", "rerank_final_k", "rerank_max_per_call", "enable_query_rewrite",
            "query_rewrite_strategy", "query_rewrite_count", "query_rewrite_model", "enable_hybrid_search",
            "hybrid_alpha", "hybrid_bm25_boost", "enable_memory", "enable_docs", "enable_kg", "kg_model",
            "kg_prompt", "kg_free_mode", "kg_domain_auto", "kg_extraction_timeout", "jit_max_chunks",
            "enable_incremental_hash", "enable_local_preprocess", "cost_strategy", "show_retrieval_progress",
            "show_retrieval_details", "track_retrieval_metrics", "embed_dimension",
            "max_embed_tokens_per_call", "embedding_base_url", "embedding_model", "rerank_base_url",
        ),
        "ui" to setOf("has_shown_welcome", "language", "theme_mode", "haptic_enabled", "loop_limit", "user_name"),
        "backup" to setOf("webdav_enabled", "auto_backup", "webdav_url", "webdav_user", "last_backup_time"),
    )

    private val dynamicAllowed = mapOf(
        "settings" to listOf(
            Regex("extra_provider_[0-9]+_(id|name|protocol|type|base_url|model)"),
            Regex("model_info_.+_(name|type|context|provider|provider_id|maxoutput|cutoff|caps)"),
        ),
        "provider" to listOf(
            Regex("provider_.+_(id|name|protocol|type|base_url|model|enabled)"),
        ),
    )

    fun isAllowed(namespace: String, key: String): Boolean {
        val normalizedNamespace = normalizeNamespace(namespace)
        val normalizedKey = normalize(key)
        if (deniedFragments.any(normalizedKey::contains)) return false
        return normalizedKey in exactAllowed[normalizedNamespace].orEmpty() ||
            dynamicAllowed[normalizedNamespace].orEmpty().any { it.matches(normalizedKey) }
    }

    fun isKnown(namespace: String, key: String): Boolean = isAllowed(namespace, key) || isDenied(key)

    fun isDenied(key: String): Boolean {
        val normalized = normalize(key)
        return deniedFragments.any(normalized::contains)
    }

    private fun normalizeNamespace(value: String): String = when (normalize(value)) {
        "nexara_provider", "provider", "model" -> "provider"
        "nexara_settings", "settings" -> "settings"
        "rag_settings", "rag" -> "rag"
        "nexara_search", "search" -> "search"
        "nexara_prefs", "ui" -> "ui"
        "nexara_backup_settings", "backup" -> "backup"
        else -> normalize(value)
    }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "_")
}
