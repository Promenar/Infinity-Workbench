package com.promenar.nexara.data.tailnet

import android.content.SharedPreferences
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** 内置 Tailscale 设置快照；auth key 只记录是否存在与修订号，值留在 [SecretStore]。 */
data class TailnetSettings(
    val enabled: Boolean = false,
    /** 空表示 Tailscale 官方控制服务器。 */
    val controlUrl: String = "",
    val ephemeral: Boolean = false,
    /** 设备白名单；为空表示允许全部 tailnet 节点。 */
    val allowedPeers: List<String> = emptyList(),
    val hasAuthKey: Boolean = false,
    /** auth key 每次写入或删除递增（只在内存中），用于判断是否需要重启节点。 */
    val authKeyRevision: Int = 0,
)

/** 设置校验结果码；界面按码显示文案。 */
enum class TailnetSettingsProblem { CONTROL_URL_INVALID, AUTH_KEY_INVALID, PEER_INVALID, TOO_MANY_PEERS }

object TailnetSettingsPolicy {
    const val MAX_AUTH_KEY_LENGTH = 512
    const val MAX_ALLOWED_PEERS = 64
    private val PEER_NAME = Regex("^[a-z0-9]([a-z0-9-]{0,62})(\\.[a-z0-9]([a-z0-9-]{0,62}))*$")

    /** 空串合法（官方控制服务器）；否则必须是不含凭据、查询与片段的 http(s) URL。 */
    fun normalizeControlUrl(raw: String): String? {
        val value = raw.trim().removeSuffix("/")
        if (value.isEmpty()) return ""
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (value.any { it.isWhitespace() || it.isISOControl() }) return null
        return value
    }

    fun validAuthKey(raw: String): Boolean {
        val value = raw.trim()
        return value.isNotEmpty() && value.length <= MAX_AUTH_KEY_LENGTH && value.none { it.isWhitespace() || it.isISOControl() }
    }

    /** 把白名单输入（逗号、空白或换行分隔）拆成条目。 */
    fun splitPeers(raw: String): List<String> = raw.split(',', '\n', ' ', '\t', '\r').filter { it.isNotBlank() }

    /** 条目为节点短名、MagicDNS 全名或 tailnet IP；规范化并去重。非法或超过上限时返回 null。 */
    fun parseAllowedPeers(entries: List<String>): List<String>? {
        val result = linkedSetOf<String>()
        for (entry in entries) {
            val value = TailnetHosts.normalize(entry)
            if (value.isEmpty()) continue
            val literal = TailnetHosts.parseAddressLiteral(value)
            if (literal != null) {
                if (!TailnetHosts.isTailnetAddress(literal)) return null
            } else if (value.length > 253 || !PEER_NAME.matches(value)) {
                return null
            }
            result += value
        }
        return result.toList().takeIf { it.size <= MAX_ALLOWED_PEERS }
    }

    fun problemForPeers(raw: String): TailnetSettingsProblem? {
        val entries = splitPeers(raw)
        return when {
            entries.size > MAX_ALLOWED_PEERS -> TailnetSettingsProblem.TOO_MANY_PEERS
            parseAllowedPeers(entries) == null -> TailnetSettingsProblem.PEER_INVALID
            else -> null
        }
    }
}

/**
 * 设置存储：普通设置写入独立的 `nexara_tailnet` 偏好文件（不在 App 备份的命名空间内，系统备份已关闭）；
 * auth key 写入 [SecretStore]（不属于可备份秘密），不进入快照、日志与模型上下文。
 * 调用方在 Dispatchers.IO 上调用写入方法。
 */
class TailnetSettingsStore(
    private val preferences: SharedPreferences,
    private val secrets: SecretStore,
) {
    private val mutable = MutableStateFlow(read(revision = 0))
    val settings: StateFlow<TailnetSettings> = mutable.asStateFlow()

    val current: TailnetSettings get() = mutable.value

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        refresh()
    }

    @Synchronized
    fun setEphemeral(ephemeral: Boolean) {
        preferences.edit().putBoolean(KEY_EPHEMERAL, ephemeral).apply()
        refresh()
    }

    /** 返回 false 表示 URL 不合法，未保存。 */
    @Synchronized
    fun setControlUrl(raw: String): Boolean {
        val value = TailnetSettingsPolicy.normalizeControlUrl(raw) ?: return false
        preferences.edit().putString(KEY_CONTROL_URL, value).apply()
        refresh()
        return true
    }

    @Synchronized
    fun setAllowedPeers(entries: List<String>): Boolean {
        val parsed = TailnetSettingsPolicy.parseAllowedPeers(entries) ?: return false
        preferences.edit().putString(KEY_ALLOWED_PEERS, parsed.joinToString("\n")).apply()
        refresh()
        return true
    }

    /** 返回 false 表示格式不合法，未保存。 */
    @Synchronized
    fun setAuthKey(raw: CharArray): Boolean {
        val value = String(raw).trim()
        if (!TailnetSettingsPolicy.validAuthKey(value)) return false
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            secrets.put(AUTH_KEY_ID, bytes)
        } finally {
            bytes.fill(0)
        }
        refresh(bumpRevision = true)
        return true
    }

    @Synchronized
    fun clearAuthKey() {
        secrets.remove(AUTH_KEY_ID)
        refresh(bumpRevision = true)
    }

    /** 只在拼装 start 请求与设置页查看时读取。 */
    fun readAuthKey(): CharArray? {
        val bytes = secrets.get(AUTH_KEY_ID) ?: return null
        return try {
            String(bytes, Charsets.UTF_8).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    private fun refresh(bumpRevision: Boolean = false) {
        val revision = mutable.value.authKeyRevision + if (bumpRevision) 1 else 0
        mutable.value = read(revision)
    }

    private fun read(revision: Int): TailnetSettings = TailnetSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        controlUrl = preferences.getString(KEY_CONTROL_URL, null).orEmpty(),
        ephemeral = preferences.getBoolean(KEY_EPHEMERAL, false),
        allowedPeers = preferences.getString(KEY_ALLOWED_PEERS, null).orEmpty().split('\n').filter { it.isNotBlank() },
        hasAuthKey = secrets.contains(AUTH_KEY_ID),
        authKeyRevision = revision,
    )

    companion object {
        /** 独立偏好文件：不属于备份命名空间（见 BackupPreferencePolicy）。 */
        const val PREFERENCES_NAME = "nexara_tailnet"
        const val KEY_ENABLED = "tailnet_enabled"
        const val KEY_CONTROL_URL = "tailnet_control_url"
        const val KEY_EPHEMERAL = "tailnet_ephemeral"
        const val KEY_ALLOWED_PEERS = "tailnet_allowed_peers"
        val AUTH_KEY_ID = SecretId("tailnet_auth_key_v1")
    }
}
