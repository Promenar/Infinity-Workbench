package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background

/**
 * 模型 ID / family → 品牌资产键映射。
 * 品牌图标来自开源项目 lobehub/lobe-icons（MIT License），SVG 资产位于 assets/model_brands/。
 */
object ModelBrandResolver {
    private val rules = listOf(
        "deepseek" to "deepseek",
        "sensenova" to "sensenova",
        "claude" to "anthropic",
        "anthropic" to "anthropic",
        "gemini" to "google",
        "gemma" to "google",
        "google" to "google",
        "gpt" to "openai",
        "codex" to "openai",
        "chatgpt" to "openai",
        "openai" to "openai",
        "glm" to "zhipu",
        "chatglm" to "zhipu",
        "zhipu" to "zhipu",
        "doubao" to "volcengine",
        "seed" to "volcengine",
        "volcengine" to "volcengine",
        "kimi" to "kimi",
        "moonshot" to "kimi",
        "minimax" to "minimax",
        "abab" to "minimax",
        "qwen" to "qwen",
        "qwq" to "qwen",
        "llama" to "meta",
        "mistral" to "mistral",
        "ministral" to "mistral",
        "mixtral" to "mistral",
        "grok" to "grok",
        "xai" to "grok",
        "groq" to "groq",
        "nemotron" to "nvidia",
        "nvidia" to "nvidia",
        "command" to "cohere",
        "cohere" to "cohere",
        "jamba" to "ai21",
        "ai21" to "ai21",
        "ollama" to "ollama",
        "openrouter" to "openrouter",
    )

    val knownBrands: Set<String> = rules.map { it.second }.toSet()

    /** 依次在给定的来源字符串（模型 ID、family 等）中查找品牌键；找不到返回 null。 */
    fun brandKey(vararg sources: String?): String? {
        for (raw in sources) {
            val source = raw?.lowercase() ?: continue
            for ((needle, brand) in rules) {
                if (needle in source) return brand
            }
        }
        return null
    }
}

/**
 * 模型品牌图标：按品牌键渲染 assets/model_brands/ 下的 SVG；
 * 未识别品牌时回退到 [fallback]（默认通用芯片图标）。
 */
@Composable
fun ModelBrandIcon(
    modelId: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallbackTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val brand = ModelBrandResolver.brandKey(modelId)
    if (brand == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Memory,
                contentDescription = contentDescription,
                tint = fallbackTint,
            )
        }
        return
    }
    SubcomposeAsyncImage(
        model = "file:///android_asset/model_brands/$brand.svg",
        contentDescription = contentDescription,
        modifier = modifier,
        error = {
            Box(modifier = Modifier, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = contentDescription,
                    tint = fallbackTint,
                )
            }
        },
    )
}

/** 品牌 Logo 的标准底座：圆形浅色衬底，与全站圆形/胶囊视觉语言一致。 */
@Composable
fun ModelBrandTile(
    modelId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ModelBrandIcon(
            modelId = modelId,
            modifier = Modifier.size(size * 0.58f),
            contentDescription = contentDescription,
            fallbackTint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
