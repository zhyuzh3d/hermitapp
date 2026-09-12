# HermitApp

1.3 的底部导航为“收藏、全部、开发、设置、支持”。收藏与全部共享应用库，支持爱心筛选、新增和二维码预填；开发页用开关控制智能体模式，并在运行时显示绿色状态点；设置集中宿主配置与软件信息；支持页在联网预检后打开官方支持页面，失败时显示本地重试页。

智能体开发模式位于“开发”Tab。将局域网地址和六位密码交给可信智能体，即可使用动态 MCP 工具创建、更新、打开、刷新和回滚页面。多台电脑共用一个持久密码，无需配对；修改后旧密码立即失效。保持 Hermit 在前台，仅使用可信局域网（HTTP 不加密）。[实施与验收计划](plans/hermitapp-agent-development-plan.md) · [实时指导源文件](app/src/main/assets/agent/hermit-device/SKILL.md)。

Hermit 项目包含 Android 宿主 HermitApp 与官网/HermitUI 所在的 HermitWeb。HermitApp 可以加载多个 happ；HermitUI 是默认加载且具有宿主管理权限的官方 happ。每个 happ 实例都有稳定 ID、独立的 Hermit 业务数据、逐应用敏感能力授权和可选本地代码版本。

“本地 happ / 线上 happ”描述来源，“本地运行 / 线上实时运行”描述当前执行方式，两者不能混用。手机文件或目录导入的是本地 happ 且只能本地运行；URL、HTTPS 包和 GitHub 是线上来源，即使代码已下载到手机仍是线上 happ。添加页面 URL 时会探测同源 `/hermit-install.json`：有有效 ZIP 时默认本地运行，没有时实时运行；同时具备本地代码和页面 URL 的线上 happ 可在管理设置中切换。网页 Cookie 与站点存储继续遵守标准同源规则。

正式包名为 `io.github.zhyuzh3d.hermit`，最低支持 Android 10 / API 29，compileSdk 与 targetSdk 为 37。只要设备存在可创建的系统 WebView，Hermit 就尝试运行页面：优先使用可校验 Origin/主 frame 的 WebMessage 通道，旧 provider 则使用传统 `JavascriptInterface`。两者都使用共享资料空间；传统桥接无法隔离 iframe 调用，界面与诊断会如实显示。

核心功能按国内无 GMS 手机设计：二维码由 APK 内置 CameraX + ZXing 离线识别，字体图标全部内置，本地应用、数据、备份和局域网开发均不依赖 Google Play 服务、海外 CDN 或运行时下载。GitHub 仅是海外可选来源；本地 ZIP/目录、局域网推送和通用 HTTPS 包不依赖 GitHub。

## 使用

页面开发的关键原则是 **纯原生 HTML + JavaScript + CSS，源文件即可运行，无需构建**。不要求 React/Vue、Vite/Webpack、Node/npm 或任何转译、打包步骤；Hermit 不为它们提供专门适配。第三方工具已生成的静态产物仍可作为普通 WebApp 导入，遵循相同规则。详细说明及可直接复制的示例见 [WebApp 编写指南](docs/webapp-authoring.md)。

从应用库可添加 HTTP(S) 页面地址，或导入 ZIP、SAF 目录快照、HTTPS ZIP；公开 GitHub 仓库目录是网络可达时的可选适配器。线上安装清单格式见 `api/hermit-install.schema.json`；ZIP 内元信息格式见 `api/hermit.schema.json`。目录导入后使用 Hermit 私有快照，原目录删除不会影响已安装实例。

应用库提供收藏、搜索、扫码预填、启动、重命名、在线地址修改、桌面快捷方式、来源更新、代码回退、逐应用授权重置、备份/恢复、短期开发连接和删除。备份包含 Hermit 记录、逻辑文件、配置和当前本地代码，但不加密，也不包含 Cookie、WebStorage、系统权限、页面授权或开发令牌。

页面通过注入的 `window.hermit` 使用数据、文件、原始录音/音频播放、TTS、语音识别、前台定位、系统拍照、分享、剪贴板、震动和受控 Native HTTP。协议与类型位于 `api/` 和 `sdk/`。麦克风、定位、剪贴板读取、拍照和 Native 网络等敏感动作遵守 Hermit 逐实例授权；其中需要 Android runtime permission 的动作还必须同时取得系统授权。页面先用 `runtime.capabilities()` 查询当前设备事实：无对应硬件或系统服务时明确得到不支持，不会连接云端补齐或返回模拟成功。

## 开发部署

先导入一个原生页面目录，在其卡片选择“管理 → 开发与更新 → USB 开发连接 / 局域网部署”，再从面板取得 appId、短期 token、地址以及 LAN 模式的 SPKI pin。直接编辑 HTML/JS/CSS 后归档并推送，无需前端编译。ADB 模式示例：

```sh
./scripts/pack.sh examples/voice-notes /tmp/voice-notes.zip
export HERMIT_TOKEN='从 Hermit 面板复制的短期令牌'
./scripts/deploy.sh '<appId>' /tmp/voice-notes.zip
```

LAN 模式还需按面板设置 `HERMIT_ADDRESS` 与 `HERMIT_SPKI_PIN`。脚本强制 HTTPS 和 SPKI 固定，不允许仅用 `-k` 信任自签名端点。令牌只驻留内存、只绑定一个实例，在 Hermit 退到后台或闲置 15 分钟后失效。

## 构建与验证

以下命令供 Hermit 宿主 APK 的维护者使用，不是 WebApp 作者的前置步骤。应用库自身也是未经编译的原生 HTML/JS/CSS；`npm run build` 只检查内置文件完整性，不进行前端打包。Font Awesome Free 7.3.1 的完整 Web 字体与 CSS 已提交并随 APK 离线分发；仅在更新上游资源时运行 `npm ci && npm run vendor:icons`。

本项目锁定 JDK 17、AGP 9.1.1、Gradle 9.3.1 和依赖校验元数据。macOS 本机验证命令：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./scripts/doctor.sh
npm ci
npm run check && npm test && npm run build
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:lintRelease
./gradlew --no-daemon :app:assembleDebug :app:connectedDebugAndroidTest
```

正式签名脚本从仓库外读取密钥与口令；密钥、token、`local.properties` 和构建目录不会进入 Git。各版本交付物位于 `artifacts/v<版本>/`。实体手机验收应按 `docs/validation/physical-device-checklist.md` 执行。

产品与架构合同见 `docs/hermitapp-product-technical-design.md`，开发阶段和证据见 `plans/hermitapp-v1-development-plan.md` 与 `plans/hermitapp-v1-execution-log.md`，隐私与数据边界见 `docs/privacy-and-data.md`。
