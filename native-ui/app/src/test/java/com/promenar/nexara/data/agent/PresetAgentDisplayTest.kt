package com.promenar.nexara.data.agent

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionMode
import org.junit.jupiter.api.Test

class PresetAgentDisplayTest {

    @Test
    fun `field-level customization keeps untouched description resource-backed`() {
        val agent = Agent(
            id = "coder",
            name = "My Coding Agent",
            description = "Stable fallback",
        )

        val fields = PresetAgentDisplay.resolve(agent.copy(nameCustomized = true))

        assertThat(fields.name).isEqualTo(PresetAgentDisplay.TextSource.Literal("My Coding Agent"))
        assertThat(fields.description)
            .isEqualTo(PresetAgentDisplay.TextSource.Resource(R.string.preset_agent_coder_desc))
    }

    @Test
    fun `preset uncustomized resolves to localized resource source`() {
        val agent = Agent(
            id = "default",
            name = "DB 中文残留",
            description = "DB desc",
            executionMode = ExecutionMode.SEMI
        )

        val fields = PresetAgentDisplay.resolve(agent)

        assertThat(fields.name)
            .isEqualTo(PresetAgentDisplay.TextSource.Resource(R.string.preset_agent_default_name))
        assertThat(fields.description)
            .isEqualTo(PresetAgentDisplay.TextSource.Resource(R.string.preset_agent_default_desc))
    }

    @Test
    fun `preset customized resolves to db literal so user edits are never overridden`() {
        val agent = Agent(
            id = "coder",
            name = "My Coder",
            description = "edited by user",
            nameCustomized = true,
            descriptionCustomized = true,
            executionMode = ExecutionMode.SEMI
        )

        val fields = PresetAgentDisplay.resolve(agent)

        assertThat(fields.name).isEqualTo(PresetAgentDisplay.TextSource.Literal("My Coder"))
        assertThat(fields.description).isEqualTo(PresetAgentDisplay.TextSource.Literal("edited by user"))
    }

    @Test
    fun `non preset agent always resolves to db literal regardless of customized flag`() {
        val agent = Agent(
            id = "user-1",
            name = "User Agent",
            description = "x",
            executionMode = ExecutionMode.SEMI
        )

        val fields = PresetAgentDisplay.resolve(agent)
        assertThat(fields.name).isEqualTo(PresetAgentDisplay.TextSource.Literal("User Agent"))
        assertThat(fields.description).isEqualTo(PresetAgentDisplay.TextSource.Literal("x"))
    }

    @Test
    fun `coder and writer presets resolve to their respective resources when uncustomized`() {
        val coder = PresetAgentDisplay.resolve(
            Agent(id = "coder", name = "x", executionMode = ExecutionMode.SEMI),
        )
        assertThat(coder.name)
            .isEqualTo(PresetAgentDisplay.TextSource.Resource(R.string.preset_agent_coder_name))

        val writer = PresetAgentDisplay.resolve(
            Agent(id = "writer", name = "x", executionMode = ExecutionMode.SEMI),
        )
        assertThat(writer.name)
            .isEqualTo(PresetAgentDisplay.TextSource.Resource(R.string.preset_agent_writer_name))
    }
}
