package com.promenar.nexara.data.linux

import android.content.Context
import com.promenar.nexara.R

enum class LinuxRuntimeMessage(val resourceId: Int) {
    TEMP_DIRECTORY(R.string.linux_runtime_temp_directory),
    WORKSPACE_DIRECTORY(R.string.linux_runtime_workspace_directory),
    MOUNT_TARGET(R.string.linux_runtime_mount_target),
    READ_ONLY_MOUNT(R.string.linux_runtime_read_only_mount),
    HOST_MOUNT(R.string.linux_runtime_host_mount),
    WORKING_DIRECTORY(R.string.linux_runtime_working_directory),
    ROOTFS_DAMAGED(R.string.linux_runtime_rootfs_damaged),
    STORAGE_REQUIRED(R.string.linux_runtime_storage_required),
    ASSET_CORRUPT(R.string.linux_runtime_asset_corrupt),
    SHELL_MISSING(R.string.linux_runtime_shell_missing),
    DATA_SYMLINK(R.string.linux_runtime_data_symlink),
    DNS_FALLBACK(R.string.linux_runtime_dns_fallback),
    DNS_PUBLIC(R.string.linux_runtime_dns_public),
    DNS_VPN(R.string.linux_runtime_dns_vpn),
    DNS_ACTIVE(R.string.linux_runtime_dns_active),
    ALPINE_CA(R.string.linux_runtime_alpine_ca),
    TIMEOUT(R.string.linux_runtime_timeout),
    PREFERENCE_FAILED(R.string.linux_runtime_preference_failed),
}

/** 应用装配使用资源解析器；非 Android 测试和工具只返回诊断标识与参数。 */
class LinuxRuntimeMessages(
    private val resolve: (LinuxRuntimeMessage, List<Any>) -> String = { message, arguments ->
        (listOf(message.name) + arguments).joinToString(": ")
    },
) {
    operator fun invoke(message: LinuxRuntimeMessage, vararg arguments: Any): String = resolve(message, arguments.toList())

    companion object {
        fun android(context: Context) = LinuxRuntimeMessages { message, arguments ->
            context.getString(message.resourceId, *arguments.toTypedArray())
        }
    }
}
