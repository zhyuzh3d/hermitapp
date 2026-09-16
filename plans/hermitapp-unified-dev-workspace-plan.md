# Hermit 统一开发工作副本实施计划

版本 1.0，2026-09-14。本文规划下一代智能体开发模式：HermitApp 在用户明确开启后提供一个面向整台设备的开发控制面；每个普通 happ 最多拥有一个逻辑上的开发工作副本，必须先切换到开发运行通道，智能体才可以新建、读取、覆盖、移动或删除其中的文件。正式代码仍是不可变 release，只有开发工作副本经过标准打包与安装事务后才成为正式版本。

本计划只设计普通 happ 的开发能力。HermitUI（HermitWeb 的 `public/shell/` 官方管理界面）是宿主控制面，不是可开发目标；智能体开发服务不得列出、复制、修改、替换或以普通 happ 身份安装它。HermitUI 继续只接受 APK 内置资源和官方更新渠道。官网静态页面也不属于设备开发目标。

## 一、确定的产品模型

开发服务开关和 happ 运行通道是两个独立状态，不能互相隐式改变：

- 全局“智能体开发服务”开关只控制 LAN/USB 开发接口是否可访问。关闭服务不删除开发工作副本，也不把正在开发的 happ 偷偷切回正式版本。
- 每个普通 happ 的 `launchChannel` 只能是 `stable` 或 `dev`。`stable` 加载当前正式 release；`dev` 加载该 happ 唯一的开发工作副本。
- 只有显式执行“进入开发模式”后，Hermit 才创建开发工作副本并把 `launchChannel` 切到 `dev`。所有开发文件写接口必须在 Native 端校验此状态；未进入开发模式统一返回 `E_DEV_MODE_REQUIRED`。
- 开启全局服务不会批量复制所有 happ。开发工作副本按需创建，避免无意义的存储占用和陈旧副本。
- 开发工作副本是一个持续变化的工作目录，不是 release，也不出现在正式版本历史中。对用户和智能体始终只有一份；内部为了原子提交而短暂存在的 staging/generation 不属于可见版本。
- 正式版本、开发工作副本、业务数据和权限分别管理。开发与正式代码继续使用同一个 `appId`、Web Profile、数据 generation、Origin 和逐 happ grants，因此开发页面具有与该实例正式页面相同的数据与能力边界。

这里的“可操作任意文件”指开发工作副本内、符合路径和容量安全规则的应用文件。智能体可以创建此前不存在的文件和目录、覆盖任意已有文件、删除文件、移动或改名；不能越过工作副本根目录、访问 `__hermit` 保留命名空间、宿主私有文件、其他 happ 工作副本、业务数据库、Cookie 文件或 Android 系统路径。一次提交后的文件树必须满足基本运行结构；文件替换和删除可以在同一原子批次完成，不能因临时中间状态损坏当前可运行快照。

快速开发是本功能的第一性能目标，不是后续优化项。日常热路径必须是“智能体已知修改文件 → 单次增量提交 → 原子启用 → 当前页面刷新”，不能在每次保存时重新读取全部设备文件、重新压缩完整应用、生成正式 release、重建 WebView 或扫描整个工作树。完整 ZIP 只用于首次导入、整树替换和正式发布。

在同一可信 Wi-Fi、普通近年 Android 设备、开发工作副本不超过 1,000 个文件/32 MiB、单次变更不超过 8 个文本文件/512 KiB 的验收基线下，目标延迟为：

| 操作 | p50 目标 | p95 目标 | 起止点 |
| --- | ---: | ---: | --- |
| CSS 单文件保存到设备生效 | 200 ms | 450 ms | 客户端检测保存至新样式完成一次绘制 |
| HTML/JS 小批量保存到页面完成重载 | 400 ms | 900 ms | 客户端提交至注入运行时报告新 revision 已完成绘制 |
| 仅提交文件且不刷新 | 150 ms | 350 ms | 客户端发出请求至 Native 原子提交响应 |
| 打开已存在 happ 的指定路由 | 350 ms | 800 ms | 命令发出至目标文档 ready 并完成一次绘制 |

这些数字是指定基线上的工程预算，不承诺所有网络和设备都达到相同绝对值。实现必须记录分段耗时，区分客户端检测、传输、Native 提交、WebView 导航和渲染回执，不能只用“请求返回成功”冒充页面已经刷新。

