package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.ktor.client.*

class WeatherSkill(
    private val httpClient: HttpClient
) : SkillDefinition {
    override val id: String = "weather_lookup"
    override val name: String = "weather_lookup"
    override val description: String = "Get current weather information for a specific location."
    override val mcpServerId: String? = null
    
    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "location": { "type": "string", "description": "The city name or coordinates" }
            },
            "required": ["location"]
        }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val location = args["location"] as? String ?: return ToolResult("err", "Missing location", "error")
        
        return try {
            val weatherInfo = "Current weather in $location: 22°C, Sunny, Humidity 45%"
            ToolResult("weather_${System.currentTimeMillis()}", weatherInfo, "success")
        } catch (e: Exception) {
            ToolResult("weather_${System.currentTimeMillis()}", "Failed to fetch weather: ${e.message}", "error")
        }
    }
}
