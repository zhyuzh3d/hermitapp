# HermitApp 开发总则

本文件约束 HermitApp 的设计、开发、测试、发布和文档维护。开始任务时，先用一句话明确本轮对象、动作与验收点；只处理这个边界。必须独立判断用户设想的真实性、正确性和可行性，发现问题时直接说明并给出明确方案，不能机械附和，也不能为了形式严谨而扩张任务。

## 工作与仓库边界

- 默认流程是“理解边界 → 最少必要分析 → 修改目标 → 直接相关的最小检查”。只读取本轮必需文件；保留用户已有改动，不清理、覆盖或顺手修改无关内容。
- 只有用户明确要求部署，或本轮交付本身就是指定环境中的运行产物时，才安装 APK、发布网站、操作 Mutagen 或修改服务器状态。
- `hermitapp/` 与同级 `hermitweb/` 是两个独立仓库。HermitApp 唯一规范远程是 `git@github.com:zhyuzh3d/hermitapp.git`，HermitWeb 唯一规范远程是 `git@github.com:zhyuzh3d/hermitweb.git`。共同父目录不得初始化 Git，两个仓库不得合并、嵌套、改作 subtree 或 submodule。
- 跨仓任务必须分别检查、暂存、提交和推送。每次提交或推送 HermitApp 前，执行 `git rev-parse --show-toplevel` 与 `git remote get-url origin`，确认顶层目录以 `/hermitapp` 结尾且远程精确匹配；不匹配立即停止。修改远程、迁移仓库和强制推送必须另有明确授权。
- 进入 HermitWeb 工作前先读取其 `AGENTS.md`。HermitWeb 的修改、验证和发布遵循其仓库规则，不能借 HermitApp 任务自动获得授权。

## 统一术语与领域不变量

- “Hermit 项目”指包含 `hermitapp`、`hermitweb` 及后续同项目目录的整体；HermitApp、HermitAPK、APK 只指本仓库的 Android 宿主。
- HermitWeb、Web 指同级 `hermitweb/` 仓库。HermitUI、UI、Shell、原生 UI、原生 happ、官方 app 均指 `hermitweb/public/shell/` 的官方管理 happ。它是默认加载且唯一具有宿主管理权限的 happ。
- happ、WebApp，以及上下文明确指页面应用时的 app，均指 HermitApp 加载的页面应用；不得用 Web App 指整个 Hermit 项目或 APK。
- “本地 happ / 线上 happ”只描述来源，代码和接口使用 `source`；“本地运行 / 线上实时运行”只描述当前执行方式，使用 `runtimeMode`。两者正交，禁止用一个 `mode` 混合表达。
- 来源不决定能力：有 `activeReleaseId` 才能本地运行，有 `liveUrl` 才能线上实时运行，有 `updateUrl` 才能一键更新，有 `downloadUrl` 才能从原地址重装。URL 来源的 happ 下载到手机后仍是线上来源。
- `happId` 是发布包声明的稳定身份，`instanceId` 是 Native 为本机实例生成的唯一身份。显示名、URL、代码版本和运行方式变化不得隐式改变实例、数据或普通授权。
- Origin 不是可独立编辑的配置，必须按浏览器规则从完整 URL 推导为协议、主机和有效端口。路径不属于 Origin，不同子域名也不是同一 Origin。
- 本地代码以不可变 release 管理。更新先生成新 release，再原子切换指针；动态配置和用户数据不得写入 release。来源、版本、摘要和用户设置必须各自表达事实，禁止互相猜测。

## 产品与实现边界

- HermitApp 是 happ 容器与 Native Bridge，负责实例、安装、版本、数据、授权和系统能力。它不为 happ 执行后台 JavaScript，不允许 happ 创建常驻服务或定时执行任意代码；happ 对自己的代码、服务器、账号、令牌和业务内容负责。
- 有 `liveUrl` 的本地 release 在该真实 URL 空间内运行：包内存在的 GET/HEAD 资源由本地只读 release 优先响应，缺失资源和动态请求交给网络。没有 `liveUrl` 的纯本地 happ 使用 Native 派生地址，只能访问包内资源和已授权 Bridge 能力。
- 页面能力必须通过公开 Bridge 合同提供，并按 `instanceId` 隔离 Hermit 数据、文件、grant、通知和运行会话。Android 系统权限与 Hermit 的逐 happ 授权是两层独立决定；宿主已获系统权限也不能替页面授权。
- 通知只提供系统通知转接、设备端单次/每日/每周/每月/每年计划，以及按规范轮询的服务器通知。通知需要逐 happ 用户授权；HermitApp 不因通知能力承担 happ 服务端内容或凭据设计责任。
- WebView 站点数据遵循标准同源规则并使用共享资料空间；Hermit 自有记录继续按实例隔离。不同路径不能被描述成安全隔离。Bridge 优先使用可校验 Origin 和主 frame 的 WebMessage 能力，旧 provider 回退传统 `JavascriptInterface`，并如实显示其 iframe 风险。
- 安装、解压、URL、文件路径、大小、数量、摘要和事务边界由 Native 校验。安全机制保护 HermitApp 自身及用户明确授予的数据，不为 happ 建立超出容器职责的内容审查或复杂信任体系。

