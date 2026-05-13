package com.promenar.nexara.data.rag

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * 图像生成 API 客户端。
 * 兼容 OpenAI Images API (`POST /v1/images/generations`) 及兼容端点。
 *
 * @param baseUrl  API 基础地址（如 https://api.openai.com）
 * @param apiKey   API 密钥
 * @param model    图像生成模型 ID（如 dall-e-3、stable-diffusion-xl 等）
 */
class ImageGenClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000  // 图像生成可能较慢
            connectTimeoutMillis = 15_000
        }
    }

    /**
     * 生成图像。
     *
     * @param prompt      图像描述提示词
     * @param size        输出尺寸（如 "1024x1024", "1792x1024"），默认 "1024x1024"
     * @param quality     质量（"standard" / "hd"），默认 "standard"
     * @param style       风格（"vivid" / "natural"），默认 null（不指定）
     * @param n           生成数量，默认 1
     * @return [ImageGenResult] 包含生成图像列表
     */
    suspend fun generate(
        prompt: String,
        size: String = "1024x1024",
        quality: String = "standard",
        style: String? = null,
        n: Int = 1
    ): ImageGenResult {
        require(baseUrl.isNotBlank()) { "Image generation base URL not configured" }
        require(apiKey.isNotBlank()) { "Image generation API key not configured" }
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        val cleanBase = baseUrl.trimEnd('/')
        val endpoint = if (cleanBase.endsWith("/v1")) "$cleanBase/images/generations"
        else "$cleanBase/v1/images/generations"

        val requestBody = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", n)
            put("size", size)
            put("quality", quality)
            if (style != null) put("style", style)
            put("response_format", "url")     // 请求返回 URL（而非 b64_json，节省带宽）
        }

        val response: HttpResponse = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw IllegalStateException(
                "Image generation failed (${response.status.value}): $errorBody".take(200)
            )
        }

        val responseText = response.bodyAsText()
        val jsonResponse = json.parseToJsonElement(responseText).jsonObject
        val dataArray = jsonResponse["data"]?.jsonArray
            ?: throw IllegalStateException("Missing 'data' array in image generation response")

        val images = dataArray.map { item ->
            val obj = item.jsonObject
            val url = obj["url"]?.jsonPrimitive?.content
            val b64 = obj["b64_json"]?.jsonPrimitive?.content
            val revisedPrompt = obj["revised_prompt"]?.jsonPrimitive?.content
            GeneratedImage(
                url = url,
                b64Json = b64,
                revisedPrompt = revisedPrompt
            )
        }

        return ImageGenResult(images = images)
    }
}

/**
 * 单张生成图像的数据。
 * @param url           图像 URL（response_format="url" 时）
 * @param b64Json       Base64 编码图像（response_format="b64_json" 时）
 * @param revisedPrompt 模型改写后的提示词（部分模型支持，如 DALL-E 3）
 */
@Serializable
data class GeneratedImage(
    val url: String? = null,
    val b64Json: String? = null,
    val revisedPrompt: String? = null
)

/**
 * 图像生成结果。
 */
@Serializable
data class ImageGenResult(
    val images: List<GeneratedImage>
)
