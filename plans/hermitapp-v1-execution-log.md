# HermitApp v1 执行记录

记录日期：2026-09-11。本记录只把实际代码、命令退出状态和设备结果视为证据；“已实现”与“实体手机已验收”严格分开。

| 阶段 | 工程状态 | 已有证据与剩余门禁 |
| --- | --- | --- |
| S0 工具链与合同 | 已完成 | JDK 17.0.14、Gradle 9.3.1、AGP 9.1.1、API/Build Tools 37、Node 22.22.0；Wrapper 摘要、依赖锁与校验元数据、schema/SDK/构建脚本均已固化 |
| S1 Runtime 与在线主链 | 自动化完成 | exact-origin WebMessage、document handshake、独立 Profile、本地网关和原生恢复页完成；API 37 Store/Bridge 启动通过，API 31 + WebView 91 正确进入不兼容恢复页 |
| S2 本地版本与数据 | 自动化完成 | ZIP/目录快照、Operation/事务、release lease、CAS/batch、逻辑文件、故障恢复完成；安装穿越、失败传输、提交恢复、代码更新后数据保持等测试通过 |
| S3 授权与原生能力 | 实现完成，待实机系统交互 | 双层授权与系统观测、TTS/语音/定位/拍照/分享/剪贴板/震动/Native HTTP 完成；SSRF、地址类别授权、IPv4-mapped IPv6 等边界已自动化，实际麦克风、定位、相机和厂商服务仍需实体手机 |
| S4 开发部署 | 自动化完成，待真实 Wi-Fi | 单目标 ADB、LAN TLS、短期 token、SPKI pin、CAS/幂等/摘要、限流、后台与闲置停服完成；loopback 更新及 AndroidKeyStore TLS 测试通过，真实 Wi-Fi 与错误 pin 的端到端客户端体验待实机 |
| S5 来源与备份 | 自动化完成 | HTTPS/GitHub 来源、代码回退、逻辑备份摘要、新实例恢复和覆盖数据恢复完成；导出持有代码 lease 并停止同实例开发会话，备份恢复代码/记录/文件 ID 测试通过 |
| S6 产品整合 | 自动化完成，待实机体验验收 | 应用库、搜索、详情、来源、版本、授权、备份、开发入口、诊断、许可页、统一图标和语音笔记示例完成；深浅色、TalkBack、输入法、Launcher/OEM 行为保留给实体手机清单 |
| S7 签名交付 | 自动化完成，待用户实机验收 | 仓库外 RSA-4096 正式密钥；0.9.0/code 1 → 1.0.0/code 2 同签名覆盖升级成功；Android firstInstallTime、实例 ID、dataGeneration 与真实 Bridge 记录保持；最终目录含 APK、摘要、manifest、SBOM、许可证、验证报告和限制 |

## 已执行的发布门禁

- `scripts/doctor.sh` 通过；Node 安装审计为 0 vulnerability。
- `npm run check`、`npm test`、`npm run build` 通过；3/3 Web/API 合同测试通过。
- `testDebugUnitTest` 通过 1/1；debug/release lint 均为 0 error。保留的 26 个 warning 是 19 个 Kotlin KTX 风格建议和 7 个锁定依赖/工具版本提示，不是安全或正确性错误。
- Android 17 / API 37 ARM64 AVD 上最终 instrumentation 为 19/19，通过 Store/Bridge、安装事务、数据、文件、备份、来源身份、部署认证/TLS、本地网关和 Native HTTP 负面测试。
- Android 12 / API 31 ARM64 AVD 上先前执行的 12 项持久化/安装/部署核心测试与 1 项宿主启动测试通过；镜像 WebView 91 缺少必需 Runtime feature，应用展示原生恢复工具而没有退化成共享 Profile。
- 0.9.0 release 测试包实际加载在线夹具并经 `window.hermit.data.put` 写入 `created-by-0.9.0`；`adb install -r` 升级到 1.0.0 后记录值、revision、实例 ID 与 dataGeneration 均未变化，release 冷启动无应用 FATAL logcat。

## 当前结论

源码、自动化、签名升级和交付链已经完成，产物达到“可交给用户做实体手机验收”的 release-candidate 状态。它还不能被科学地标记为“商用环境验收完成”：相机、麦克风、定位、系统分享、Launcher、真实 Wi-Fi LAN、TalkBack 和厂商 WebView/OEM 差异必须由至少一台目标实体手机按清单走完。实体手机若出现崩溃、数据丢失、越权或主链阻断，应回到本记录并重新打开相应阶段，而不是忽略失败直接发布。
