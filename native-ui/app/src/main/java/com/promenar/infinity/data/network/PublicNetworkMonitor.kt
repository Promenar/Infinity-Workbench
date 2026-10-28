package com.promenar.infinity.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 手机当前网络能否访问互联网；模型选择器的“公网”指示灯据此显示。 */
enum class PublicNetworkStatus {
    /** 系统已确认可以访问互联网。 */
    CONNECTED,
    /** 连着网络，但系统未确认能访问互联网（如需网页认证）。 */
    LIMITED,
    /** 没有可用网络。 */
    OFFLINE,
}

/** 由默认网络的能力推出公网状态；[capabilities] 为 null 表示没有默认网络。 */
internal fun publicNetworkStatusOf(capabilities: NetworkCapabilitiesView?): PublicNetworkStatus = when {
    capabilities == null || !capabilities.internet -> PublicNetworkStatus.OFFLINE
    capabilities.validated && !capabilities.captivePortal -> PublicNetworkStatus.CONNECTED
    else -> PublicNetworkStatus.LIMITED
}

/** [NetworkCapabilities] 中与公网判断相关的部分，便于在 JVM 单元测试中构造。 */
internal data class NetworkCapabilitiesView(
    val internet: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
) {
    companion object {
        fun of(capabilities: NetworkCapabilities) = NetworkCapabilitiesView(
            internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        )
    }
}

/** 跟随系统默认网络回调更新 [status]；进程内常驻，回调开销只有网络变化时的一次计算。 */
class PublicNetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableStatus = MutableStateFlow(currentStatus())
    val status: StateFlow<PublicNetworkStatus> = mutableStatus.asStateFlow()

    init {
        connectivity?.let { manager ->
            runCatching {
                manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        mutableStatus.value = publicNetworkStatusOf(NetworkCapabilitiesView.of(networkCapabilities))
                    }

                    override fun onLost(network: Network) {
                        mutableStatus.value = currentStatus()
                    }
                })
            }
        }
    }

    private fun currentStatus(): PublicNetworkStatus {
        val manager = connectivity ?: return PublicNetworkStatus.OFFLINE
        val capabilities = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
        return publicNetworkStatusOf(capabilities?.let(NetworkCapabilitiesView::of))
    }
}
