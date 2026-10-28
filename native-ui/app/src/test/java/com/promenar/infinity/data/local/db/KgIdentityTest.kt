package com.promenar.infinity.data.local.db

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KgIdentityTest {
    @Test fun `完整大小写折叠统一希腊下标iota而保持土耳其dotless差异`() {
        assertThat(KgIdentity.normalize("ᾀ")).isEqualTo(KgIdentity.normalize("ἀι"))
        assertThat(KgIdentity.normalize("\u0345")).isEqualTo(KgIdentity.normalize("ι"))
        assertThat(KgIdentity.normalize("ı")).isNotEqualTo(KgIdentity.normalize("I"))
        assertThat(KgIdentity.normalize("Straße")).isEqualTo(KgIdentity.normalize("STRASSE"))
    }
    @Test fun `完整折叠差异黄金样本与JVM实现一致`() {
        val rows = java.io.File("app/src/test/resources/kg/casefold-v13.tsv").readLines().filterNot { it.startsWith("#") || it.isBlank() }
        fun decode(value: String) = value.split(',').joinToString("") { String(Character.toChars(it.toInt(16))) }
        rows.forEach { row ->
            val (input, expected) = row.split('\t')
            com.google.common.truth.Truth.assertWithMessage("Unicode U+$input").that(KgIdentity.normalize(decode(input))).isEqualTo(decode(expected))
        }
    }
}