## 二、数据结构与设备目录

在 `instances` 增加：

```text
launch_channel TEXT NOT NULL DEFAULT 'stable'
```

新增 `dev_workspaces`：

```text
app_id                 TEXT PRIMARY KEY
base_release_id        TEXT NOT NULL
current_generation     TEXT NOT NULL
revision               INTEGER NOT NULL
tree_hash              TEXT NOT NULL
dirty                   INTEGER NOT NULL
created_at              INTEGER NOT NULL
updated_at              INTEGER NOT NULL
```

开发工作副本采用“只读正式基线 + 内容寻址变更文件 + 原子树清单”，设备内部目录：

```text
files/dev-workspaces/<appId>/
  current.json
  trees/<generationId>.json
  blobs/<sha256>
  incoming-<requestId>/
```

tree 清单描述当前逻辑文件树。未修改文件直接引用 `baseReleaseId` 中的文件；新增或修改文件引用本 workspace 的 SHA-256 blob；删除使用 tombstone；移动只改变清单路径引用，不复制文件内容。`current.json` 是经过 fsync 后原子替换的 generation/revision 指针。一次提交只写入发生变化且设备尚未保存的 blob，再生成一份小型 tree 清单并切换指针，不复制完整应用，也不重新计算未改变文件的摘要。

Runtime 通过 `DevTreeResolver` 读取固定 generation 的不可变视图：先查询 tree 清单，再从 dev blob 或正式基线解析文件。一次页面请求生命周期内持有 generation lease，指针切换不会让同一资源请求读到半写状态。旧 tree 清单和无引用 blob 在 Runtime lease 释放后异步清理；崩溃遗留的 `incoming-*` 在下次启动清理。对用户和智能体仍然只有一个 `current` 工作副本，tree generation 只是原子提交机制，不提供历史版本、分支或回滚 UI。

开发工作副本创建流程：

1. 锁定实例并读取当前 `activeReleaseId`；纯线上、没有本地 release 的 happ 返回 `E_LOCAL_RELEASE_REQUIRED`，要求先通过正常 ZIP、下载地址或更新地址安装本地代码。
2. 构建引用正式 release 文件的初始 tree 清单，不复制文件内容。
3. 记录 `baseReleaseId`、初始 tree hash、`revision=1`、`dirty=false`。
4. 原子启用 workspace，并把实例 `launchChannel` 设置为 `dev`。
5. 如果目标当前可见，让现有 WebView 切换 resolver 后重新加载相同路由，不销毁和重建 WebView。

开发工作副本的完整“文件夹”由 MCP 和本地同步助手呈现为普通目录语义；物理存储无需复制成第二棵完整文件树。只有生成正式包时才流式物化合并后的完整树。该结构比每次完整目录 staging 更复杂，但能同时满足单一工作副本、原子一致性和快速增量更新，是核心目标要求的必要复杂度。

## 三、MCP v2 与文件操作

保留一个设备级开发服务和一个动态工具目录。所有已认证客户端可以看见并操作全部普通 happ，不设置应用白名单或每电脑配对；但服务端必须先验证目标是 Registry 中处于 ready 状态的普通 happ，并排除任何宿主保留目标。

建议工具集合：

| 工具 | 作用 |
| --- | --- |
| `hermit_list_apps` | 列出普通 happ、正式 release、开发状态、dev revision 和运行通道 |
| `hermit_get_app` | 获取实例与开发工作副本状态 |
| `hermit_create_dev_app` | 原子创建最小合法 happ、正式种子 release 和开发工作副本 |
| `hermit_enter_dev_mode` | 从当前正式 release 创建或恢复唯一开发工作副本，并切换到 dev |
| `hermit_leave_dev_mode` | 切回 stable；默认保留工作副本，显式参数才丢弃 |
| `hermit_list_dev_files` | 列出开发工作副本完整文件树 |
| `hermit_read_dev_file` | 读取开发工作副本中的 UTF-8 文本或文件元数据 |
| `hermit_apply_dev_files` | 原子创建、覆盖、删除、移动多个文本文件 |
| `hermit_sync_dev_changes` | 日常最快路径；一次请求提交已知变更、切换 revision、触发刷新并返回渲染 operation |
| `hermit_put_dev_file` | 以 Content-Length 和 SHA-256 上传单个二进制或大文件 |
| `hermit_replace_dev_tree` | 用完整 ZIP 原子替换开发工作副本，而非正式 release |
| `hermit_open_app` | 打开 happ 的入口或同源相对路由 |
| `hermit_reload_app` | 刷新当前可见 happ 的当前路由 |
| `hermit_wait_dev_render` | 等待指定 revision 在当前页面完成 ready/paint 回执 |
| `hermit_get_dev_diagnostics` | 返回限定条数的导航、资源与脚本错误及分段耗时，不返回 DOM、Cookie 或业务数据 |
| `hermit_build_dev_package` | 校验开发树并生成确定性 ZIP，但不安装 |
| `hermit_install_dev_package` | 将生成的 ZIP 送入标准安装事务并形成正式 release |
| `hermit_reset_dev_workspace` | 从当前正式 release 重新建立开发工作副本 |

