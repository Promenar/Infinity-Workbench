package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import android.graphics.BitmapFactory
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.rag.ImageGenClient
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.data.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID

/**
 * 图像生成工具。
 *
 * 供 LLM 调用，接收提示词和可选参数，调用默认图像模型生成图片，
 * 下载保存到本地，返回图片信息供主会话界面展示。
 *
 * 工具名: generate_image
 * 参数:
 *   - prompt (必需): 图像描述提示词
 *   - size  (可选): 输出尺寸，默认 "1024x1024"
 *   - quality (可选): 质量，默认 "standard"
 *   - style (可选): 风格，"vivid" / "natural"
 */
class ImageGenerationSkill(
    private val appContext: Context,
    private val providerManager: ProviderManager
) : SkillDefinition {

    override val id = "image_generation"
    override val name = "generate_image"
    override val description =
        "Generate an image from a text description. " +
        "Use this when the user asks to create, draw, or generate an image, illustration, or artwork. " +
        "Provide a detailed prompt describing the desired image content, style, composition, and mood."
    override val mcpServerId: String? = null

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
        args: Map<String, Any>,
        context: SkillExecutionContext
    ): ToolResult {
        val resultId = "img_${UUID.randomUUID().toString().take(8)}"

        val prompt = args["prompt"]?.toString()
            ?: return ToolResult(id = resultId, content = "Missing required parameter: prompt", status = "error")

        if (prompt.isBlank()) {
            return ToolResult(id = resultId, content = "Prompt must not be blank", status = "error")
        }

        val size = args["size"]?.toString() ?: "1024x1024"
        val quality = args["quality"]?.toString() ?: "standard"
        val style = args["style"]?.toString()

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
            val client = ImageGenClient(baseUrl = baseUrl, apiKey = apiKey, model = modelId)

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

            // 下载或解码图片到本地存储
            val savedImages = withContext(Dispatchers.IO) {
                result.images.mapNotNull { image ->
                    val data = GeneratedImageData(
                        url = image.url,
                        b64Json = image.b64Json,
                        revisedPrompt = image.revisedPrompt
                    )
                    saveImageToLocal(data)
                }
            }

            if (savedImages.isEmpty()) {
                return ToolResult(
                    id = resultId,
                    content = "Image generated but failed to save locally. URL: ${result.images.first().url}",
                    status = "error"
                )
            }

            val firstImage = savedImages.first()
            val countSuffix = if (savedImages.size > 1) " (${savedImages.size} images generated)" else ""

            // 将图片信息编码到 data 字段，供 ChatBubble 渲染
            val imageData = Json.encodeToString(savedImages)

            val revisedNote = result.images.first().revisedPrompt?.let { rp ->
                "\n\n*Revised prompt used by model:* $rp"
            } ?: ""

            ToolResult(
                id = resultId,
                content = "Image generated successfully$countSuffix.$revisedNote\n\nPrompt: \"$prompt\"",
                data = imageData
            )
        } catch (e: Exception) {
            ToolResult(
                id = resultId,
                content = "Image generation failed: ${e.message?.take(200) ?: "Unknown error"}",
                status = "error"
            )
        }
    }

    /**
     * 将生成的图片保存到应用内部存储，返回本地路径和元信息。
     */
    private fun saveImageToLocal(image: GeneratedImageData): GeneratedImageData? {
        return try {
            val imageDir = File(appContext.filesDir, "generated_images")
            if (!imageDir.exists()) imageDir.mkdirs()

            val fileName = "gen_${UUID.randomUUID().toString().take(12)}.png"
            val file = File(imageDir, fileName)

            if (image.url != null) {
                downloadFromUrl(image.url, file)
            } else if (image.b64Json != null) {
                decodeBase64ToFile(image.b64Json, file)
            } else {
                return null
            }

            image.copy(localPath = file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadFromUrl(imageUrl: String, destFile: File) {
        val url = URL(imageUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.useCaches = false

        connection.inputStream.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun decodeBase64ToFile(b64: String, destFile: File) {
        val bytes = Base64.getDecoder().decode(b64)
        destFile.writeBytes(bytes)
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