## 页面与 HermitUI 规则

- happ 默认并首选纯原生 HTML、JavaScript、CSS，源文件就是可运行页面；不假定 React、Vue、Vite、Webpack、转译器、包管理器或编译步骤。Hermit 不为第三方框架建立特殊分支，但接受其已经生成的最终静态产物。
- 页面创作不要求 Node/npm。ZIP 只是归档传输格式，不是构建产物；官方示例、模板、教程与验收夹具遵守同一原则。公共图标通过 APK 内置 Font Awesome Free 使用，不依赖运行时 CDN。
- HermitUI 的语义源码只在 `hermitweb/public/shell/` 编辑；`app/src/main/assets/store/` 是 APK 内置快照，不允许两边手工修改。需要更新快照时运行 `node tools/sync-shell-assets.mjs`，然后校验源文件、清单、版本和发布 ZIP 一致。
- Host API、Bridge、权限和 Android 生命周期属于 HermitApp；HermitUI 只消费公开合同。普通 happ 永远不能获得 HermitUI 的宿主管理权限。
- 新增页面合同、示例或依赖前，确认仅含 `index.html`、`app.js`、`style.css` 的目录仍可直接使用。以 `docs/webapp-authoring.md`、`docs/hermitapp-product-technical-design.md`、`api/` 与 `sdk/` 的当前合同为准。
- 文档或旧计划与本总则、用户最新明确指示冲突时，以后两者为准；同步修正文档，不能在代码中继续扩大冲突语义。

## 国内设备与运行环境

- 核心链路以国内销售、无 GMS 的 Android 手机及普通国内网络为基线。安装、启动、应用库、本地页面、扫码、数据、备份和局域网开发不得依赖 Google Play 服务、Firebase、海外 CDN、海外账号、在线许可校验或运行时下载模块。
- 随 APK 打包的开源库必须锁定版本、校验构建输入并保留许可。GitHub 等海外来源只作为可选适配器，失败不得阻断本地目录/ZIP、局域网开发或通用 HTTP(S) 包。
- Android 版本不能替代 WebView 能力检测。必须在实际 provider 上检测所需 WebKit 能力并按受支持路径降级；旧 provider 的页面可用性优先，但不得伪装不存在的安全边界。

## 验证、设备与发布

- 验证范围严格等于修改范围。网页资源默认做语法和直接合同检查；局部 Kotlin 修改编译受影响变体并运行直接相关测试；文档修改不触发构建。同一批代码未变化时不得重复构建、安装或验收。
- 除非用户明确要求全面检查，不执行全项目 lint、完整 instrumentation、设备矩阵、截图分析、视觉验收、反复 release 校验或长篇报告。发现范围外风险时只报告，不擅自扩修。
- 用户要求真机更新时，生成必要 APK、使用覆盖安装，并确认版本和进程可启动；安装包生成不等于设备交付，`adb install` 成功也不等于功能或视觉验收。
- 正式包名固定为 `io.github.zhyuzh3d.hermit`。发布签名、口令、token、`local.properties` 和私有路径配置不得进入 Git、日志或文档。发布产物使用 `hermit-v<version>-release.apk`，版本化产物发布后不得覆盖。
- HermitUI 上线属于 HermitWeb 发布。发布前确认 Mutagen Alpha 精确指向 `hermitweb/public/`、同步模式为本机到服务器的单向副本且状态正常；上线后核对 `/shell/manifest.json` 的版本、包路径和 SHA-256，并确认公开 Shell 资源可访问。APK 内置 Shell 不得高于尚未发布且不可用的线上版本。
