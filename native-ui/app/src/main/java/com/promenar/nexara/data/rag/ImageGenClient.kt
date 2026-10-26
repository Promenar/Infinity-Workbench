package com.promenar.nexara.data.rag

import com.promenar.nexara.data.remote.ModelHttp
import com.promenar.nexara.data.remote.ModelTimeoutKind
import com.promenar.nexara.data.remote.applyModelTimeout
import com.promenar.nexara.data.remote.withModelTimeoutSupport
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
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
    private val model: String,
    httpClient: HttpClient = ModelHttp.client {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000  // 图像生成可能较慢
            connectTimeoutMillis = 15_000
            // 上游出图前长时间不回包；不设时沿用 OkHttp 默认 10 秒读超时，十几秒的生图会被切断。
            socketTimeoutMillis = 120_000
        }
    },
    private val responseBodyReader: suspend (HttpResponse) -> String = { response ->
        response.bodyAsText()
    },
) {
    private val httpClient = httpClient.withModelTimeoutSupport()
    private val json = Json { ignoreUnknownKeys = true }

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
        size: String? = null,
        quality: String? = null,
        style: String? = null,
        n: Int = 1
    ): ImageGenResult {
        require(baseUrl.isNotBlank()) { "Image generation base URL not configured" }
        require(apiKey.isNotBlank()) { "Image generation API key not configured" }
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        val cleanBase = baseUrl.trimEnd('/')
        val endpoint = if (cleanBase.endsWith("/v1")) "$cleanBase/images/generations"
        else "$cleanBase/v1/images/generations"

        // 审计缺陷 P0-4：部分聚合网关对 DALL-E 专有参数（quality/style/response_format）返回 400，
        // 只发送调用方显式给出的参数；服务端默认返回 b64_json，客户端两种格式均可解析。
        val requestBody = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", n)
            size?.let { put("size", it) }
            quality?.let { put("quality", it) }
            style?.let { put("style", it) }
        }

        val response: HttpResponse = httpClient.post(endpoint) {
            applyModelTimeout(ModelTimeoutKind.IMAGE)
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        if (!response.status.isSuccess()) {
            val errorBody = try {
                responseBodyReader(response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ""
            }
            // 按错误体结构提取状态码、错误码与说明，避免硬截断把服务端 JSON 拦腰截断。
            val summary = com.promenar.nexara.data.remote.httpErrorSummary(response.status.value, errorBody)
            throw IllegalStateException(
                "Image generation failed (${summary.head})" + summary.detail?.let { ": $it" }.orEmpty()
            )
        }

        val responseText = responseBodyReader(response)
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
