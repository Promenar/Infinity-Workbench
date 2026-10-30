package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** 输入框密钥检测器：各格式命中、正常文本不误报、区间与替换正确。测试串均为随手拼出的假值。 */
class ComposerSecretGuardTest {
    private val body36 = "aB3dE5fG7hJ9kL1mN2pQ4rS6tU8vW0xY2zA4"
    private val classic = "ghp_$body36"
    private val fineGrained = "github_pat_11ABCDEFG0aB3dE5fG7hJ9kL_" + "mN2pQ4rS6tU8vW0xY2zA4bC5dE7fG9hJ1kL3mN5pQ7rS9tU1vW3xY5zA7bC9d"
    private val openAi = "sk-proj-Q7rS9tU1vW3xY5zA7bC9dE1fG3hJ5kL7mN9pQ1rS3t"
    private val anthropic = "sk-ant-api03-Zx8Cv7Bn6Mq5Wl4Er3Ty2Ui1Op0As9Df8Gh7Jk6"
    private val deepSeek = "sk-" + "0f3a9c7e1b5d4a8c" + "2e6f0b9d3a7c5e1f"
    private val aws = "AKIAZ4Q7R2T9K3M8P5N1"
    private val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----"

    @Test fun `测试数据长度符合格式`() {
        assertThat(body36.length).isEqualTo(36)
    }

    @Test fun `GitHub 经典令牌五种前缀都命中，区间覆盖整个令牌`() {
        listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_").forEach { prefix ->
            val text = "我的令牌是 $prefix$body36 请用它"
            val matches = detectComposerSecrets(text)
            assertThat(matches).hasSize(1)
            assertThat(matches[0].kind).isEqualTo(ComposerSecretKind.GITHUB_TOKEN)
            assertThat(text.substring(matches[0].range.first, matches[0].range.last + 1)).isEqualTo("$prefix$body36")
        }
    }

    @Test fun `细粒度令牌、模型密钥、AWS 与私钥都命中`() {
        assertThat(detectComposerSecrets("token=$fineGrained").map { it.kind }).containsExactly(ComposerSecretKind.GITHUB_FINE_GRAINED)
        assertThat(detectComposerSecrets("export OPENAI_API_KEY=$openAi").map { it.kind }).containsExactly(ComposerSecretKind.API_KEY)
        assertThat(detectComposerSecrets("key: $anthropic").map { it.kind }).containsExactly(ComposerSecretKind.API_KEY)
        assertThat(detectComposerSecrets("deepseek $deepSeek").map { it.kind }).containsExactly(ComposerSecretKind.API_KEY)
        assertThat(detectComposerSecrets("aws_access_key_id = $aws").map { it.kind }).containsExactly(ComposerSecretKind.AWS_ACCESS_KEY)
        assertThat(detectComposerSecrets("看下这个\n$pem\n能用吗").map { it.kind }).containsExactly(ComposerSecretKind.PRIVATE_KEY)
    }

    @Test fun `私钥块从 BEGIN 行到 END 行，缺 END 时到文末`() {
        val text = "前文\n$pem\n后文"
        val match = detectComposerSecrets(text).single()
        assertThat(text.substring(match.range.first, match.range.last + 1)).isEqualTo(pem)
        val truncated = "前文\n-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA"
        val open = detectComposerSecrets(truncated).single()
        assertThat(open.range.last).isEqualTo(truncated.lastIndex)
    }

    @Test fun `正常文本与占位符不误报`() {
        listOf(
            "用 sk-learn 还是 scikit-learn？",
            "sk-this-is-not-a-real-key-at-all-just-words",
            "把 ghp_xxx 换成你的令牌",
            "token = \"ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"",
            "OPENAI_API_KEY=sk-0000000000000000000000000000000000000000",
            "示例密钥 AKIAIOSFODNN7EXAMPLE 来自 AWS 文档",
            "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE",
            "这是一段普通的中文消息，没有任何密钥，长度也超过二十个字。",
            "mask${classic}suffix",
        ).forEach { text ->
            assertWithMessage(text).that(detectComposerSecrets(text)).isEmpty()
        }
    }

    @Test fun `多处命中按起点排序，嵌在私钥块里的串不重复计`() {
        val text = "$aws 和 $classic\n-----BEGIN PRIVATE KEY-----\n$openAi\n-----END PRIVATE KEY-----"
        val matches = detectComposerSecrets(text)
        assertThat(matches.map { it.kind }).containsExactly(
            ComposerSecretKind.AWS_ACCESS_KEY,
            ComposerSecretKind.GITHUB_TOKEN,
            ComposerSecretKind.PRIVATE_KEY,
        ).inOrder()
        assertThat(matches.zipWithNext().all { (a, b) -> a.range.last < b.range.first }).isTrue()
    }

    @Test fun `替换只改命中区间，其余文字保持原样`() {
        val text = "令牌 $classic 和 $aws 结束"
        val matches = detectComposerSecrets(text)
        assertThat(redactComposerSecrets(text, matches.filter { it.kind.gitHubToken }, "‹已保存›"))
            .isEqualTo("令牌 ‹已保存› 和 $aws 结束")
        assertThat(redactComposerSecrets(text, matches, "‹›")).isEqualTo("令牌 ‹› 和 ‹› 结束")
    }

    @Test fun `识别示意只露出前缀与前 4 位`() {
        val text = "x $classic"
        assertThat(composerSecretPreview(text, detectComposerSecrets(text).single())).isEqualTo("ghp_aB3d…")
        val key = "x $openAi"
        assertThat(composerSecretPreview(key, detectComposerSecrets(key).single())).isEqualTo("sk-proj-Q7rS…")
        val pemText = "x\n$pem"
        assertThat(composerSecretPreview(pemText, detectComposerSecrets(pemText).single())).isEqualTo("-----BEGIN OPENSSH PRIVATE KEY-----")
    }

    @Test fun `只有 GitHub 令牌提供保存到 Git 凭据`() {
        assertThat(ComposerSecretKind.entries.filter { it.gitHubToken })
            .containsExactly(ComposerSecretKind.GITHUB_TOKEN, ComposerSecretKind.GITHUB_FINE_GRAINED)
    }
}
