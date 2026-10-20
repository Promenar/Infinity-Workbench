//go:build linux

package main

// Android App 沙箱禁止 netlink：androidbin 合成网卡信息，经 androiddns 让 net.DefaultResolver 走系统 dnsproxyd
// （直连目标因此使用 Android 系统 DNS，含私人 DNS 设置），并为静态 linux 构建（x86_64）指向系统证书目录。
// 构建约束 linux 同时覆盖 GOOS=android；在普通 Linux 上该功能不生效。
import _ "tailscale.com/feature/androidbin"
