#!/bin/bash

# Android SDK Path Configuration
export ANDROID_HOME=/usr/local/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

echo "🚀 Nexara Dev Proxy (Watcher Mode) - 持续监控中..."
echo "💡 提示：该脚本将每 5 秒自动检查并同步所有在线设备的端口转发。"
echo "🛑 按 Ctrl+C 停止监控"
echo "------------------------------------"

PREV_DEVICES=""

while true; do
    # Get currently connected devices
    CURRENT_DEVICES=$(adb devices | grep -v "List" | grep "device" | awk '{print $1}')
    
    if [ ! -z "$CURRENT_DEVICES" ]; then
        # If device list changed or was empty
        if [ "$CURRENT_DEVICES" != "$PREV_DEVICES" ]; then
            echo "$(date '+%H:%M:%S') 📲 检测到设备变动: $CURRENT_DEVICES"
            for DEV in $CURRENT_DEVICES; do
                adb -s $DEV reverse tcp:8081 tcp:8081 > /dev/null 2>&1
                adb -s $DEV reverse tcp:8082 tcp:8082 > /dev/null 2>&1
                adb -s $DEV reverse tcp:8097 tcp:8097 > /dev/null 2>&1
                echo "   ✅ 已为 [$DEV] 建立/刷新转发 (8081, 8082, 8097)"
            done
            PREV_DEVICES=$CURRENT_DEVICES
        else
            # Quietly re-apply every loop to ensure mapping hasn't dropped
            # but don't spam the console
            for DEV in $CURRENT_DEVICES; do
                adb -s $DEV reverse tcp:8081 tcp:8081 > /dev/null 2>&1
                adb -s $DEV reverse tcp:8082 tcp:8082 > /dev/null 2>&1
                adb -s $DEV reverse tcp:8097 tcp:8097 > /dev/null 2>&1
            done
        fi
    else
        if [ "$PREV_DEVICES" != "" ]; then
             echo "$(date '+%H:%M:%S') ⚠️  连接断开，正在等待设备重连..."
             PREV_DEVICES=""
        fi
    fi
    
    sleep 5
done
