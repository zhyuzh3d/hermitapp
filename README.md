# HermitApp

HermitApp 是一个面向 Android 的 happ 容器：它直接运行 HTML、CSS、JavaScript 页面，并为页面提供安装、版本、数据、文件、权限和系统能力。项目目标不是把网页重新包装成独立 APK，而是在一个开放、轻量、可离线工作的宿主中管理多个页面应用。

当前正式版本为 `1.10.8`（versionCode `42`），包名为 `io.github.zhyuzh3d.hermit`，最低支持 Android 10 / API 29，compileSdk 与 targetSdk 为 37。系统语音识别语言目录通过 `speech.languages()` 从当前 Android 识别服务读取；系统 TTS 的可选语言和音色也来自当前引擎，不再声明未经运行时确认的候选。HermitUI 固定使用竖屏；其他 happ 仍可在 `hermit.json.display` 中独立声明竖屏、横屏或跟随设备，以及键盘布局策略。HermitUI 会在应用启动和恢复前台时读取 Android 固定快捷方式状态，使 happ 卡片图钉与桌面图标状态保持同步。开发连接可以选择并刷新当前可见的官方实时 HermitUI，但仍不能读取或修改其受保护源码；普通重启保留界面模式，APK 替换会恢复内置 UI。

Hermit 项目由两个同级独立仓库组成：

- `hermitapp/`：Android 宿主，本仓库只推送到 `git@github.com:zhyuzh3d/hermitapp.git`。
- `hermitweb/`：官网与 HermitUI，只推送到 `git@github.com:zhyuzh3d/hermitweb.git`。

HermitUI 是 HermitApp 默认加载、并拥有宿主管理权限的官方 happ。它的源码位于 HermitWeb 的 `public/shell/`；本仓库 `app/src/main/assets/store/` 只保存随 APK 分发的同步快照。

## 核心模型

Hermit 把 happ 的来源与运行方式分开描述：

- `source` 记录代码来自本地文件、HTTP(S)、Git 等何处。下载到手机不会把线上来源改成本地来源。
- `runtimeMode` 只表示当前采用本地 release，还是直接访问 `liveUrl`。
- 有本地 release 才能本地运行；有 `liveUrl` 才能线上实时运行；有 `updateUrl` 才能一键更新；有 `downloadUrl` 才能从原地址重装。

每个安装实例由 Native 生成稳定的 `instanceId`，发布包可以通过 `happId` 声明产品身份。Hermit 的业务数据、文件、授权、通知和代码版本按实例管理；Cookie、localStorage 与 IndexedDB 仍遵守浏览器的标准同源规则。

本地代码以不可变 release 保存。更新会生成新 release 后原子切换，当前版和上一版可用于回退，页面数据不会随代码切换而被覆盖。对于带 `liveUrl` 的本地 release，Hermit 会在该真实 URL 空间优先提供包内静态文件，包内不存在的资源和动态请求继续访问网络，因此相对 URL、Cookie 和请求头仍按正常网页规则工作。没有 `liveUrl` 的 happ 是纯本地应用，只访问包内资源和已授权的 Bridge 能力。

## 主要能力

应用库支持本地目录/ZIP、HTTP(S) 页面或安装包、局域网地址及公开 Git 仓库来源；提供收藏、搜索、扫码预填、桌面快捷方式、重装、更新、代码回退、备份恢复和卸载保留数据。安装包采用严格的 `hermit.json`，线上页面可通过同 Origin 的 `/hermit-install.json` 提供本地安装包。

页面通过 `window.hermit` 调用数据、文件、录音与播放、系统 TTS、语音识别、定位、运动/方向/环境传感器、拍照与闪光灯、Wi-Fi、BLE、红外、网络与电池状态、分享、剪贴板、震动及通知能力。Bridge 对 happ 暴露稳定的 Android 通用能力，不暴露也不要求页面适配手机品牌或语音服务商；系统服务发现、用户选择、失效回退和诊断由 HermitApp 处理。敏感能力需要逐 happ 授权；涉及 Android runtime permission 时，还必须同时获得系统授权。Wi-Fi 与蓝牙配置遵守 Android 的用户确认和系统设置流程，不能越过系统限制静默修改。通知支持即时通知、设备端单次/每日/每周/每月/每年计划，以及由 HermitApp 约每 15 分钟同步的服务器通知；宿主不会在后台执行 happ JavaScript。

