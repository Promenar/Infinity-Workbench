package com.promenar.nexara.domain.network

import kotlinx.coroutines.flow.StateFlow

/** Agent 网络目标的分类；同一主机按解析结果与 tailnet 路由归入其中一类。 */
enum class NetworkTargetClass { PUBLIC, LAN, LOOPBACK, TAILNET }

/**
 * 出现在网络访问总表中的工具族。前四项由 Nexara 在 App 内判定；
 * 内置进程、Termux 与 SSH 只做展示，它们的网络不经 App 内判定。
 */
enum class AgentNetworkTool { WEB_FETCH, MEDIA_FETCH, SCRIPT, MCP, BUILTIN_PROCESS, TERMUX, SSH }

enum class NetworkAccessState {
    /** 允许；脚本仍需每次调用声明 allow_network。 */
    ALLOWED,
    /** 只允许共享白名单中的精确主机。 */
    ALLOWLIST_ONLY,
    /** 需要内置 Tailscale 已启用；可达节点再受 Tailscale 设备白名单约束。 */
    TAILNET_REQUIRED,
    /** 只连接用户已配置的端点或主机（MCP 服务器、SSH 主机别名）。 */
    CONFIGURED_ONLY,
    /** App 内不做限制，由系统网络与对端决定。 */
    UNRESTRICTED,
    DENIED,
}

data class NetworkAccessCell(val target: NetworkTargetClass, val state: NetworkAccessState)

/** 每行按 [NetworkTargetClass] 声明顺序给出四个单元格。 */
data class NetworkAccessRow(val tool: AgentNetworkTool, val cells: List<NetworkAccessCell>)

data class AgentNetworkSnapshot(
    /** 共享的局域网与本机主机白名单（规范化后排序）。 */
    val lanHosts: List<String>,
    /** 安装包是否包含内置 Tailscale。 */
    val tailnetAvailable: Boolean,
    val tailnetEnabled: Boolean,
    val tailnetRunning: Boolean,
    /** Tailscale 设备白名单；为空表示允许全部节点。 */
    val tailnetPeerAllowlist: List<String>,
    /** 按 [AgentNetworkTool] 声明顺序给出全部工具族。 */
    val rows: List<NetworkAccessRow>,
)

/** 界面读取网络访问总表并编辑共享白名单的唯一入口；判定逻辑与工具执行共用同一份规则。 */
interface AgentNetworkAccess {
    val snapshot: StateFlow<AgentNetworkSnapshot>

    /** 解析并保存白名单（逗号、空白或换行分隔）；输入无效时抛出 [IllegalArgumentException]，消息可直接展示。 */
    fun setLanHosts(text: String)
}