`hermit_apply_dev_files` 的单项操作支持：

```json
{"path":"pages/new.html","content":"..."}
{"path":"assets/old.svg","delete":true}
{"from":"pages/a.html","to":"pages/b.html","move":true}
```

新路径自动创建父目录；空目录不作为包内容保留。一次请求可以混合新增、修改、删除和移动。目标路径必须唯一，移动源必须存在，删除不存在文件返回明确错误。最终文件树统一执行规范化和重复路径检查，拒绝绝对路径、路径穿越、反斜杠、空分段、NUL、冒号、符号链接、特殊文件和 `__hermit` 分段。

每个写请求必须带：

```text
expectedDevRevision
requestId
```

`expectedDevRevision` 不一致返回 `E_CONFLICT`，并返回当前 revision/tree hash，智能体必须重新读取、合并后再提交。相同 `requestId` 只允许重放完全相同的输入。提交成功返回新 revision、tree hash、dirty 状态和刷新结果。文本 RPC、单文件上传、整树 ZIP 分别设置明确大小上限；二进制内容不通过 JSON Base64 大段传输。

`hermit_sync_dev_changes` 是 Codex 和其他智能体的默认热路径。客户端已经知道自己刚刚修改了哪些文件，不应先调用全量 list/read 或在设备端重扫目录。一次调用携带变更清单、每个内容摘要、缺失 blob 的实际内容、`refreshMode` 和可选 route；服务端通过摘要跳过已存在内容，在同一写锁内提交 tree 指针，并立即把新 revision 通知前台 Runtime。响应分开返回 `commitState`、`refreshState` 和 `renderOperationId`，客户端需要真实页面完成证据时再短轮询/等待 render operation，不让常规保存被截图或长等待阻塞。

对于本地目录镜像，首次连接下载完整路径与摘要清单；后续只比较编辑器已报告的变更或本地缓存的 mtime/size，候选变化才计算 SHA-256。服务端返回的 tree hash 和 revision 是并发权威，本地全树扫描只能用于显式 reconcile，不能成为每次保存的前置步骤。

开发主路径只使用 dev workspace API。旧的 `hermit_apply_files` MCP 写接口已经删除，不保留迁移期或双轨合同；`/v1/apps/{appId}/release` 仅作为用户明确要求正式发布时使用的独立发布端点，不参与日常开发同步。

## 四、打开页面、指定路由与自动刷新

智能体可以要求 Hermit 打开某个 happ 的入口，也可以打开该 happ 内的特定页面。接口只接受相对路由，不接受任意完整 URL：

```json
{
  "appId": "<appId>",
  "route": "/pages/editor.html?document=12#preview"
}
```

Native 使用该实例的实际 runtime URL 解析路由，并强制结果保持同一 Origin；拒绝 `javascript:`、`file:`、`content:`、scheme-relative URL、凭据 URL 和跨 Origin 目标。纯本地 happ 由 LocalContentGateway 解析文件或 history fallback；带 `liveUrl` 的本地 release 继续遵循现有本地优先、缺失资源访问网络的规则。页面跳转不会改变实例的 `liveUrl`、Origin 或授权。

`hermit_reload_app` 只在目标正处于前台时刷新当前完整 URL；目标不可见返回 `not-visible`，不会偷偷切换应用。`hermit_open_app` 才能切换到目标并可附带 route。开发文件提交默认 `refreshMode=auto`：目标可见时在 generation 原子切换后刷新当前路由，不可见时只提交文件并返回 `not-visible`。

