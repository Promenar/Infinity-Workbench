package com.promenar.nexara.data.remote.provider

import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertThrows
import org.junit.Test

class VertexCredentialParserTest {
    @Test
    fun `valid service account yields project email and RSA key without secret stringification`() {
        val fixture = validCredentialJson()

        val parsed = VertexCredentialParser.parse(fixture.json)

        assertThat(parsed.projectId).isEqualTo("project-safe")
        assertThat(parsed.clientEmail).isEqualTo("service@example.invalid")
        assertThat(parsed.toString()).doesNotContain(fixture.privateKeyPem)
        assertThat(parsed.toString()).doesNotContain(fixture.json)
    }

    @Test
    fun `explicit project must match credential project and errors never echo JSON`() {
        val fixture = validCredentialJson()

        val error = assertThrows(VertexCredentialException::class.java) {
            VertexCredentialParser.parse(fixture.json, explicitProjectId = "different-project")
        }

        assertThat(error.reason).isEqualTo(VertexCredentialFailure.PROJECT_ID_MISMATCH)
        assertThat(error.message).doesNotContain(fixture.privateKeyPem)
        assertThat(error.message).doesNotContain(fixture.json)
        assertThat(error.toString()).doesNotContain(fixture.privateKeyPem)
    }

    @Test
    fun `invalid private key fails with typed safe reason`() {
        val marker = "PRIVATE-MARKER-MUST-NOT-LEAK"
        val json = """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":"$marker"}"""

        val error = assertThrows(VertexCredentialException::class.java) {
            VertexCredentialParser.parse(json)
        }

        assertThat(error.reason).isEqualTo(VertexCredentialFailure.INVALID_PRIVATE_KEY)
        assertThat(error.message).doesNotContain(marker)
        assertThat(error.toString()).doesNotContain(marker)
        assertThat(error.cause?.message.orEmpty()).doesNotContain(marker)
    }

    @Test
    fun `credential fields reject every non string JSON type with typed missing reasons`() {
        val nonStringValues = listOf("{}", "[]", "null", "7", "true")

        nonStringValues.forEach { invalidValue ->
            val emailError = assertThrows(VertexCredentialException::class.java) {
                VertexCredentialParser.parse(
                    """{"project_id":"project-safe","client_email":$invalidValue,"private_key":"unused"}""",
                )
            }
            assertThat(emailError.reason)
                .isEqualTo(VertexCredentialFailure.MISSING_CLIENT_EMAIL)

            val privateKeyError = assertThrows(VertexCredentialException::class.java) {
                VertexCredentialParser.parse(
                    """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":$invalidValue}""",
                )
            }
            assertThat(privateKeyError.reason)
                .isEqualTo(VertexCredentialFailure.MISSING_PRIVATE_KEY)

            val fixture = validCredentialJson()
            val malformedProject = fixture.json.replace(
                "\"project_id\":\"project-safe\"",
                "\"project_id\":$invalidValue",
            )
            val projectError = assertThrows(VertexCredentialException::class.java) {
                VertexCredentialParser.parse(malformedProject)
            }
            assertThat(projectError.reason)
                .isEqualTo(VertexCredentialFailure.MISSING_PROJECT_ID)
        }
    }

    @Test
    fun `标准 Google PEM 末尾换行可解析`() {
        val fixture = validCredentialJson(trailingPemNewline = true)

        val parsed = VertexCredentialParser.parse(fixture.json)

        assertThat(parsed.projectId).isEqualTo("project-safe")
        assertThat(parsed.clientEmail).isEqualTo("service@example.invalid")
    }

    private fun validCredentialJson(trailingPemNewline: Boolean = false): CredentialFixture {
        val encoded = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(encoded) +
            "\n-----END PRIVATE KEY-----" + if (trailingPemNewline) "\n" else ""
        encoded.fill(0)
        val json = """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":${jsonString(pem)}}"""
        return CredentialFixture(json, pem)
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private data class CredentialFixture(val json: String, val privateKeyPem: String)
}
