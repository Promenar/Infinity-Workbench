package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.domain.tool.ToolRisk
import org.junit.Test

class ToolInvocationIdentityFactoryTest {
    private fun tool(
        runtimeToolId: String = "read_file",
        name: String = "read_file",
        description: String = "读取文件",
        schema: String = """{"type":"object","properties":{"uuid":{"type":"string"}},"required":["uuid"]}""",
        risk: ToolRisk = ToolRisk.SAFE_READ,
        sourceId: String = "builtin",
        serverId: String? = null,
    ) = ProtocolTool(
        runtimeToolId = runtimeToolId,
        sourceId = sourceId,
        mcpServerId = serverId,
        risk = risk,
        function = ProtocolToolFunction(name, description, schema),
    )

    @Test
    fun `schema 对象键重排不改变 definition digest`() {
        val first = tool(schema = """{"type":"object","properties":{"b":{"type":"integer"},"a":{"type":"string"}},"required":["a"]}""")
        val second = tool(schema = """{"required":["a"],"properties":{"a":{"type":"string"},"b":{"type":"integer"}},"type":"object"}""")

        val firstDigest = ToolInvocationIdentityFactory.definitionDigest(first)
        val secondDigest = ToolInvocationIdentityFactory.definitionDigest(second)

        assertThat(firstDigest).isInstanceOf(ToolDefinitionDigestResolution.Valid::class.java)
        assertThat(secondDigest).isEqualTo(firstDigest)
    }

    @Test
    fun `定义身份绑定运行时 id 名称描述 schema 风险来源和 server`() {
        val base = ToolInvocationIdentityFactory.definitionDigest(tool())
            as ToolDefinitionDigestResolution.Valid
        val variants = listOf(
            tool(runtimeToolId = "other"),
            tool(name = "other"),
            tool(description = "另一描述"),
            tool(schema = """{"type":"object","properties":{}}"""),
            tool(risk = ToolRisk.FILE_WRITE),
            tool(sourceId = "custom"),
            tool(sourceId = "mcp", serverId = "server-1"),
        )

        variants.forEach { variant ->
            val digest = ToolInvocationIdentityFactory.definitionDigest(variant)
                as ToolDefinitionDigestResolution.Valid
            assertThat(digest.digest).isNotEqualTo(base.digest)
        }
    }

    @Test
    fun `prepared tool call 生成绑定参数与定义的不可变身份`() {
        val resolution = ToolInvocationIdentityFactory.fromPreparedToolCall(
            call = ToolCall("call-1", "read_file", """{"uuid":"u1"}"""),
            tool = tool(),
            requiresApproval = false,
        ) as ToolInvocationIdentityResolution.Valid

        assertThat(resolution.identity.runtimeToolId).isEqualTo("read_file")
        assertThat(resolution.identity.toolName).isEqualTo("read_file")
        assertThat(resolution.identity.requiresApproval).isFalse()
        assertThat(resolution.arguments.getValue("uuid").toString()).isEqualTo("\"u1\"")
        assertThat(resolution.identity.definitionDigest).hasLength(64)
        assertThat(resolution.identity.argumentsDigest).hasLength(64)
    }

    @Test
    fun `prepared tool call 名称参数与 schema 任一不符均 fail closed`() {
        val prepared = tool()
        val wrongName = ToolInvocationIdentityFactory.fromPreparedToolCall(
            ToolCall("call-1", "write_file", """{"uuid":"u1"}"""),
            prepared,
            false,
        ) as ToolInvocationIdentityResolution.Invalid
        val malformed = ToolInvocationIdentityFactory.fromPreparedToolCall(
            ToolCall("call-2", "read_file", "{"),
            prepared,
            false,
        ) as ToolInvocationIdentityResolution.Invalid
        val schemaMismatch = ToolInvocationIdentityFactory.fromPreparedToolCall(
            ToolCall("call-3", "read_file", "{}"),
            prepared,
            false,
        ) as ToolInvocationIdentityResolution.Invalid

        assertThat(wrongName.code).isEqualTo(ToolInvocationIdentityErrorCode.TOOL_NAME_MISMATCH)
        assertThat(malformed.code).isEqualTo(ToolInvocationIdentityErrorCode.MALFORMED_ARGUMENTS)
        assertThat(schemaMismatch.code).isEqualTo(ToolInvocationIdentityErrorCode.SCHEMA_MISMATCH)
    }

    @Test
    fun `generate image 使用稳定运行时 id`() {
        val image = tool(
            runtimeToolId = "image_generation",
            name = "generate_image",
            description = "生成图片",
            schema = """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}""",
            risk = ToolRisk.EXTERNAL_WRITE,
        )

        val resolution = ToolInvocationIdentityFactory.fromPreparedToolCall(
            ToolCall("call-image", "generate_image", """{"prompt":"cat"}"""),
            image,
            true,
        ) as ToolInvocationIdentityResolution.Valid

        assertThat(resolution.identity.runtimeToolId).isEqualTo("image_generation")
    }
}
