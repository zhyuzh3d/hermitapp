# HermitApp

Hermit 是面向个人工具与自托管页面的 Android Web App 宿主。一个 APK 可以保存多个在线页面或本地静态应用；每个实例都有稳定 ID、独立 WebView Profile、独立业务数据、逐应用敏感能力授权和本地代码版本。更新页面代码不会清空 Hermit 管理的数据。

正式包名为 `io.github.zhyuzh3d.hermit`，最低支持 Android 12 / API 31，compileSdk 与 targetSdk 为 37。页面 Runtime 还要求系统 WebView 支持 Multi-Profile、WebMessageListener 和 document-start script；不满足时 Hermit 会进入原生恢复界面，而不会退化成共享浏览数据的运行模式。

## 使用

从应用库可添加 HTTP(S) 在线地址，或导入 ZIP、SAF 目录快照、HTTPS ZIP、公开 GitHub 仓库目录。ZIP 根目录必须有 `index.html`；可选 `hermit.json` 的格式见 `api/hermit.schema.json`。目录导入后使用 Hermit 私有快照，原目录删除不会影响已安装实例。

应用库提供启动、重命名、在线地址修改、桌面快捷方式、来源更新、代码回退、逐应用授权重置、备份/恢复、短期开发连接和删除。备份包含 Hermit 记录、逻辑文件、配置和当前本地代码，但不加密，也不包含 Cookie、WebStorage、系统权限、页面授权或开发令牌。

页面通过注入的 `window.hermit` 使用数据、文件、TTS、语音识别、前台定位、系统拍照、分享、剪贴板、震动和受控 Native HTTP。协议与类型位于 `api/` 和 `sdk/`。麦克风、定位、剪贴板读取、拍照和 Native 网络等敏感动作遵守 Hermit 逐实例授权；其中需要 Android runtime permission 的动作还必须同时取得系统授权。

## 开发部署

先导入一个本地实例，在其卡片选择“开发连接”或“局域网部署”，再从面板取得 appId、短期 token、地址以及 LAN 模式的 SPKI pin。ADB 模式示例：

```sh
./scripts/pack.sh examples/voice-notes /tmp/voice-notes.zip
export HERMIT_TOKEN='从 Hermit 面板复制的短期令牌'
./scripts/deploy.sh '<appId>' /tmp/voice-notes.zip
```

LAN 模式还需按面板设置 `HERMIT_ADDRESS` 与 `HERMIT_SPKI_PIN`。脚本强制 HTTPS 和 SPKI 固定，不允许仅用 `-k` 信任自签名端点。令牌只驻留内存、只绑定一个实例，在 Hermit 退到后台或闲置 15 分钟后失效。

## 构建与验证

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

正式签名脚本从仓库外读取密钥与口令；密钥、token、`local.properties` 和构建目录不会进入 Git。最终交付物、摘要、证书指纹、验证报告和已知限制位于 `artifacts/v1.0.0/`。实体手机验收应按 `docs/validation/physical-device-checklist.md` 执行。

产品与架构合同见 `docs/hermitapp-product-technical-design.md`，开发阶段和证据见 `plans/hermitapp-v1-development-plan.md` 与 `plans/hermitapp-v1-execution-log.md`，隐私与数据边界见 `docs/privacy-and-data.md`。
