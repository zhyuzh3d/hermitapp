# Hermit v1 验证环境

记录日期：2026-09-11。本文只记录可复核的本地环境，不包含设备序列号、签名口令或部署 token。

| 项目 | 实际值 |
| --- | --- |
| 主机 | macOS arm64 |
| JDK | OpenJDK 17.0.14 |
| Android 构建 | AGP 9.1.1、Gradle 9.3.1、compileSdk/targetSdk 37、minSdk 31 |
| Node | Node 22.22.0、npm 10.9.4 |
| 最低系统验证 | Android 12 / API 31 ARM64 AVD；WebView 91.0.4472.114 |
| 现代环境验证 | Android 17 / API 37 ARM64 AVD；WebView 145.0.7632.218 |

API 31 镜像完成 APK 安装、冷启动、原生不兼容恢复页、12 项持久化/安装事务/来源隔离/部署 TLS 测试及 1 项宿主启动测试。镜像自带 WebView 91 不支持 Hermit 必需的 Multi-Profile 等 Runtime feature，因此没有被错误计为页面 Runtime 全功能环境。

API 37 镜像的现代 WebView 满足 Runtime feature 门槛，最终全量 instrumentation 为 19/19，并完成 0.9.0/code 1 到 1.0.0/code 2 的同签名升级与真实 Bridge 记录保留验证。机器尚未接入实体 Android 手机；相机、语音引擎、Launcher、真实 Wi-Fi LAN、厂商 WebView 和系统授权对话框必须按实体手机清单验收。

逐环境机器可读快照位于 `generated/api31.json` 与 `generated/api37.json`。它们来自 `scripts/verify-device.sh`，故意不采集 serial。
