package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.rag.ImageGenClient
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.promenar.nexara.domain.media.*
import com.promenar.nexara.data.media.download.WorkspaceMediaDownloader
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import com.promenar.nexara.ui.chat.manager.registry.stringArgument

/**
 * 图像生成工具。
 *
 * 供 LLM 调用，接收提示词和可选参数，调用默认图像模型生成图片，
 * 把生成结果保存到会话工作区，返回虚拟路径和媒体引用。
 *
 * 工具名: generate_image
 * 参数:
 *   - prompt (必需): 图像描述提示词
 *   - size  (可选): 输出尺寸，默认 "1024x1024"
 *   - quality (可选): 质量，默认 "standard"
 *   - style (可选): 风格，"vivid" / "natural"
 */
class ImageGenerationSkill(
    @Suppress("UNUSED_PARAMETER") appContext: Context,
    private val providerManager: ProviderManager,
    private val mediaStore: WorkspaceMediaStore? = null,
    private val mediaDownloader: WorkspaceMediaDownloader? = null,
    private val imageGenClientFactory: (baseUrl: String, apiKey: String, modelId: String) -> ImageGenClient =
        { baseUrl, apiKey, modelId -> ImageGenClient(baseUrl = baseUrl, apiKey = apiKey, model = modelId) },
) : SkillDefinition {

    override val id = "image_generation"
    override val name = "generate_image"
    override val description =
        "Generate an image from a text description. " +
        "Use this when the user asks to create, draw, or generate an image, illustration, or artwork. " +
        "Provide a detailed prompt describing the desired image content, style, composition, and mood."
    override val mcpServerId: String? = null
    override val risk = ToolRisk.EXTERNAL_WRITE

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "A detailed description of the image to generate. Include subject, style, composition, lighting, and mood. Be as specific as possible."
                },
                "size": {
                    "type": "string",
                    "enum": ["1024x1024", "1792x1024", "1024x1792"],
                    "description": "Output image dimensions. Default is 1024x1024."
                },
                "quality": {
                    "type": "string",
                    "enum": ["standard", "hd"],
                    "description": "Image quality level. Default is standard."
                },
                "style": {
                    "type": "string",
                    "enum": ["vivid", "natural"],
                    "description": "Image style. 'vivid' for hyper-real and dramatic, 'natural' for more realistic. Optional."
                }
            },
            "required": ["prompt"]
        }
    """.trimIndent()

    override suspend fun execute(
        args: JsonObject,
        context: SkillExecutionContext
    ): ToolResult {
        val resultId = "img_${UUID.randomUUID().toString().take(8)}"

        val prompt = args.stringArgument("prompt")
            ?: return ToolResult(id = resultId, content = "Missing required parameter: prompt", status = "error")

        if (prompt.isBlank()) {
            return ToolResult(id = resultId, content = "Prompt must not be blank", status = "error")
        }

        val size = args.stringArgument("size")
        val quality = args.stringArgument("quality")
        val style = args.stringArgument("style")

        // ── 读取图像模型配置 ──
        val mainConfig = providerManager.getMainProviderConfig()
        val baseUrl = mainConfig?.baseUrl?.takeIf { it.isNotBlank() } ?: ""
        val apiKey = mainConfig?.apiKey?.takeIf { it.isNotBlank() } ?: ""
        val modelId = providerManager.imageModelId.value.takeIf { it.isNotBlank() } ?: ""

        if (baseUrl.isBlank()) {
            return ToolResult(id = resultId, content = "Image generation not configured: no provider base URL set. Please configure a provider in Settings.", status = "error")
        }
        if (apiKey.isBlank()) {
            return ToolResult(id = resultId, content = "Image generation not configured: no API key set. Please configure a provider in Settings.", status = "error")
        }
        if (modelId.isBlank()) {
            return ToolResult(id = resultId, content = "Image generation not configured: no image model selected. Please select an image model in Settings → Default Image Model.", status = "error")
        }

        return try {
            val client = imageGenClientFactory(baseUrl, apiKey, modelId)

            val result = withContext(Dispatchers.IO) {
                client.generate(
                    prompt = prompt,
                    size = size,
                    quality = quality,
                    style = style
                )
            }

            if (result.images.isEmpty()) {
                return ToolResult(id = resultId, content = "Image generation returned no images.", status = "error")
            }

            val saved = mutableListOf<MediaRef>()
            val failures = mutableListOf<String>()
            val revised = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (image in result.images) {
                    image.revisedPrompt?.takeIf(String::isNotBlank)?.let(revised::add)
                    try {
                        val store = mediaStore ?: throw WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用，图片未保存")
                        val ref = when {
                            image.url != null -> {
                                val downloader = mediaDownloader ?: throw WorkspaceMediaException("DOWNLOAD_UNAVAILABLE", "图片下载服务不可用")
                                downloader.save(image.url, context.sessionId, store, MediaOrigin.GENERATED,
                                    maxBytes = MAX_WORKSPACE_MEDIA_BYTES, imageOnly = true)
                            }
                            image.b64Json != null -> store.save(context.sessionId,
                                MediaPayload.Bytes(decodeMediaBase64(image.b64Json)), MediaOrigin.GENERATED, "image/png")
                            else -> throw WorkspaceMediaException("IMAGE_MISSING", "生图结果没有 URL 或 base64 图片")
                        }
                        saved += ref
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failures += mediaFailureDescription(failure)
                    }
                }
            }
            ToolResult(
                id = resultId,
                content = buildString {
                    appendLine("图片保存成功 ${saved.size} 张，失败 ${failures.size} 张。")
                    saved.forEach { appendLine(mediaSavedDescription(it)); appendLine(mediaReferenceGuidance(it)) }
                    failures.forEach { appendLine("保存失败：$it") }
                    revised.distinct().forEach { appendLine("模型修订提示词：$it") }
                    append("原始提示词：$prompt")
                },
                status = if (saved.isEmpty()) "error" else "success",
                media = saved.takeIf { it.isNotEmpty() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ToolResult(
                id = resultId,
                content = "Image generation failed: ${e.message?.take(200) ?: "Unknown error"}",
                status = "error"
            )
        }
    }

}

/**
 * 生成图片的本地存储元信息。
 * 序列化为 JSON 存入 Message.images 字段。
 */
@kotlinx.serialization.Serializable
data class GeneratedImageData(
    val url: String? = null,
    val b64Json: String? = null,
    val revisedPrompt: String? = null,
    val localPath: String? = null       // 本地存储路径
)
