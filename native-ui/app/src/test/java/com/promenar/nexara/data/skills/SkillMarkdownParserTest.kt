package com.promenar.nexara.data.skills

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SkillMarkdownParserTest {
    @Test
    fun `parses required fields folded description and allowed tools`() {
        val parsed = SkillMarkdownParser.parse(
            """
            ---
            name: pdf-processing
            description: >
              Extract text and tables from PDF files.
              Use when the user mentions PDFs.
            license: "Apache-2.0"
            allowed-tools: read_file search_files
            metadata:
              author: example
            ---

            # PDF Processing
            Step one.
            """.trimIndent(),
        )

        assertThat(parsed.metadata.name).isEqualTo("pdf-processing")
        assertThat(parsed.metadata.description)
            .isEqualTo("Extract text and tables from PDF files. Use when the user mentions PDFs.")
        assertThat(parsed.metadata.license).isEqualTo("Apache-2.0")
        assertThat(parsed.metadata.allowedTools).containsExactly("read_file", "search_files").inOrder()
        assertThat(parsed.body).isEqualTo("# PDF Processing\nStep one.")
    }

    @Test
    fun `accepts CRLF BOM and list style allowed tools`() {
        val parsed = SkillMarkdownParser.parse(
            "\uFEFF---\r\nname: a1\r\ndescription: 'It''s fine'\r\nallowed-tools:\r\n  - read_file\r\n---\r\nbody",
        )

        assertThat(parsed.metadata.description).isEqualTo("It's fine")
        assertThat(parsed.metadata.allowedTools).containsExactly("read_file")
        assertThat(parsed.body).isEqualTo("body")
    }

    @Test
    fun `rejects invalid names missing frontmatter and empty description`() {
        listOf(
            "no frontmatter",
            "---\nname: Bad_Name\ndescription: x\n---\n",
            "---\nname: -lead\ndescription: x\n---\n",
            "---\nname: ok\n---\n",
            "---\nname: ok\ndescription: x\n",
            "---\nname: ${"a".repeat(65)}\ndescription: x\n---\n",
        ).forEach { text ->
            assertThrows(AgentSkillFormatException::class.java) { SkillMarkdownParser.parse(text) }
        }
    }

    @Test
    fun `render round trips through parse`() {
        val metadata = AgentSkillMetadata("weekly-report", "Summarize \"weekly\" work\nfor managers", "MIT", listOf("read_file"))

        val parsed = SkillMarkdownParser.parse(SkillMarkdownParser.render(metadata, "Body text"))

        assertThat(parsed.metadata).isEqualTo(metadata.copy(description = "Summarize \"weekly\" work for managers"))
        assertThat(parsed.body).isEqualTo("Body text")
    }
}