同一 happ、同一运行通道的刷新不得再次执行 `showTarget` 来销毁并创建 WebView。Native 保留现有 WebView、Bridge、窗口和页面路由，只更新 `DevTreeResolver` 的 generation：

- 只有外链 CSS 文件变化时，注入的开发运行时给对应 stylesheet URL 添加 `__hermit_dev=<revision>`，等待新 stylesheet `load` 后在下一次 `requestAnimationFrame` 报告完成，不重载文档。
- HTML、JavaScript、路由配置或无法安全判断的资源变化时，对当前 URL 执行同 WebView reload。dev 资源响应统一带 `Cache-Control: no-store`，WebView dev 会话使用 `LOAD_NO_CACHE`，本地 Service Worker 继续禁用；不能通过清除整个共享 WebView 缓存影响其他 happ。
- 图片、字体等静态资源如果调用者明确知道页面会主动重取，可以选择 `refreshMode=none`；`auto` 默认使用页面 reload，优先保证测试看到一致结果。
- `refreshMode=reload` 强制同 WebView 页面重载；`refreshMode=none` 只提交文件。不得默认提供不可靠的 JavaScript 热替换，普通原生 JS 没有安全的通用 HMR 语义。

APK 注入的开发运行时在 `DOMContentLoaded` 后以及两次 `requestAnimationFrame` 后，向 Native 报告当前 appId、dev revision、完整同源 URL 和时间戳。CSS 热替换在所有新 link load/error 结束并完成一次绘制后报告。`hermit_wait_dev_render` 只等待该回执，不要求 happ 作者添加代码；超时返回 `render-timeout` 和最近导航/控制台错误，不能把 refresh scheduled 表述为测试成功。

Codex 等能准确知道写入文件的智能体应在文件写入完成后立即调用同步，不等待文件监听轮询。本地监听助手作为通用编辑器备用：优先使用平台文件事件，采用约 80–150 ms 的短防抖合并连续 rename/save；没有文件事件能力时使用低成本元数据轮询，只给候选文件计算摘要。它只上传变化内容，大规模首次同步或明确整树替换才使用 ZIP。它先执行 `hermit_enter_dev_mode` 或确认现有 dev revision，再开始同步；遇到 revision 冲突立即停止，不静默覆盖其他智能体的修改。

开发诊断只保留当前 dev 会话最近 100 条、每条最多 2 KiB 的主 frame 导航错误、资源加载失败、console warning/error 和未处理脚本错误，自动附带 revision 与 URL 路径。不得采集输入值、DOM、网络请求正文、Cookie、Authorization 或 localStorage。停止开发服务或离开 dev 通道即清理内存诊断，避免为了便利建立长期业务数据日志。

## 五、DEV 状态的原生可见提示

进入 dev 运行通道的 happ 在应用库卡片上必须明显区分：

- 亮色主题卡片使用浅蓝底，例如 `#EAF4FF`；暗色主题使用低亮度蓝色表面，例如 `#071C33`。
- 名称旁显示文字 `DEV` 或“开发副本”，不能只依赖颜色表达状态。
- 卡片继续遵守统一边距、图文间距和按钮间距，不增加点击焦点外框。

运行开发工作副本时，以 Android 顶部状态栏的绿色背景表示开发态；普通 happ 以线上实时模式运行时，以蓝色背景表示实时态。开发态优先级高于实时态。亮色主题使用更淡的浅绿或浅蓝背景与深色系统图标，暗色主题使用更明亮的深绿或深蓝背景与浅色系统图标。正式本地 release、HermitUI、支持页面和原生错误页恢复当前主题对应的默认状态栏。

happ 通过公开的 `appearance.reportTheme` Bridge 方法主动汇报当前实际生效的 `light` 或 `dark`。这是当前文档会话的瞬时事实，不是主题偏好：APK 不检测页面样式、不读取 happ 的主题存储，也不保存或推断其切换规则。跟随系统时，由 happ 自己解析系统主题并在变化后重新汇报；导航、刷新或会话销毁后报告失效，新文档必须重新汇报。未报告期间 APK 只使用系统明暗主题作为安全回退。

Android 15 及以上使用透明系统状态栏，因此由 Native 在状态栏 inset 后绘制背景；Android 10 至 14 直接设置窗口状态栏颜色。系统图标只使用 Android 公开的明暗外观控制，不尝试厂商私有染色。页面 DOM、CSS 和脚本不能改变该状态。UI 自动化和无障碍信息继续暴露 `运行开发副本` 状态，不能只靠颜色判断。

