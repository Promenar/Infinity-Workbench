#!/bin/bash
# 修复 WSL USBIPD 驱动加载问题
# 自动加载 vhci-hcd 模块以支持 USB 设备挂载
# 对应任务: 修复 WSL USBIPD 挂载故障

echo "🔍 [Nexara] 正在检查 vhci_hcd 模块状态..."

# 检查当前内核
KERNEL_VER=$(uname -r)
echo "ℹ️ 当前内核: $KERNEL_VER"

if lsmod | grep -q vhci_hcd; then
    echo "✅ 模块早已加载 (Module already loaded)"
    exit 0
fi

echo "⚠️ 模块未加载，需要 Root 权限加载..."
echo "👉 请输入 sudo 密码以执行: modprobe vhci-hcd"

if sudo modprobe vhci-hcd; then
    echo "✅ vhci_hcd 模块加载成功！"
    echo "✨ 现在请重新运行 ADB Bridge 工具。"
else
    echo "❌ 加载失败，请检查 sudo 密码或内核完整性。"
    echo "路径检查: $(find /lib/modules/"$KERNEL_VER" -name '*vhci*')"
    exit 1
fi