核心链路按国内无 GMS 设备设计。二维码使用随 APK 打包的 CameraX 与 ZXing 离线识别，图标资源全部内置；本地运行、数据、备份和局域网开发不依赖 Google Play 服务、海外 CDN 或运行时下载。实际 Bridge 通道根据系统 WebView 能力选择安全 WebMessage 模式或兼容模式。

## 编写 happ

推荐目录就是可直接运行的原生静态页面：

```text
my-happ/
  hermit.json
  index.html
  app.js
  style.css
```

不要求 React、Vue、Vite、Webpack、Node/npm 或转译步骤。第三方工具已经生成的静态产物也可直接导入，遵循相同的 WebView、入口、Origin、权限和存储规则。ZIP 仅用于归档传输，不是前端编译。

`hermit.json` schema 2 的最小正式示例：

```json
{
  "schema": 2,
  "happId": "com.example.notes",
  "name": "示例笔记",
  "version": { "code": 1, "name": "1.0.0" },
  "entry": "index.html",
  "routing": "hash"
}
```

`liveUrl` 与 `updateUrl` 都是可选字段；缺少 `liveUrl` 表示纯本地 happ。完整作者合同、Bridge 示例和兼容要求见 [WebApp 编写指南](docs/webapp-authoring.md)，机器可读合同位于 [api](api/) 与 [sdk](sdk/)。

## 智能体开发

HermitUI 的“开发”Tab 可开启全局智能体开发模式。可信电脑使用页面显示的局域网地址和六位密码连接 MCP 服务；USB 可通过同一服务转发。每个普通 happ 有且只有一个开发副本，必须先切换为运行开发副本，智能体才能增量创建、修改、移动或删除文件。保存后可在同一 WebView 快速刷新并等待渲染确认；正式发布时再构建 ZIP，经标准更新事务安装。HermitUI 是受保护目标，不能通过该接口修改。

该服务使用可信局域网内的明文 HTTP，不能暴露到公网。Hermit 必须保持前台；停止开发模式、进程退出或 30 分钟没有认证请求都会关闭服务。动态客户端说明随 APK 位于 `app/src/main/assets/agent/hermit-device/SKILL.md`。

仓库仍保留面向单个实例的旧式 ADB/LAN 部署脚本，适合明确取得短期 token 的兼容流程：

```sh
./scripts/pack.sh examples/voice-notes /tmp/voice-notes.zip
export HERMIT_TOKEN='从目标 happ 的开发连接面板复制'
./scripts/deploy.sh '<instanceId>' /tmp/voice-notes.zip
```

两种开发入口互斥；启动其中一个会停止另一个。不要把密码、token 或业务数据写入源码、日志或提交内容。

## 构建与验证

下面的工具仅供 HermitApp 维护者使用，不是 happ 作者的依赖。项目锁定 JDK 17、AGP 9.1.1、Gradle 9.3.1 与 Android SDK 37；Node 只用于合同检查、HermitUI 快照和第三方图标资源维护，不参与页面运行时构建。

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./scripts/doctor.sh
./scripts/quick-check.sh web
./scripts/quick-check.sh android
```

HermitUI 必须先在同级 HermitWeb 修改，再同步快照：

```sh
node tools/sync-shell-assets.mjs
```

正式签名信息从仓库外读取。构建、校验和设备状态分别使用：

```sh
./scripts/build-release.sh
./scripts/verify-release.sh
./scripts/verify-device.sh
```

版本化交付物位于 `artifacts/v<version>/`。签名密钥、口令、token、`local.properties` 和构建目录不得进入 Git。

## 文档导航

- [产品与技术设计](docs/hermitapp-product-technical-design.md)
- [WebApp 编写指南](docs/webapp-authoring.md)
- [隐私与数据边界](docs/privacy-and-data.md)
- [实体设备验收清单](docs/validation/physical-device-checklist.md)
- [开发计划](plans/hermitapp-v1-development-plan.md)
- [执行记录](plans/hermitapp-v1-execution-log.md)