## 六、HermitUI/HermitWeb 的禁止开发与恢复

可以并且应当从 Native 服务端阻止 HermitUI 开发，不能只在前端隐藏按钮。执行以下硬边界：

1. `hermit_list_apps` 只读取普通 `instances`，不合成 HermitUI 开发目标。
2. 所有 dev workspace、文件、运行与打包接口首先通过 `requireDevelopableApp(appId)`，只接受普通 ready 实例；保留 ID、空 ID、Store role 和 OfficialShellManager 路径一律返回 `E_PROTECTED_TARGET`。
3. 开发根目录只由 `appId` 映射，禁止调用者提供绝对目录或目标类型；任何路径都无法指向 APK `assets/store/`、`files/official-shell/` 或 HermitWeb 在线资源。
4. 普通 happ 即使名称叫 HermitUI、地址指向官方 `/shell/`，也只能获得普通 WEB_APP role，不能获得 Store Host 权限。
5. PackageManifest 和安装入口保留官方身份、宿主命名空间与特殊 happId，普通 ZIP 不能声明或覆盖官方 Shell 身份。
6. 动态指南明确说明 HermitUI 是受保护控制面，不提供 checkout、文件读取或发布工具。可以只读提供官方仓库地址、更新清单地址和恢复说明，不能把 Git 内容直接安装为官方 Shell。

HermitUI 继续由 OfficialShellManager 独立更新。正式 APK 被覆盖安装后，HermitApplication 检测 package replacement/`lastUpdateTime` 变化，停止开发服务并强制优先加载 APK 内置 Shell；普通 happ Registry、正式 releases、业务数据、用户图标和逐 happ grants 保留。应增加不依赖 HermitUI 页面代码的 Native 安全回退：在线或下载 Shell 主 frame 加载失败、renderer 崩溃或未在限定时间内完成 ready 握手时，回退内置 Shell。这样无需开放 HermitUI 开发权限，也能保证控制面可恢复。

## 七、统一 LAN 与 USB 传输

产品只保留一个设备级开发服务，不再为每个 happ 创建 USB/LAN 会话、token、端口和设置项。开发 Tab 展示总开关、连接地址、复制/二维码、授权重置、最近动作和停止服务。

- LAN 是默认入口，服务覆盖所有普通 happ。
- USB 是同一服务的备用入口，通过 ADB loopback forwarding 使用同一 MCP、工具目录、密码和 dev revision，不启动旧的单 happ协议。
- LAN 和 USB 客户端并发写同一个 workspace 时，共用 revision CAS 和单写提交锁。
- 用户关闭开发模式或切换到互斥的旧部署模式时关闭 listener，但不删除开发工作副本或改变 `launchChannel`。后台、锁屏、空闲、备份操作和 Wi-Fi 临时断开不清除持久开关；进程重新启动后恢复 listener。

客户端助手复用 HTTP 连接和认证配置，MCP 初始化、guide、app metadata 与 dev revision 在 serverVersion/runId 不变时缓存；一次热更新不重复 discovery、tools/list、guide 或密码协商。Native 写入路径不执行网络请求、不生成正式 release、不压缩完整 ZIP，tree 指针提交后立即释放写锁，页面刷新在 UI 线程异步调度。不同 appId 的 blob 准备可并发，最终指针提交按 workspace 独立串行，不能用一个全局长耗时 ZIP锁阻塞所有 happ。

由于授权范围扩大到所有普通 happ 及其已有权限，正式实现应把 LAN 从六位密码加明文 HTTP 升级为带 SPKI pin 的 TLS。界面仍可保持一次复制：连接信息同时包含地址、pin 和密码；USB loopback 可以使用 HTTP。若阶段一暂时沿用可信 LAN 明文模式，必须保留当前 Host/Origin 校验、错误限速和前台生命周期，并明确标记为迁移限制，不能把六位密码描述为强安全认证。

## 八、新建、发布、更新与冲突

`hermit_create_dev_app` 接收名称、稳定 happId 和可选的初始文本文件；没有文件时由 Native 创建最小合法 `hermit.json`、`index.html`、`style.css` 和 `app.js`。该操作先通过 InstallCoordinator 生成一个正式种子 release，再立即创建开发工作副本、切换 dev 通道并返回 appId/dev revision。一个事务失败时不留下半个实例或孤立 workspace。

