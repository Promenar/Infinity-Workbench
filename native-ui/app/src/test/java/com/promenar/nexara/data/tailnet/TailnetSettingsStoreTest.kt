package com.promenar.nexara.data.tailnet

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.backup.BackupPreferencePolicy
import com.promenar.nexara.data.security.SecretCatalog
import org.junit.Test

class TailnetSettingsStoreTest {
    private val preferences = MemoryPreferences()
    private val secrets = MemorySecretStore()
    private val store = TailnetSettingsStore(preferences, secrets)

    @Test
    fun `设置持久化并重新读取`() {
        store.setEnabled(true)
        store.setEphemeral(true)
        assertThat(store.setControlUrl(" https://hs.example.org:8443/ ")).isTrue()
        assertThat(store.setAllowedPeers(listOf("NAS", "nas", "100.64.0.3"))).isTrue()
        val reloaded = TailnetSettingsStore(preferences, secrets).current
        assertThat(reloaded.enabled).isTrue()
        assertThat(reloaded.ephemeral).isTrue()
        assertThat(reloaded.controlUrl).isEqualTo("https://hs.example.org:8443")
        assertThat(reloaded.allowedPeers).containsExactly("nas", "100.64.0.3").inOrder()
    }

    @Test
    fun `auth key 只写入秘密存储，不进偏好文件与设置快照`() {
        assertThat(store.setAuthKey("  tskey-auth-SECRETVALUE  ".toCharArray())).isTrue()
        assertThat(String(secrets.get(TailnetSettingsStore.AUTH_KEY_ID)!!)).isEqualTo("tskey-auth-SECRETVALUE")
        assertThat(preferences.values.values.joinToString()).doesNotContain("SECRETVALUE")
        assertThat(store.current.toString()).doesNotContain("SECRETVALUE")
        assertThat(store.current.hasAuthKey).isTrue()
        val revision = store.current.authKeyRevision
        assertThat(String(store.readAuthKey()!!)).isEqualTo("tskey-auth-SECRETVALUE")
        assertThat(store.setAuthKey("with space".toCharArray())).isFalse()
        assertThat(store.setAuthKey("".toCharArray())).isFalse()
        assertThat(store.setAuthKey("x".repeat(TailnetSettingsPolicy.MAX_AUTH_KEY_LENGTH + 1).toCharArray())).isFalse()
        assertThat(store.current.authKeyRevision).isEqualTo(revision)
        store.clearAuthKey()
        assertThat(store.current.hasAuthKey).isFalse()
        assertThat(store.current.authKeyRevision).isEqualTo(revision + 1)
        assertThat(store.readAuthKey()).isNull()
    }

    @Test
    fun `设置与 auth key 都不属于备份范围`() {
        for (key in listOf(
            TailnetSettingsStore.KEY_ENABLED,
            TailnetSettingsStore.KEY_CONTROL_URL,
            TailnetSettingsStore.KEY_EPHEMERAL,
            TailnetSettingsStore.KEY_ALLOWED_PEERS,
        )) {
            assertThat(BackupPreferencePolicy.isAllowed(TailnetSettingsStore.PREFERENCES_NAME, key)).isFalse()
            assertThat(BackupPreferencePolicy.isAllowed("nexara_settings", key)).isFalse()
        }
        assertThat(SecretCatalog.backupEligible(listOf("tailnet", "openai"))).doesNotContain(TailnetSettingsStore.AUTH_KEY_ID)
    }

    @Test
    fun `控制服务器地址校验`() {
        assertThat(TailnetSettingsPolicy.normalizeControlUrl("")).isEqualTo("")
        assertThat(TailnetSettingsPolicy.normalizeControlUrl("https://controlplane.tailscale.com")).isEqualTo("https://controlplane.tailscale.com")
        assertThat(TailnetSettingsPolicy.normalizeControlUrl("http://100.64.0.1:8080")).isEqualTo("http://100.64.0.1:8080")
        for (invalid in listOf("ftp://hs.example.org", "https://u:p@hs.example.org", "https://hs.example.org/?k=v", "https://hs.example.org/#x", "hs.example.org", "https://", "https://a b")) {
            assertThat(TailnetSettingsPolicy.normalizeControlUrl(invalid)).isNull()
        }
        assertThat(store.setControlUrl("ftp://hs.example.org")).isFalse()
        assertThat(store.current.controlUrl).isEmpty()
    }

    @Test
    fun `白名单只接受节点名、MagicDNS 名称与 tailnet IP`() {
        assertThat(TailnetSettingsPolicy.parseAllowedPeers(listOf("nas", "nas.tail1.ts.net", "100.64.0.3", "fd7a:115c:a1e0::3")))
            .containsExactly("nas", "nas.tail1.ts.net", "100.64.0.3", "fd7a:115c:a1e0::3").inOrder()
        for (invalid in listOf("192.168.1.1", "-bad", "a_b", "nas..ts.net", "x".repeat(64))) {
            assertThat(TailnetSettingsPolicy.parseAllowedPeers(listOf(invalid))).isNull()
        }
        assertThat(TailnetSettingsPolicy.problemForPeers("")).isNull()
        assertThat(TailnetSettingsPolicy.problemForPeers("nas laptop\n100.64.0.9,")).isNull()
        assertThat(TailnetSettingsPolicy.problemForPeers("8.8.8.8")).isEqualTo(TailnetSettingsProblem.PEER_INVALID)
        assertThat(TailnetSettingsPolicy.problemForPeers((0..64).joinToString(",") { "n$it" })).isEqualTo(TailnetSettingsProblem.TOO_MANY_PEERS)
        assertThat(store.setAllowedPeers(listOf("8.8.8.8"))).isFalse()
        assertThat(store.current.allowedPeers).isEmpty()
    }
}
