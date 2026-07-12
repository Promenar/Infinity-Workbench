package com.promenar.nexara.data.agent

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import org.junit.jupiter.api.Test

class PresetAgentsTest {

    @Test
    fun `database seed fallback is locale independent`() {
        assertThat(PresetAgents.fallbackName("coder")).isEqualTo("Coding Expert")
        assertThat(PresetAgents.fallbackDescription("coder")).contains("architecture")
    }

    @Test
    fun `ids exposes the three stable preset agents`() {
        assertThat(PresetAgents.IDS).containsExactly("default", "coder", "writer")
    }

    @Test
    fun `nameRes maps each preset id to a string resource`() {
        assertThat(PresetAgents.nameRes("default")).isEqualTo(R.string.preset_agent_default_name)
        assertThat(PresetAgents.nameRes("coder")).isEqualTo(R.string.preset_agent_coder_name)
        assertThat(PresetAgents.nameRes("writer")).isEqualTo(R.string.preset_agent_writer_name)
    }

    @Test
    fun `descRes maps each preset id to a string resource`() {
        assertThat(PresetAgents.descRes("default")).isEqualTo(R.string.preset_agent_default_desc)
        assertThat(PresetAgents.descRes("coder")).isEqualTo(R.string.preset_agent_coder_desc)
        assertThat(PresetAgents.descRes("writer")).isEqualTo(R.string.preset_agent_writer_desc)
    }

    @Test
    fun `unknown id yields zero resource id and is not preset`() {
        assertThat(PresetAgents.nameRes("unknown")).isEqualTo(0)
        assertThat(PresetAgents.descRes("unknown")).isEqualTo(0)
        assertThat(PresetAgents.isPreset("unknown")).isFalse()
        assertThat(PresetAgents.isPreset("")).isFalse()
    }

    @Test
    fun `isPreset recognises the three preset ids`() {
        assertThat(PresetAgents.isPreset("default")).isTrue()
        assertThat(PresetAgents.isPreset("coder")).isTrue()
        assertThat(PresetAgents.isPreset("writer")).isTrue()
    }
}
