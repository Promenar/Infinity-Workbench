package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SystemCaBundleTest {
    private lateinit var work: File

    @Before fun setUp() { work = Files.createTempDirectory("ca-bundle").toFile() }
    @After fun tearDown() { work.deleteRecursively() }

    private fun pem(body: String) = "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"

    @Test
    fun `只拼接证书块并在证书目录变化时重建`() {
        val source = File(work, "cacerts").apply { mkdirs() }
        // Android 证书文件在 PEM 块后附有可读文本与指纹，bundle 只保留 PEM 块。
        File(source, "aaaa.0").writeText(pem("QUFB") + "\nCertificate:\n    Data: ...\nSHA1 Fingerprint=00\n")
        File(source, "bbbb.0").writeText(pem("QkJC"))
        val bundle = SystemCaBundle(File(work, "out"), listOf(File(work, "missing"), source))

        val first = checkNotNull(bundle.ensure())
        assertThat(first.readText()).isEqualTo(pem("QUFB") + "\n" + pem("QkJC") + "\n")
        assertThat(bundle.ensure()).isEqualTo(first)

        File(source, "cccc.0").writeText(pem("Q0ND"))
        val second = checkNotNull(bundle.ensure())
        assertThat(second).isNotEqualTo(first)
        assertThat(first.exists()).isFalse()
        assertThat(second.readText()).contains("Q0ND")
    }

    @Test
    fun `没有系统证书目录时返回 null`() {
        assertThat(SystemCaBundle(File(work, "out"), listOf(File(work, "missing"))).ensure()).isNull()
    }
}