开发工作副本发布为正式版本时：

1. 智能体显式调用 build，提供新版本号；Native 校验 `happId` 与实例一致、版本递增、入口存在、路径/数量/体积合法、图标与 URL 配置有效。
2. 生成内容确定的 ZIP 和 SHA-256。build 不改变正式 release、运行通道或 dev revision。
3. 本机直接发布时，install 工具把该 ZIP 送入现有 InstallCoordinator，要求 `expectedStableReleaseId` 和 `expectedDevRevision`；只有两者均匹配才提交新正式 release。
4. 通过 `updateUrl` 发布时，智能体把 ZIP/清单发布到外部服务器后，再要求设备走正常更新接口。`updateUrl` 是下载入口，不是上传接口。
5. 发布成功后默认切回 stable，并从新正式 release 重建一份 `dirty=false` 的开发工作副本；调用者可以显式要求继续运行内容相同的 dev 工作副本。
6. 外部更新到达时，干净 workspace 自动 rebase；有未发布修改时保留 workspace、标记 `base-outdated` 并拒绝自动覆盖，等待智能体合并或用户重置。

删除、归档或数据替换 happ 时停止该目标的 Runtime lease，并在对应正式事务完成后删除开发工作副本。普通备份默认不包含 dev workspace；如未来支持导出，必须作为显式“包含未发布源码”选项，不能与业务备份混在一起。

## 九、HermitUI 交互调整

开发 Tab 收敛为一个全局面板：

- 智能体开发服务总开关。
- LAN 地址、USB 备用命令、密码/TLS pin、复制配置和二维码。
- 当前连接状态、最近操作和停止服务。
- 使用说明：智能体必须先让目标 happ 进入开发模式，才能写文件。

happ 设置弹窗移除现有“USB 开发连接”和“局域网部署”按钮，新增展开显示的“开发工作副本”区：

- 当前状态：未创建、干净、有未发布修改或基线冲突。
- 运行正式版本/运行开发副本切换。
- 创建或重建开发副本。
- 导出发布 ZIP。
- 放弃未发布修改并从正式版本重建。

这些按钮仍执行 Native Host 方法，HermitUI 不自行读写目录。所有一行双按钮维持统一两侧边距和正常横向 gap；关闭按钮、点击无外框、底部 sheet、明暗主题对比继续遵守现有界面规范。

## 十、实施顺序

### 阶段 A：领域模型与迁移

新增 `launchChannel`、`dev_workspaces`、DevWorkspaceManager、目录清理和 lease；现有实例迁移后全部保持 `stable`，不自动创建 workspace，不改变 release、数据或 grants。为当前 OfficialShellManager、Store role 和保留路径增加不可开发断言。

### 阶段 B：原子工作副本与 Runtime

完成基线引用、内容寻址 blob、原子 tree 清单、增删改移、单文件上传、整树替换、revision CAS、崩溃恢复和配额；LocalContentGateway 通过 DevTreeResolver 获取稳定 generation lease。加入 Native 3dp DEV 顶线、同 WebView 刷新、CSS 热替换和当前路由保留。

### 阶段 C：MCP v2 与本地助手

更新工具 schema、动态 SKILL、Python helper 和 Codex 配置模板。加入单请求 sync 热路径、render operation、短防抖文件事件监听、诊断和相对路由打开。助手默认确认开发模式、只同步已知差异并自动刷新；冲突停止。旧直接 release 写接口进入弃用期。

开发地址必须是平台无关的自描述入口，不依赖维护者 Mac 上的文件或配置。支持远程 Streamable HTTP MCP 的智能体直接从初始化说明、`tools/list`、`resources/list` 和 `prompts/list` 获得能力；同时兼容 2025 握手式协议与 2026 无握手协议。不能直接注册远程 MCP 的 Windows、macOS 或 Linux 客户端，可从同一地址取得仅用 Python 3.10+ 标准库的 HTTP/stdio 适配器、动态 Skill 和结构化客户端配置。服务只提供资料和配置模板；是否安装 Skill 或写入客户端配置仍由该智能体软件及用户权限模型决定。

### 阶段 D：正式发布闭环

实现确定性 ZIP、版本/身份验证、直接安装、updateUrl 更新后的 workspace rebase，以及创建新 happ 的单事务流程。正式 release 继续复用现有 InstallCoordinator，不另建第二套安装逻辑。

