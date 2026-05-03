package com.promenar.nexara.data.remote.parser

import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.TokenUsage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ResponseNormalizerTest {

    private fun normalize(raw: JsonObject, type: ProviderType) =
        ResponseNormalizer.normalize(raw, type)

    @Nested
    @DisplayName("OpenAI Compatible")
    inner class OpenAICompatible {

        @Test
        fun `text delta returns Text`() {
            val raw = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject {
                            put("content", "Hello")
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.OPENAI_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat(result.content).isEqualTo("Hello")
        }

        @Test
        fun `reasoning_content returns WithReasoning`() {
            val raw = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject {
                            put("content", "")
                            put("reasoning_content", "Let me think...")
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.OPENAI_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.WithReasoning::class.java)
            val withReasoning = result as NormalizedResponse.WithReasoning
            assertThat(withReasoning.reasoning).isEqualTo("Let me think...")
        }

        @Test
        fun `content and reasoning together`() {
            val raw = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject {
                            put("content", "The answer")
                            put("reasoning_content", "I deduced it")
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.OPENAI_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.WithReasoning::class.java)
            val withReasoning = result as NormalizedResponse.WithReasoning
            assertThat(withReasoning.content).isEqualTo("The answer")
            assertThat(withReasoning.reasoning).isEqualTo("I deduced it")
        }

        @Test
        fun `empty delta returns Empty`() {
            val raw = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject {})
                    })
                })
            }

            val result = normalize(raw, ProviderType.OPENAI_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.Empty::class.java)
        }

        @Test
        fun `no choices returns Empty`() {
            val raw = buildJsonObject { put("choices", buildJsonArray {}) }
            val result = normalize(raw, ProviderType.OPENAI_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.Empty::class.java)
        }

        @Test
        fun `DeepSeek-style reasoning_content`() {
            val raw = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject {
                            put("content", "42")
                            put("reasoning_content", "step by step...")
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.OPENAI_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.WithReasoning::class.java)
            val r = result as NormalizedResponse.WithReasoning
            assertThat(r.content).isEqualTo("42")
            assertThat(r.reasoning).isEqualTo("step by step...")
        }

        @Test
        fun `Moonshot-style basic delta`() {
            val raw = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject {
                            put("content", "Kimi response")
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.OPENAI_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat(result.content).isEqualTo("Kimi response")
        }
    }

    @Nested
    @DisplayName("Anthropic Compatible")
    inner class AnthropicCompatible {

        @Test
        fun `text_delta returns Text`() {
            val raw = buildJsonObject {
                put("type", "content_block_delta")
                put("delta", buildJsonObject {
                    put("type", "text_delta")
                    put("text", "Hello from Claude")
                })
            }

            val result = normalize(raw, ProviderType.ANTHROPIC_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat(result.content).isEqualTo("Hello from Claude")
        }

        @Test
        fun `thinking_delta returns WithReasoning`() {
            val raw = buildJsonObject {
                put("type", "content_block_delta")
                put("delta", buildJsonObject {
                    put("type", "thinking_delta")
                    put("thinking", "I need to analyze this...")
                })
            }

            val result = normalize(raw, ProviderType.ANTHROPIC_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.WithReasoning::class.java)
            val r = result as NormalizedResponse.WithReasoning
            assertThat(r.content).isEmpty()
            assertThat(r.reasoning).isEqualTo("I need to analyze this...")
        }

        @Test
        fun `content_block_start returns Empty`() {
            val raw = buildJsonObject {
                put("type", "content_block_start")
                put("delta", buildJsonObject {
                    put("type", "text_delta")
                    put("text", "ignored")
                })
            }

            val result = normalize(raw, ProviderType.ANTHROPIC_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.Empty::class.java)
        }

        @Test
        fun `no delta returns Empty`() {
            val raw = buildJsonObject {
                put("type", "content_block_delta")
            }

            val result = normalize(raw, ProviderType.ANTHROPIC_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.Empty::class.java)
        }

        @Test
        fun `message_start returns Empty`() {
            val raw = buildJsonObject {
                put("type", "message_start")
                put("delta", buildJsonObject {})
            }

            val result = normalize(raw, ProviderType.ANTHROPIC_COMPATIBLE)
            assertThat(result).isInstanceOf(NormalizedResponse.Empty::class.java)
        }
    }

    @Nested
    @DisplayName("Vertex AI")
    inner class VertexAI {

        @Test
        fun `simple text part returns Text`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    put("text", "Gemini says hello")
                                })
                            })
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat(result.content).isEqualTo("Gemini says hello")
        }

        @Test
        fun `thought part returns WithReasoning`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    put("thought", true)
                                    put("text", "Let me think about this")
                                })
                                add(buildJsonObject {
                                    put("text", "The answer is 42")
                                })
                            })
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.WithReasoning::class.java)
            val r = result as NormalizedResponse.WithReasoning
            assertThat(r.content).isEmpty()
            assertThat(r.reasoning).isEqualTo("Let me think about thisThe answer is 42")
        }

        @Test
        fun `thought as string value`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    put("thought", "reasoning text here")
                                })
                            })
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.WithReasoning::class.java)
            val r = result as NormalizedResponse.WithReasoning
            assertThat(r.reasoning).isEqualTo("reasoning text here")
        }

        @Test
        fun `no candidates returns Empty`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {})
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.Empty::class.java)
        }

        @Test
        fun `citations and tokens return Rich`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", "Answer") })
                            })
                        })
                        put("groundingMetadata", buildJsonObject {
                            put("groundingChunks", buildJsonArray {
                                add(buildJsonObject {
                                    put("web", buildJsonObject {
                                        put("title", "Source A")
                                        put("uri", "https://example.com")
                                    })
                                })
                            })
                        })
                    })
                })
                put("usageMetadata", buildJsonObject {
                    put("promptTokenCount", 100)
                    put("candidatesTokenCount", 50)
                    put("totalTokenCount", 150)
                })
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.Rich::class.java)
            val rich = result as NormalizedResponse.Rich
            assertThat(rich.content).isEqualTo("Answer")
            assertThat(rich.citations).hasSize(1)
            assertThat(rich.citations!!.first()).isEqualTo(
                Citation(title = "Source A", url = "https://example.com", source = "Google")
            )
            assertThat(rich.tokens).isEqualTo(TokenUsage(input = 100, output = 50, total = 150))
        }

        @Test
        fun `groundingMetadata at top level`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", "Referenced answer") })
                            })
                        })
                    })
                })
                put("groundingMetadata", buildJsonObject {
                    put("groundingChunks", buildJsonArray {
                        add(buildJsonObject {
                            put("web", buildJsonObject {
                                put("title", "Top-level Source")
                                put("uri", "https://top.example.com")
                            })
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.Rich::class.java)
            val rich = result as NormalizedResponse.Rich
            assertThat(rich.citations).hasSize(1)
            assertThat(rich.citations!!.first().title).isEqualTo("Top-level Source")
        }

        @Test
        fun `inline_data image returns Rich`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", "Here is an image") })
                                add(buildJsonObject {
                                    put("inline_data", buildJsonObject {
                                        put("data", "base64data==")
                                        put("mime_type", "image/png")
                                    })
                                })
                            })
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.Rich::class.java)
            val rich = result as NormalizedResponse.Rich
            assertThat(rich.content).isEqualTo("Here is an image")
            assertThat(rich.images).hasSize(1)
            assertThat(rich.images!!.first().mime).isEqualTo("image/png")
        }

        @Test
        fun `inlineData camelCase variant`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    put("inlineData", buildJsonObject {
                                        put("data", "imgdata==")
                                        put("mimeType", "image/jpeg")
                                    })
                                })
                            })
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.Rich::class.java)
            val rich = result as NormalizedResponse.Rich
            assertThat(rich.images).hasSize(1)
            assertThat(rich.images!!.first().mime).isEqualTo("image/jpeg")
        }

        @Test
        fun `empty groundingChunks returns no citations`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", "No refs") })
                            })
                        })
                        put("groundingMetadata", buildJsonObject {
                            put("groundingChunks", buildJsonArray {})
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat(result.content).isEqualTo("No refs")
        }

        @Test
        fun `no usageMetadata returns no tokens`() {
            val raw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", "Simple") })
                            })
                        })
                    })
                })
            }

            val result = normalize(raw, ProviderType.VERTEX_AI)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat((result as NormalizedResponse.Text).content).isEqualTo("Simple")
        }
    }

    @Nested
    @DisplayName("Generic")
    inner class Generic {

        @Test
        fun `content field`() {
            val raw = buildJsonObject { put("content", "fallback content") }
            val result = normalize(raw, ProviderType.GENERIC)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat(result.content).isEqualTo("fallback content")
        }

        @Test
        fun `text field fallback`() {
            val raw = buildJsonObject { put("text", "text fallback") }
            val result = normalize(raw, ProviderType.GENERIC)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat(result.content).isEqualTo("text fallback")
        }

        @Test
        fun `message field fallback`() {
            val raw = buildJsonObject { put("message", "msg fallback") }
            val result = normalize(raw, ProviderType.GENERIC)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat(result.content).isEqualTo("msg fallback")
        }

        @Test
        fun `no known field returns empty`() {
            val raw = buildJsonObject { put("unknown", "ignored") }
            val result = normalize(raw, ProviderType.GENERIC)
            assertThat(result).isInstanceOf(NormalizedResponse.Text::class.java)
            assertThat(result.content).isEmpty()
        }

        @Test
        fun `content takes priority over text and message`() {
            val raw = buildJsonObject {
                put("content", "first")
                put("text", "second")
                put("message", "third")
            }
            val result = normalize(raw, ProviderType.GENERIC)
            assertThat(result.content).isEqualTo("first")
        }
    }

    @Nested
    @DisplayName("Provider Routing")
    inner class ProviderRouting {

        @Test
        fun `same OpenAI format works for multiple provider names`() {
            val raw = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject { put("content", "test") })
                    })
                })
            }

            for (provider in listOf(
                ProviderType.OPENAI_COMPATIBLE
            )) {
                val result = normalize(raw, provider)
                assertThat(result.content).isEqualTo("test")
            }
        }

        @Test
        fun `Vertex uses candidate structure not choices`() {
            val vertexRaw = buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", "vertex") })
                            })
                        })
                    })
                })
            }

            val result = normalize(vertexRaw, ProviderType.VERTEX_AI)
            assertThat(result.content).isEqualTo("vertex")
        }

        @Test
        fun `Anthropic uses type-delta structure`() {
            val anthropicRaw = buildJsonObject {
                put("type", "content_block_delta")
                put("delta", buildJsonObject {
                    put("type", "text_delta")
                    put("text", "claude says hi")
                })
            }

            val result = normalize(anthropicRaw, ProviderType.ANTHROPIC_COMPATIBLE)
            assertThat(result.content).isEqualTo("claude says hi")
        }
    }
}
