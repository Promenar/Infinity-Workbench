#!/bin/bash
# 配置 vhci-hcd 驱动自动加载
# 路径: /etc/modules-load.d/vhci-hcd.conf
# 对应任务: 修复 WSL USBIPD 挂载故障

CONF_FILE="/etc/modules-load.d/vhci-hcd.conf"
MODULE_NAME="vhci-hcd"

echo "🛠️ [Nexara] 正在配置驱动持久化..."

# 检查权限
if [ "$EUID" -ne 0 ]; then
    echo "⚠️ 需要使用 sudo 权限运行此脚本。"
    echo "👉 命令: sudo $0"
    exit 1
fi

# 写入配置
echo "写入配置文件: $CONF_FILE"
echo "$MODULE_NAME" > "$CONF_FILE"

if [ $? -eq 0 ]; then
    echo "✅ 配置写入成功！"
    echo "✨ 以后每次 WSL 启动都会自动加载 $MODULE_NAME 驱动。"
    echo "🔍 当前状态: $(lsmod | grep $MODULE_NAME || echo '未加载 (将在下次重启后自动加载)')"
else
    echo "❌ 写入失败，请检查文件系统权限。"
    exit 1
fi