### 阶段 E：HermitUI 与传输收口

移除每 happ USB/LAN 配置和旧 `developer_enabled` 产品语义；USB 改为全局服务备用端点。完成 HermitUI 服务端排除、官方身份保护、APK replacement 回退和 Native Shell ready/watchdog。LAN TLS pin 可与本阶段一起交付；若拆期，明文限制必须保持显式。

### 阶段 F：文档、版本与交付

同步 `api/`、`sdk/`、`docs/webapp-authoring.md`、产品技术设计、APK 内 agent 资源和 HermitWeb 使用指南。分别验证 HermitApp APK、HermitUI 发布资源和官网说明；只有用户明确要求部署时才构建安装和发布网站。

## 十一、直接验收标准

1. 未进入 dev 的 happ，所有开发写接口均返回 `E_DEV_MODE_REQUIRED`，正式 release 文件和 `activeReleaseId` 不变。
2. 进入 dev 后，可以在一个原子批次中新增目录/文件、修改、移动和删除原文件；刷新后设备加载完整的新树，不出现半写状态。
3. 每个 appId 只存在一个对外可见 dev workspace；重复进入返回同一 workspace/revision，不创建分支或版本列表。
4. dev 与 stable 使用同一 appId、Origin、Web Profile、业务数据和逐 happ grants；切换代码通道不复制或重置数据。
5. 应用库中的 dev happ 在亮暗主题下都有明确的开发状态和文字标识；运行 dev 页面时状态栏显示主题自适应绿色，线上实时运行时显示主题自适应蓝色。
6. 智能体可以刷新当前页面，并以相对 route 打开目标 happ 的具体页面；任何跨 Origin 或危险 scheme 被拒绝。
7. 两个客户端并发写入时，旧 dev revision 收到冲突，不覆盖较新的工作副本。
8. build 只生成 ZIP；install 才创建正式 release。发布前后版本号、happId、摘要和 expected stable/dev 状态均被验证。
9. `updateUrl` 更新遇到 dirty workspace 不覆盖开发文件；干净 workspace 正确 rebase。
10. HermitUI 不出现在开发目标列表；猜测保留 ID、路径或官方身份的所有开发请求均由 Native 返回 `E_PROTECTED_TARGET`。普通同名 happ 不获得 Store 权限。
11. LAN 和 USB 使用同一工具集合和 workspace revision；删除每 happ 开发连接 UI 后仍可完成创建、编辑、刷新、指定路由、打包与发布闭环。
12. APK 覆盖安装后优先启动内置 HermitUI，普通 happ、正式 releases、数据、图标和 grants 保留；Shell 加载失败时无需依赖 HermitUI 页面即可恢复。
13. 基准项目的小型 CSS 保存、HTML/JS 保存、纯提交和指定路由打开达到本文 p50/p95 预算；报告必须列出客户端检测、传输、提交、导航和渲染各段耗时。
14. 日常增量提交的设备写入量只与变更文件及 tree 清单相关；未修改的 32 MiB 文件不会被复制、上传、重新摘要或重新压缩。
15. CSS-only 更新不重建 WebView；HTML/JS 更新在同一个 WebView 中重载并保留目标路由。render 回执对应准确的 appId 和 dev revision，旧页面回执不能错误完成新 operation。
16. 断开再连接且 server run 未变化时，客户端不重复获取完整文件树和指南；runId/schema 变化时正确失效缓存并重新发现。

## 十二、不进入本计划的能力

- 不向智能体开放任意 Android Shell、宿主私有目录、Cookie 数据库或业务数据导出。
- 不为 happ 执行后台 JavaScript，不因开发模式创建常驻任务。
- 不把 Git clone、远程仓库写入或外部服务器发布伪装成设备 updateUrl 的能力。
- 不允许开发工作副本绕过标准包验证直接成为正式 release。
- 不允许 HermitUI、官网或 APK 原生代码通过普通 happ 开发服务热修改。

完成后，用户只需开启一次设备级开发服务；智能体选择普通 happ、显式进入开发模式、修改其唯一工作副本并要求打开或刷新。测试满意后，再通过同一套包验证和更新机制生成正式 release。该流程减少连接配置，同时保留正式代码不可变、并发不覆盖、HermitUI 不可修改和设备可恢复四条底线。
