# Hermit v1.0.0 自动化验证报告

验证日期：2026-09-11。验证对象为 `io.github.zhyuzh3d.hermit`，versionName 1.0.0、versionCode 2、minSdk 31、target/compileSdk 37。交付 APK 的准确摘要与源码 commit 以同目录的 `SHA256SUMS` 和 `release-manifest.json` 为准。

## 结论

构建、静态检查、设备自动化、正式签名与覆盖升级均通过，未发现阻断交付给实体手机测试的缺陷。当前质量等级是“签名 release candidate，等待实体手机验收”，不是已经完成公开商用发布审批。

| 门禁 | 结果 |
| --- | --- |
| 工具链自检 | 通过：JDK 17.0.14、Gradle 9.3.1、AGP 9.1.1、ADB/Build Tools/API 37 |
| npm 安装与审计 | 通过：0 vulnerability |
| Web/API 合同 | 通过：3/3 |
| JVM 单元测试 | 通过：1/1 |
| Android lint | debug/release 均 0 error；26 个非阻断 warning |
| API 37 instrumentation | 通过：19/19，0 failure、0 error、0 skipped |
| API 37 Runtime | WebView 145.0.7632.218；Store/Bridge 握手与本地实例主链通过 |
| API 31 最低基线 | APK 安装/宿主与 13 项核心验证通过；WebView 91 缺少必需 feature 时原生恢复页正确出现 |
| release 签名 | 通过 APK Signature Scheme v3；RSA-4096 证书 SHA-256 见 release manifest |
| 同签名升级 | 0.9.0/code 1 → 1.0.0/code 2 原位升级通过；firstInstallTime、实例与 Hermit 记录保持 |
| release 冷启动 | 通过；未发现 Hermit AndroidRuntime FATAL |

自动化重点覆盖：事务式本地包安装与中断清理、路径穿越拒绝、代码 release 与 data generation 分离、记录 CAS/原子 batch、逻辑文件、备份摘要和恢复、Origin 变化的信任/Profile 轮换、开发端点目标绑定与认证、临时 TLS/SPKI、网关 MIME/Range/Service Worker 抑制、Native HTTP 回环/地址类别/IPv4-mapped IPv6 防护，以及 Store 注入 Bridge 的握手。

## 尚未由自动化替代的验收

实体手机的真实麦克风/识别引擎、TTS、近似/精确定位、相机、文件选择与分享、剪贴板系统策略、Launcher 固定入口、真实 Wi-Fi LAN、深浅色/字体放大/TalkBack 和 OEM WebView 行为，必须按 `physical-device-checklist.md` 验收。任何崩溃、数据损坏、越权或升级失败都应阻止该设备组合进入支持矩阵。
