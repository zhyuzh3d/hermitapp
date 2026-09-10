# HermitApp 产品与技术设计

版本：1.0.0 · 实施基线版
日期：2026-09-11
状态：v1 实施合同；实现与验收状态以执行记录和发布报告为准
配套计划：[HermitApp v1 开发计划](../plans/hermitapp-v1-development-plan.md)

## 1. 产品定位与决策依据

Hermit 是个人 Android 页面应用工作台：把用户自己制作、维护或信任的网页，变成能从桌面打开、长期保存数据、使用手机能力并持续更新的小工具。一个 Hermit APK 承载多个 Web App，业务页面更新不必重新编译或安装 APK。

主要服务两类人：用 AI 或前端工具制作个人应用的创作者，以及使用自托管服务、家庭设备控制页和这些个人应用的使用者。首要价值是让一个页面工具从“能打开”变成“能长期使用和维护”；任意网站兼容是入口能力，不承诺所有网站都能获得与原生应用相同的行为。

本文替代 v0.4.1 的实施方案，保留用户确认的产品方向，重新选择技术路线。旧稿归档在 [v0.4.1 历史参考](archive/hermitapp-product-technical-design-v0.4.1.md)。旧稿与本文冲突时以本文为准；两个文档都不是已实现证据。

### 1.1 已确认且继续保留的约束

| 项目 | 决策 |
| --- | --- |
| 术语 | Hermit / hermitapp / 容器 / shell 均指整个 APK；app / Web App / 页面应用均指其中运行的页面 |
| Android 身份 | release 的 applicationId 与 namespace 均为 `io.github.zhyuzh3d.hermit` |
| 桌面入口 | Hermit 主图标进入内置应用库；Web App 快捷方式携带稳定 appId |
| 授权 | 系统对 Hermit 的授权，与 Hermit 对每个 Web App 的授权共同决定可用能力 |
| 本地目录 | 导入到 Hermit 管理的快照，不依赖原目录继续存在；支持外部 curl 等工具更新 |
| 交付方式 | 开发阶段是内部连续执行顺序，最终交付产品级签名 APK、源码与验证证据 |

Android 最低安装基线为 Android 12 / API 31，compileSdk / targetSdk 为 37。该基线覆盖发布时约五年内的系统版本，并避免为已退出主流支持周期的系统承担额外安全与兼容成本。能安装 APK 不等于该设备的 WebView 能运行所有 Web App；运行能力仍需逐项检查。

### 1.2 首要使用场景

| 场景 | 用户实际要完成的事 | 产品必须保证的结果 |
| --- | --- | --- |
| AI 生成个人工具 | 把记事、清单、朗读、语音录入等静态产物放进手机 | 不要求 PWA 或额外构建；自包含代码可离线运行；数据不随代码更新消失 |
| 自托管与家庭设备 | 把域名、HTTP 私网 IP、带端口控制台放到桌面 | 地址可编辑，连接失败可恢复；原生能力按需授权 |
| 持续开发 | 修改电脑上的 dist，推送到手机并立即查看 | 同一 appId 连续部署；无需重装 APK、重建图标或重新授予已有权限 |
| 长期维护 | 升级页面、回到旧版、备份、换机、更新 Hermit | 代码、业务数据、浏览登录态的保留和恢复边界清楚 |

“本地副本”只说明代码在手机，不意味着远程 API、CDN、字体或登录服务离线可用。Hermit 不递归镜像网站；参考应用和离线模板必须自包含。

### 1.3 范围与成功标准

v1 完成上述四条场景的闭环，包括在线与本地安装、公开 GitHub 来源、桌面入口、按应用授权、持久数据、必要原生能力、开发部署、更新、备份和恢复。应用库不建设公共应用市场、账号体系、收费体系或云同步。

不在 v1 建设浏览器标签页、手机端 npm/Git 构建、独立 APK 生成、多窗口、后台录音定位、任意 Intent/系统插件执行、蓝牙/NFC/无障碍服务。扫码、私有仓库、差分包、云发布和动态原生插件留作扩展；粘贴、文件选择及 Android 分享接收覆盖首发输入。

成功以“桌面工具可以稳定使用和更新”验收，不以 API 数量验收。四条参考用户任务、负面隔离测试、故障恢复、APK 覆盖升级必须通过；构建成功或生成 debug APK 不代表产品完成。

## 2. 产品体验

### 2.1 应用库与运行页面

内置 Store 在用户界面显示为“应用库”，避免被理解为公共应用商店。它随 APK 发布，离线可用，管理所有 Web App。首页只有已安装应用、搜索和“添加”；应用详情提供打开、桌面图标、地址/来源、版本、权限、存储、备份、开发连接和删除。

普通运行页面占据内容区，无常驻地址栏或悬浮管理按钮。系统栏、键盘和安全区域正确避让；错误时显示 Hermit 控制的恢复界面，提供重试、回应用库和查看原因。用户随时可通过 Hermit 主图标返回应用库。

应用库使用 APK 内置的 HTML/CSS/TypeScript 编译产物；授权对话框、系统选择器、WebView 不兼容/数据库损坏恢复页使用原生 UI。恢复入口不能依赖已经失效的 WebView 或 Bridge。

### 2.2 添加与身份维护

用户选择“在线地址”或“本地副本”，也可直接粘贴输入，由解析器给出明确候选。在线地址保存原 scheme；裸域名默认 HTTPS，裸私网地址可建议 HTTP，最终地址始终可见。普通 URL 不会因为根目录恰好有安装描述文件就被静默改成本地包。

本地副本接受 ZIP、SAF 目录、HTTPS 包地址和公开 GitHub 静态目录。无 manifest 时推断名称与入口；候选不唯一时列出目录供选择，不遍历任意源码目录碰运气。v1 的应用库卡片和桌面快捷方式统一使用 Hermit 品牌图标，避免在没有完整图标净化、裁切与密度生成链路时处理不可信图片；实例自定义图标作为后续兼容功能。手机不执行构建命令。

确认后先完成安装，再可选添加桌面图标。安装成功与 Launcher 接受固定图标是两个结果；用户拒绝或 Launcher 不支持时，仍可从应用库打开。快捷方式只记录 appId，不记录 URL 或物理目录。[Android ShortcutManager](https://developer.android.com/reference/android/content/pm/ShortcutManager)

同一来源可安装多个实例，分别拥有身份、授权和数据。修改展示名、在线入口的同 Origin 路径或更新代码不改变 appId。修改在线 Origin 是信任关系变更：展示前后地址，清除敏感授权，换用新的 Web Profile，保留 Hermit 管理的业务数据；不自动复制旧站点 Cookie。在线与本地形态之间的转换首版通过新建实例和显式数据导入完成。

### 2.3 启动、导航和返回

一个 Activity、同一时刻一个可见 WebView。主图标无条件请求应用库会话；快捷方式通过 appId 查询实例。切换实例或代码 release 时销毁旧 WebView，创建并绑定新会话。Intent 只表达打开目标，不能直接授予权限、指定文件路径或调用管理方法。

在线应用的同 Origin 页面留在当前 WebView。其他 Origin 默认交系统浏览器；确需网页登录跳转时，可在应用详情启用“允许站内跨站导航”，但 Bridge 仍仅属于主 Origin，跨站页面不获授权。v1 不开放多 Bridge Origin 白名单。外部浏览器中的 OAuth 不能被承诺自动把登录态带回 WebView；需要站点支持或选择站内登录模式。

返回键优先处理网页历史；到根页后，若本次从应用库打开则回应用库，从桌面快捷方式打开则结束当前运行入口。新窗口按导航规则处理，不创建隐藏的额外 WebView。非 HTTP(S) 协议只允许固定的系统用途，并由明确用户操作触发。

前后台切换、屏幕旋转和进程回收不承诺保存 JS 内存。应用应及时持久化业务状态；Hermit 保留可恢复的入口和页面位置，旋转重建遵循同一会话恢复流程。恢复的浏览历史也必须重新经过导航策略。

### 2.4 更新、删除和故障恢复

在线应用显示“重新加载”，没有可回滚的本地代码版本。缓存遵循网站与 WebView；开发时可绕过 HTTP 缓存，但不能宣称清除了 Service Worker 或登录态。

本地应用显示当前版本、来源和上一保留版本。下载与校验不打断正在使用的旧版；普通更新默认下次打开生效，用户可选择立即重开。开发会话可预先开启“部署后立即重开”。新包失败时保留旧版和数据，并显示可重试错误。

v1 删除统一为“删除此应用及本机数据”，先提供备份入口，不实现模糊的“删身份但保留目录”回收站。逻辑删除立即禁用启动、授权、部署和快捷方式；后台完成代码、数据、Web Profile 清理，保留最小删除任务直至完成。Launcher 残留图标可能需用户自行移除。

## 3. 统一领域模型

只保留两个运行形态，来源适配器只负责把输入转换为其中之一：

```text
URL ───────────────────────────────→ OnlineSpec
ZIP / SAF目录 / HTTPS包 / GitHub / curl → BundleCandidate
                                           ↓
                                    同一个安装事务
                                           ↓
                              WebAppInstance + CodeRelease
```

`WebAppInstance` 是长期身份；`CodeRelease` 是不可变本地代码；`RuntimeSession` 是一次运行；`Operation` 是一次可追踪操作。不要再用一个含糊的“Profile”同时指业务身份和 WebView 存储。

| 对象 | 关键字段 | 唯一职责 |
| --- | --- | --- |
| WebAppInstance | appId、name、mode、entry、trustRevision、webProfileName、activeReleaseId | 身份、运行配置、当前代码指针 |
| SourceBinding | appId、adapter、spec、updatePolicy | 用户认可的上游来源和更新方式 |
| CodeRelease | releaseId、appId、treeHash、entryPath、版本、provenance、sourceRevision | 一次完整且不可变的本地代码 |
| RuntimeSession | sessionId、appId、role、webProfileName、releaseId、dataGeneration、documentEpoch | 当前运行上下文；由 Native 创建 |
| CapabilityGrant | appId、trustRevision、capability、resourceScope、decision | Hermit 层的持久授权意图 |
| Operation | operationId、appId、kind、state、idempotencyKey、result | 安装、更新、备份、删除、部署的状态和恢复 |
| FileHandle | opaqueId、appId、logicalFileId、access、expiry | 对受控文件的引用 |
| DevelopmentSession | targetAppId、transport、expiresAt、autoReload | 显式开启且短期存活的开发连接 |

appId 采用 Native 生成的 UUID，发布方声明的 ID 只作元数据，不自动合并实例。releaseId 也由 Native 生成；规范化文件树摘要用于识别相同内容。treeHash 使用固定域标识，并逐文件编码 UTF-8 路径长度、路径、内容长度和内容，避免不同文件边界得到同一摘要输入。ZIP 文件摘要与展开后 treeHash 是不同值，不能混用。

当前版本号、来源、摘要只从 activeReleaseId 关联得到，不在实例表重复存多份“当前版本”字段。SourceBinding 与 release.provenance 分离：一次 curl 覆盖不修改原 GitHub 上游；界面显示开发覆盖，再从上游更新需明确说明会覆盖该版本。

## 4. 技术路线与模块边界

采用 Kotlin + Coroutines + Android Framework/AndroidX Activity + System WebView + AndroidX WebKit。网络统一用 OkHttp，系统注册库和应用数据使用系统 SQLite，文件使用 SAF。原生只有一个 Android 模块，通过包和接口分离责任；不为预计的规模建立多进程服务、复杂依赖注入或跨平台抽象。

选择依据是总实现与维护成本，而非依赖数量最少。协程使页面退出、超时、系统授权和网络取消可以遵守统一生命周期，减少 Java 手工回调状态；OkHttp 统一下载、请求取消、超时与测试替身。依赖仍需锁定、审计和按用途隔离。[Android 协程实践](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)、[OkHttp 官方说明](https://github.com/square/okhttp)

| 路线 | 判断 |
| --- | --- |
| Kotlin + 精简自有 WebView 宿主 | 采用；运行身份、来源和权限确为产品核心，直接表达这些模型 |
| 保留 Java 并手写异步/HTTP 基础设施 | 不采用为默认路线；省掉语言依赖却增加取消、竞态和网络实现成本 |
| Capacitor / Cordova 整体接入 | 不采用；插件生态有价值，但单业务包配置不能直接解决动态多实例身份与授权，仍需重做核心宿主 |
| GeckoView / 内置 Chromium | 不采用；首发没有必须脱离 System WebView 的需求，增加引擎分发与维护负担 |
| Compose / Flutter / React Native | 不采用为主 UI；产品 UI 本来以页面为主，少量原生系统界面用 Views 即可 |
| Room / ORM、NDK SQLite、动态插件加载 | 首版不引入；受控而少量的表用显式迁移，扩展 Native 能力随 APK 编译发布 |

Capacitor 官方仍把 `server.url` 描述为开发 live reload 用途。这里的选型是对 Hermit 多实例目标的工程判断，不是声称该框架不能加载在线页面或其桥必然不安全。[Capacitor 配置](https://capacitorjs.com/docs/config)

```text
应用库 UI / 快捷方式 / Deploy HTTP
                 ↓
      Application Services
    安装事务 · 实例管理 · 备份恢复
                 ↓
  Registry ── RuntimeHost ── LocalContentGateway
                  ↓
        Bridge + CapabilityRegistry
                  ↓
       PermissionBroker + Adapters
                  ↓
          Android / SQLite / Files
```

入口不直接读写业务表或目录。应用库管理调用与 Deploy 都调用相同 Application Services，但持有不同权限：开发连接仅能更新当前获准目标，不能复用应用库的管理员身份。

建议代码包为 `model/`、`registry/`、`install/`、`runtime/`、`bridge/`、`capability/`、`data/`、`deploy/`、`ui/`。纯解析、状态机、验证规则不依赖 Activity，便于普通 JVM 测试；禁止为了分层给每个类创建无意义的接口。

### 4.1 构建基线

| 项目 | 初始锁定目标 |
| --- | --- |
| release applicationId / namespace | `io.github.zhyuzh3d.hermit` |
| debug applicationId | `io.github.zhyuzh3d.hermit.debug`，可与 release 并存，不参与正式升级链 |
| minSdk / compileSdk / targetSdk | 31 / 37 / 37 |
| JDK / AGP / Gradle | JDK 17 / AGP 9.1.1 / Gradle 9.3.1 |
| AndroidX WebKit | 1.17.0，实际能力仍逐项检测 |
| Kotlin | 优先使用 AGP 内置 Kotlin 支持；与 serialization 编译插件版本匹配 |
| JSON / HTTP | kotlinx.serialization / OkHttp，精确版本在 S0 构建验证后锁定 |
| Store / SDK | TypeScript + DOM/CSS，开发时 esbuild 打包；APK 内无 Node runtime |

AGP 官方列出的 9.1.1 兼容 API 37、Gradle 9.3.1、JDK 17；AndroidX WebKit 当前稳定版为 1.17.0。以上是经资料核对的候选构建组合，尚未在本机完成构建验证。Build Tools 及其他依赖在 S0 固定到实际可获取且兼容的精确版本，不使用 `+` 或浮动最新版。[AGP 兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)、[WebKit 发布记录](https://developer.android.com/jetpack/androidx/releases/webkit)

## 5. 安装、来源与版本事务

### 5.1 一个描述格式

以可选的 `hermit.json` 统一应用元信息和本地版本信息，不再并行维护 hermit-install 与 hermit-version 两套相似协议。在线 URL 无须此文件；本地无文件也可按默认入口安装。

以下为 ZIP 内的最小应用描述：

```json
{
  "schema": 1,
  "name": "语音笔记",
  "entry": "index.html",
  "routing": "hash",
  "version": { "code": 104, "name": "1.4.0" }
}
```

包根就是可运行 Web 根，打包工具在电脑上处理 dist 子目录；导入目录可在确认时选择子目录，不在 Runtime 中保留 webRoot 的二次映射。

v1 的 HTTPS 来源直接指向完整 ZIP，不再维护一套与包内 `hermit.json` 重叠的远程描述协议。更新时重新下载同一 SourceBinding，由 TLS、64 MiB 流量限制、ZIP 校验和内容 treeHash 共同约束；相同文件树不会制造重复 release。需要发布级摘要或签名分发时，应在后续版本引入独立且经过威胁建模的发布清单，不能把一个可被同一服务器同时替换的摘要包装成发布者签名。

`entry` 使用本地路径校验器：禁止绝对路径、反斜杠、路径穿越和保留命名空间。v1 manifest 采用严格 schema，只接受 `schema/name/entry/routing/version`；未知字段拒绝并给出可恢复错误。能力是否可用由运行时 `runtime.capabilities()` 探测，授权只在实际敏感调用或显式 `permissions.request()` 时发生，manifest 不能自行取得能力。

### 5.2 统一来源适配器

适配器接口只有 `resolve(input)`、`materialize(candidate, sink)` 和可选 `check(binding)`。所有本地适配器写入同一受限 staging sink，不持有 active 指针写权限。

| 输入 | 处理方式 | 更新语义 |
| --- | --- | --- |
| 在线 HTTP(S) 地址 | 保存规范化 OnlineSpec；有限获取标题/图标，不执行探测页面脚本 | 重新加载；同 Origin 修改路径可保留授权 |
| SAF ZIP / 目录 | 完整复制到 staging；遍历有数量/大小边界 | 再次选择导入，不依赖旧 URI grant |
| HTTPS ZIP | 公网地址校验后受限流式下载，再进入统一包校验 | 用户显式触发重新下载；以内容树识别相同版本，不把自报 version 当安全边界 |
| GitHub 公开仓库 | 解析 repo/ref/path，先锁 commit，再下载该 commit 的 ZIP archive 并抽取目标目录 | 比较锁定来源的 commit；不在下载过程中跟随变化的 branch |
| curl 部署 | 向已有本地实例提交完整快照 | 同一事务；不要求开发时反复递增业务版本号 |

GitHub 首版只保留 archive 一条下载路径。目录不明确时要求显式指定 ref 与 path；包含斜杠的 ref 不能靠截断 URL 猜测。静态入口缺失、LFS 指针、submodule、限额、仓库过大均给出具体错误及“在电脑打包后导入”的路径。下载全仓库 ZIP 的体积上限按传输量计算，即使只提取一个目录也不能绕过限额。

HTTPS 更新 code 仅比较同一 SourceBinding；手工回退不降低已见过的上游最高 code，避免下一次检查误判版本。摘要验证完整性，发布者真实性依赖已确认来源和 TLS；代码包签名不是 v1 既有能力。

### 5.3 提交与并发

```text
创建 Operation
→ 获取每 appId 的写操作锁
→ 传输/复制 staging
→ 校验文件树、入口、manifest、配额、摘要
→ 完成文件写入和同步
→ 同一文件系统 rename 到 releases/<releaseId>
→ Registry 事务写 release、CAS 切换 activeReleaseId、写 operation 成功
→ 发布变更事件
→ 按用户策略重建 RuntimeSession
```

对于新安装，appId 在操作开始时预留，但成功前不成为可启动实例。对于更新，WebAppInstance 仍可运行旧版，忙碌状态由 Operation 表达，不复制为另一套“应用更新状态机”。

安装、开发覆盖、恢复、删除对同一 appId 串行。提交必须比较 `expectedActiveReleaseId`；并发旧请求返回 `E_CONFLICT`，不能用最后写入静默覆盖新版本。不同实例最多两项传输，一项解压，避免争用手机存储。

Operation 状态为 `queued → transferring → validating → committing → succeeded`，另有 `failed / cancelled`。进入 committing 前可取消；提交后取消只停止后续展示，不撤销已经提交的结果。幂等键与目标、输入摘要绑定，同键不同输入冲突；调用方断线后查询 operation，不盲目再安装一次。

进程重启时，未提交 staging 可清理；有完整 release 而 DB 未引用的目录是待清理孤儿；DB 已提交但 operation 响应丢失时返回原成功结果。缺失/损坏的活动目录先停止该实例启动，再恢复到仍可验证的保留版本或进入恢复页。文件和数据库不是跨资源事务；通过写入顺序、journal、CAS 和启动核对保证可恢复，不承诺所有掉电场景零损失。

### 5.4 会话版本与回滚

LocalContentGateway 在 RuntimeSession 创建时绑定固定 releaseId，所有 HTML、JS、CSS、Worker 和媒体请求读取同一文件树，绝不逐请求读取 activeReleaseId。旧会话退出前，版本清理不能删除其 release；响应流也持有版本租约，关闭后才释放。

保留当前版与至少一个上版，额外版本按存储配额清理。校验失败不会激活；代码目录损坏等确定的基础设施错误可以恢复旧指针。JavaScript 业务错误、页面空白和超时不能可靠判定“坏版本”，仅提供显式回退，不反复自动重试造成循环。

回退只切换代码，既不回退业务数据，也不恢复服务器状态。示例应用使用追加、向后兼容的数据变更。需要破坏性数据迁移时，应用先导出或保留自己的迁移版本；v1 不运行任意包内 Native 迁移脚本。可选 `hermit.app.ready()` 只记录页面自报就绪，不证明业务正确，也不作为唯一健康依据。

## 6. WebView、内容加载与兼容

### 6.1 隔离合同

正式 Runtime 必需 `MULTI_PROFILE`、`WEB_MESSAGE_LISTENER`、`DOCUMENT_START_SCRIPT`。每个 WebAppInstance 与应用库拥有独立 Web Profile，使用 Profile 自己的 Cookie/存储服务，禁止用默认 Profile 的全局 CookieManager 替代。不支持时进入原生兼容说明页，可导出已有 Hermit 数据、打开系统 WebView 更新入口，但不运行共享 Profile 的替代模式。

这一选择减少一套难以验证的权限和数据语义，也意味着部分旧设备不能运行 v1。API 31 是最低安装条件，实际支持范围仍以设备与 WebView feature 测试结果为准，不能仅靠 Android 大版本推断。

Profile 删除要在下次进程启动、该 Profile 尚未加载前处理。仅销毁 WebView 不足以保证能调用 deleteProfile：已在当前进程通过 getProfile/getOrCreateProfile 加载的 Profile 也受到限制。逻辑删除立即生效，物理清理完成情况另记；不宣称安全擦除磁盘。[ProfileStore](https://developer.android.com/reference/androidx/webkit/ProfileStore)

### 6.2 LocalContentGateway

应用库 Origin 固定为 `https://store.hermit.invalid`；本地实例为 `https://<appId>.apps.hermit.invalid`。这些域名禁止作为在线来源安装。

通过 AndroidX WebKit 的本地内容映射机制和自有受限处理器提供资源，不启动 HTTP 服务承载日常页面。每个 WebView 只映射自己的代码根；其他 Hermit 内部域名一律拒绝，不回落网络。内部保留 `/__hermit/` 路径用于临时媒体 handle，不允许包占用。

网关仅支持 GET/HEAD，返回明确 MIME、`nosniff`、长度和状态；媒体支持单区间 Range/206/416，拒绝多区间。不存在的 JS/CSS 返回真正 404；只有 manifest 显式选择 history routing、请求是 HTML 导航且无对应文件时才回退 index.html。默认 hash routing。外部数据 API 不被伪装成本地静态文件服务器。

本地代码响应采用 `Cache-Control: no-store`，稳定 Origin 保留 WebStorage，而会话锁定 release 保证资源一致。Web App 自己主动使用 CacheStorage 缓存业务内容属于应用逻辑，Hermit 不替其迁移缓存。

本地包不启用 Service Worker；离线与版本缓存由 Hermit 负责，避免 SW 控制旧页面和更新缓存再形成第二套发布系统。网关对所有代码及受控资源响应强制设置跨 Origin 的 `Service-Worker-Allowed: https://sw-disabled.hermit.invalid/`。按 SW 更新算法，这使注册因 scope 的 Origin 不匹配而失败，同时不限制普通 URL/Blob Worker。此方案有标准与 Chromium 实现依据，但仍必须在支持的 WebView 上验证 classic/module、各种 scope 和 Worker 行为；不能仅靠注入 JS 覆盖 register 作为保证。[W3C SW 更新算法](https://www.w3.org/TR/service-workers/#update-algorithm)、[Chromium scope 校验实现](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/content/browser/service_worker/service_worker_loader_helpers.cc)

每个 Local Profile 的 ServiceWorkerClient 也把内部虚拟域请求交给固定网关或明确失败，不允许 DNS 兜底。v1 从新 Profile 开始，不继承外部已有的本地 SW；未来迁移旧 Profile 若发现 controller，暂停运行并走显式清理流程。依赖 SW 才能启动的包必须提供不注册 SW 的构建。

### 6.3 在线内容与系统接口

在线内容保留网站缓存、Service Worker 和普通浏览网络行为，不承诺可靠离线、后台执行或完全兼容特定站点登录。TLS 错误直接失败，用户可修改地址或证书部署，不提供全局“忽略 SSL”。

关闭 file/content URL 任意访问、universal file access 和 mixed content；允许声明过的 HTTP 在线入口，应用详情始终标识明文来源。第三方 Cookie 默认关闭，可按实例开启；release 关闭 WebView debugging。

原生相机/麦克风、Web geolocation 等敏感入口不能绕过 PermissionBroker。v1 对 WebChromeClient 的 getUserMedia、地理位置等授权请求统一明确拒绝，能力通过主文档 Hermit API 提供；未知资源种类也拒绝。原因是这些回调未提供与 Bridge 等价的主 Frame 身份合同。文件输入允许调用系统选择器，但结果只归属发起会话，并遵循文件导入边界。标准网页摄像头/麦克风站点可能因此需要适配，必须写入兼容说明。

## 7. Bridge 与能力扩展合同

### 7.1 身份、文档与信任

消息桥注册在首次加载前，使用 exact Origin 的 WebMessageListener 和 document-start SDK，公开 `window.hermit`。Native 校验 WebView 实例、role、appId、Profile、sourceOrigin、isMainFrame、sessionId、documentEpoch 和参数。网页传入 appId 不能选择执行身份，host 方法只属于 Native 创建的 Store 会话。

一次 WebView 会话可以经历多个 HTML 文档，其下建立独立 DocumentSession。SDK 每个文档生成随机 documentId，先发送 hello 并暂存 API 调用；hello 本身不激活权限。Native 确认主导航已提交且不为错误页后，经该文档的 replyProxy 发出一次性 challenge，收到匹配 ack 才绑定 documentId、epoch 和代理并放行。完整文档导航开始即撤销旧绑定，副作用执行前与结果返回前再次检查；204、下载或取消等未提交导航须恢复原文档的握手，不能永久卡住。

同文档 hash/history 变化不重置。v1 不主动开启 BFCache，支持控制时明确关闭；仍测试系统可能产生的 pageshow 恢复，不把“从未开启”当成不会发生。SDK 的 pagehide/pageshow 配合 Native 导航回调完成失效与重新握手，不能只相信页面事件。

优先使用支持时的 NavigationListener 区分导航、提交与同文档变化；旧 provider 采用 WebViewClient + 文档握手，导航未完成时关闭敏感调用并取消待决授权，重新握手后恢复。此处必须通过乱序、重定向、BFCache、同 Origin 换页测试；无法保持合同的 provider 不能列入支持矩阵。NavigationListener 是可选优化，不能因为依赖库含有方法就认为 provider 实现了它。[NavigationListener](https://developer.android.com/reference/androidx/webkit/NavigationListener)

异步回复只使用发起消息的 replyProxy，并附带原 epoch；即使框架在页面离开后丢弃回复，Native 也必须取消可取消任务、丢弃失效结果，不向当前页面重新寻址发送。[WebMessageListener](https://developer.android.com/reference/androidx/webkit/WebViewCompat.WebMessageListener)

Origin 是信任单位，不是 URL 路径。直接来自 iframe 的桥消息拒绝；同 Origin iframe 能通过 DOM 访问父窗口时，仍与父页面共享信任，不能宣称 main-frame 检查隔离了这种脚本。XSS 和同 Origin 脚本具有当前页面权限；Native 桥不提供“可信页面里的不可信 JS”沙箱。

### 7.2 一个调用模型

协议统一为请求、响应、事件；公共方法和 host 方法共享编解码及调度基础设施，使用不同 Native 方法目录和角色白名单。

```json
{
  "v": 1,
  "id": "r17",
  "sessionId": "s1",
  "documentEpoch": 3,
  "method": "tts.speak",
  "params": { "text": "你好" }
}
```

```json
{
  "v": 1,
  "id": "r17",
  "documentEpoch": 3,
  "ok": false,
  "error": {
    "code": "E_OS_PERMISSION_DENIED",
    "message": "系统未授予所需权限",
    "retryable": false
  }
}
```

Promise 用于一次性结果；持续任务返回 subscriptionId，事件包含 epoch、subscriptionId 和 sequence。stop/cancel 幂等；页面退出取消订阅。取消不撤销已经完成的数据库提交、分享或网络副作用。长安装/备份任务返回 operationId，页面重开后从应用库查询，避免无限等待的 Promise。

能力目录由 `api/capabilities.json` 冻结公共与 Store-only 方法，TypeScript 类型、注入 SDK 与 Native dispatch 由契约测试交叉检查；运行时再补充设备是否支持以及当前是否可用。扩展新能力需要同时修改目录、adapter、类型和测试，但不修改传输协议。

`runtime.capabilities()` 分开返回 implemented、supported 与 usable；具体逐应用 grant 和系统权限通过 `permissions.status()` 查询，不把“系统未装识别服务”显示成权限拒绝。API 主版本不兼容就明确失败；同一主版本只做兼容增加。当前尚未发布 ABI，不为旧稿的方法别名建立长期兼容层。

基础错误码包括 `E_INVALID_ARGUMENT`、`E_UNSUPPORTED`、`E_ORIGIN_DENIED`、`E_CAPABILITY_DENIED`、`E_OS_PERMISSION_DENIED`、`E_SESSION_EXPIRED`、`E_CANCELLED`、`E_TIMEOUT`、`E_CONFLICT`、`E_QUOTA`、`E_STORAGE`、`E_NETWORK` 和 `E_INTERNAL`，详解和修复动作在协议中冻结。仍在当前文档中的请求必须终止一次，不以永远 pending 表示失败。

## 8. 双层授权与系统权限

Hermit 保存自身声明权限的最近观测状态，也保存每个 Web App 的授权；Android 仍是系统权限事实来源。进程前台恢复和敏感调用前重新检查，并处理调用期间的 SecurityException、精度变化和服务关闭。

```text
用户对该 Web App 的 Hermit grant
    + 当前操作所需的 Android 权限表达式
    + 支持的系统服务、资源范围与前台条件
    = 当前这次调用是否可执行
```

权限表达式可为 ALL/ANY/精度条件，不能一律要求所有声明权限同时存在。例如近似定位只需 coarse；只有明确请求精确位置才要求 fine，用户拒绝精确而授予近似时应返回实际精度或可操作错误。

首次敏感调用显示 Hermit 原生授权界面，写明 Web App 名称、来源、能力与用途。若 Hermit 已有系统权限，不再弹 Android 系统框；当前 Web App 仍单独获得并记录 Hermit grant。若系统层缺失，由 Hermit 代为请求，获准后生效。拒绝、永久拒绝、仅本次、系统撤销、服务不可用是不同状态。

“仅本次”属于当前运行会话，切换实例或结束会话后失效；“始终允许”按 appId + trustRevision + capability + resourceScope 持久化。拒绝后不反复弹窗，由用户在应用详情重新开启。撤销 app 授权不撤销整个 APK 的系统权限；撤销系统权限使所有依赖它的调用失效，但可保留用户对各 app 的意图。

| 能力类别 | 默认 Hermit 决策 | 系统层 |
| --- | --- | --- |
| 运行信息、自有记录和文件 | 自动允许，受配额限制 | 无 runtime permission |
| 前台朗读、震动、剪贴板写入 | 自动允许，限制频率；剪贴板写入需明确交互 | 按 Adapter 实际所需 |
| 选择文件、分享、系统拍照 | 允许发起可信系统 UI，用户可取消 | SAF/授权 URI；系统 Camera Intent 不为此预申请 CAMERA |
| 语音识别、定位、剪贴板读取 | 按实例首次询问 | 麦克风/精度等实际需求 |
| Native 网络请求 | 按实例与明确目标 Origin 询问，默认无目标 | INTERNET；局域网按系统版本另检查 |
| 安装、删除、授权管理、开发连接 | Store 角色专属 | 不向普通 Web App 暴露 |

系统授权 UI 由串行 PermissionBroker 协调。同一能力请求合并，最多一个系统弹窗；返回时核对原 appId、trustRevision、session 和 documentEpoch。Android 可能已经授予整个 APK 权限，但页面已离开时不能把结果误发或授予新页面。

Android Manifest 只声明实际实现所需权限与组件。语音使用 RECORD_AUDIO；定位使用 coarse/fine；网络声明 INTERNET/ACCESS_NETWORK_STATE；TTS/识别/拍照按需配置 package visibility；FileProvider 仅暴露专用分享目录；不申请 all-files，也不预声明后台高权限。

Android 17、target 37 的 LAN 访问需检查并请求 ACCESS_LOCAL_NETWORK，WebView 的 LAN 流量继承 APK 的系统权限。这意味着 Hermit 的 Native fetch 目标限制不等于全部网页网络防火墙；普通网页 fetch、WebSocket、Service Worker 网络仍遵循 WebView/Android 规则。v1 明确不承诺逐 Web App 的全网络出口隔离。若未来要求此能力，需要新的网络隔离架构，不能用 shouldInterceptRequest 的局部拦截冒充。[Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)

首次打开已识别的 LAN 在线入口时，Runtime 在 loadUrl 前由 Broker 处理该系统权限，不等待尚未加载的页面调用 Bridge。私网 IP、明确的本地域名和用户标注的局域网入口均走此流程；解析后发现私网目标也补做检查。拒绝时保留实例，提供重试或系统设置入口；无法可靠识别的网络失败允许用户主动选择“此地址在局域网”。这不是对全部网页子资源的逐目标授权承诺。

HTTP 在线实例首次启用敏感能力时额外说明其代码可被网络替换；授权需从应用详情显式开启。此例外不取消逐实例 grant、目标校验或系统层检查。

## 9. 数据、文件与原生能力

### 9.1 持久数据的明确边界

Hermit 管理的数据与网页浏览数据是两类存储：

| 类别 | 用途 | 更新/迁移保证 |
| --- | --- | --- |
| Hermit Data / Files | 笔记、清单、配置、附件等业务数据 | 按 appId 保留，可导出恢复，与代码 release 分离 |
| Web Profile 数据 | Cookie、localStorage、IndexedDB、CacheStorage 等 | 同实例更新时保留；由 WebView 管理，不承诺跨设备完整导出 |
| 页面 JS 内存 | 临时界面状态 | 不保证进程回收后恢复 |

v1 将网页可调用的任意 SQL 收敛为 `hermit.data` 的键控 JSON 记录服务，底层仍用 Native SQLite。网页不能提交 SQL、数据库文件路径、PRAGMA 或 ATTACH。这样消除“用字符串黑名单实现 SQL 沙箱”的不确定性；代价是没有跨表 JOIN、任意 SQL 和原生全文检索。复杂查询可由 Web App 自用 IndexedDB 等 Web 存储解决，但须接受其备份边界。

首发接口为 `get(collection,key)`、`put(...,expectedRevision?)`、`delete(...,expectedRevision?)`、`scan(collection,{prefix,afterKey,limit})`、`batch(operations)`。记录返回 revision；创建专用条件与 CAS 冲突语义明确。scan 只按 key 排序，不承诺多页快照；需要多条一致更新使用一次 batch。

每实例一个 Native SQLite 数据库，表键为 `(collection,key)`，值为受限 JSON 文本。revision 对外是包含 dataGeneration 与序号的不透明字符串；序号由该 generation 全局单调计数器产生，删除再创建不重用，避免 ABA。expectedRevision 缺省表示无条件写，`absent` 表示仅创建，具体 revision 表示 CAS；指定具体 revision 时记录缺失视为冲突，`absent` 在记录不存在时满足。batch 拒绝重复目标键，一次提交全部操作与条件检查，失败全部回滚；不跨 Bridge 持有开放事务，不让 JavaScript 回调决定 SQL 锁的生命周期。首版不建立通用查询 DSL 或自研 ORM。[SQLiteDatabase API](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase)

### 9.2 文件与二进制

应用文件使用逻辑文件 ID 并始终按 appId 与 dataGeneration 解析，保存时先写临时文件再提交索引；App 代码目录只读。持久记录只保存 logicalFileId，不保存 Android 物理路径。SAF 选入文件默认复制到当前实例，移除原文件不影响已导入内容。导出走系统目标选择器，不让网页提交 Android 物理路径。

原生选择、拍照、较大 Native HTTP 响应和 TTS 导出均产生相同逻辑文件对象。只有不超过 256 KiB 的文本可经 RPC 读取；较大内容由原生流直接导入、导出或分享，不通过巨大 Base64 JSON。文件写入绑定当前实例和 dataGeneration，完成前不进入索引。分享给其他 Android 应用的只读文件使用独立副本和 URI grant，不能因切到分享目标就立即删除；该副本按短期 TTL 清理，不授予对原数据目录的访问。通用二进制分块读写与同源临时媒体 URL 不属于 v1 公共接口。

本地网关保留 `/__hermit/` 命名空间，v1 不向页面发布其中的媒体 URL。跨应用或跨 dataGeneration 即使知道 logicalFileId 也不能读取对应文件。

### 9.3 v1 能力面

| API | 首发功能 | 生命周期与限制 |
| --- | --- | --- |
| runtime | info、capabilities、事件订阅 | 设备和 API 能力探测，不读取其他实例数据 |
| app | info、reload、ready、checkUpdate | 仅当前实例；真正激活更新由应用库或预授权开发会话处理 |
| permissions | status、request | 统一 PermissionBroker |
| data | get、put、delete、scan、batch | 持久化、CAS、分页、原子批量 |
| files | 选择导入、文本创建/读取、删除、列举、导出、分享 | 逻辑 ID 隔离；大文件只走原生导入/导出/分享 |
| tts | 引擎/声音查询、speak、stop、导出音频 | 引擎可能联网；会话退出停止输出，导出用 FileHandle |
| speech | availability、start、stop、cancel | 单活跃识别、partial/final 事件；明确 on-device 可用性，不伪称离线 |
| location | getCurrent、watch、clearWatch | 前台、精度/超时/最大缓存年龄明确，后台停止 watch |
| camera | capturePhoto | 系统 Camera Intent + FileProvider，取消清临时文件 |
| share | text、file | 系统 chooser，回调不能证明对方已读取或发布 |
| clipboard | write、read | 读取受前台/系统与逐实例限制 |
| haptics | vibrate、impact | 有限时长，频率限制 |
| network | status、request | 受控 Native HTTP，普通页面优先标准 fetch |

麦克风、系统文件选择器、拍照等排他资源同时只允许一个操作，其余返回忙碌或进入有界队列。Adapter 不独立建立权限和线程体系，而是使用统一调度、生命周期和错误模型。

### 9.4 Native HTTP

用途是让自包含页面访问用户明确授权的设备或 API，解决 CORS/mixed-content 的特定集成问题。默认目标集合为空，本地虚拟 Origin 不是默认授权网络目标。

目标按规范化 scheme、host、port 和解析后的地址类别授权；持久 grant 的内部 resourceScope 同时编码 `public/private` 与 Origin，不使用字符串前缀或通配 `*`。同一域名在公网与私网解析间变化时必须重新授权，不能沿用另一地址类别的 grant；环回、链路本地、云元数据地址、Hermit 开发端口默认拒绝。Host/userinfo、代理头、跳跃头由客户端控制，不能由 JS 伪造；不自动共享 WebView Cookie、系统凭据或安装器凭据。

Native network.request 使用独立配置的 OkHttp client：关闭自动重定向和 retryOnConnectionFailure，仅 GET/HEAD 最多手工跟随 5 跳，其他方法返回重定向结果供页面决定；每跳重新验证目标和解析地址，跨 Origin 清除 Authorization、Cookie 等凭据，HTTPS 不降为 HTTP。经校验的 DNS 地址集交给实际连接使用，不能先检查再由另一解析器重查。公网授权遇到私网解析结果拒绝；代理关闭；按授权范围分隔连接池，避免复用绕过地址检查。证书和 hostname 仍按原 URL 验证。实现测试必须覆盖 DNS rebinding、IPv4-mapped IPv6 和重定向。

原生网络请求可有外部副作用，重试默认仅对明确可重试、幂等操作；POST 等不能因页面没收到响应而自动重发。默认返回小 JSON/text，大响应返回当前实例的文件 handle。安装器与 Web App 网络共用代码库，但不能共用授权身份。

## 10. Developer Deploy

Developer Deploy 是“更新已有本地实例”的窄接口，不是远程 shell 或管理员接口。用户先在应用库创建/选择实例，开启短期开发会话并明确是否部署后自动重开；外部工具只需 appId，无需知道手机私有目录。

开发会话属于 Hermit 前台生命周期，切换应用库与目标 Web App 不关闭。整个 APK 真正进入后台、锁屏、空闲超时、进程退出或用户停止时关闭连接并撤销 token；旋转等配置重建不误关。关闭前未提交上传取消，已经进入提交阶段的短事务完成并留 Operation 结果，不转成常驻服务。

默认 ADB 转发设备 loopback HTTP；LAN 为显式开启的 TLS 连接，不在不可信 Wi-Fi 上明文发送 token 和代码。LAN 使用短期自签名证书，连接配置含 `sha256//base64(SHA256(SPKI))` 公钥 pin，通过 ADB 或用户控制的本地文件传递到电脑，不能从尚未认证的 LAN 连接取得信任根。配套脚本对该开发端点强制使用 `--insecure --pinnedpubkey` 的组合：端点认证来自带外 pin，不声称通过公共 CA/hostname 校验，绝不允许单独 `-k`。证书只服务开发连接，不能改变 WebView 对外站点的 TLS 校验。

连接由用户在目标实例卡片显式开启；配置只显示在当前应用库面板，不自动写入公共目录。监听、token 与闲置计时在面板返回后立即生效，Hermit 真正退出前台后必须重新开启连接。

开发客户端不得跟随重定向，必须检测当前 curl 的公钥固定能力；不支持则给出明确错误。pin 只用于初始端点，不能让重定向变成带凭据的无校验请求。[curl 公钥固定说明](https://curl.se/libcurl/c/CURLOPT_PINNEDPUBLICKEY.html)

嵌入式传输优先使用 NanoHTTPD 核心，经 DevTransport 薄适配；不使用其通用文件服务、目录列表、multipart 管理或默认无限线程。锁定源码/版本并审核请求长度、超时和 TLS 接口，边界测试未通过前不对 LAN 开放。这里选择的是成熟小型 HTTP 解析器加窄路由，并不假设库自带资源限制。[NanoHTTPD 官方源码与说明](https://github.com/NanoHttpd/nanohttpd)

| 接口 | 语义 |
| --- | --- |
| GET /v1/status | 仅返回此 token 绑定的目标、剩余期限、活动 release 和限额 |
| PUT /v1/apps/{appId}/release | 完整 ZIP；要求 Idempotency-Key、内容摘要及 expected release |
| GET /v1/operations/{operationId} | 仅查询当前目标的操作状态，区分 installed 与 reload 结果 |
| POST /v1/apps/{appId}/reload | 仅重开已获准目标，不扩大应用管理权限 |

token 至少 256 bit 熵，只存内存、单目标、空闲 15 分钟失效；不是应用库 JS 可随意读取的全局密钥。所有请求鉴权，拒绝浏览器 Origin 请求和跨站 CORS，检查 Host，错误不暴露文件路径。上传固定 Content-Length，禁止 chunked、multipart 和压缩 HTTP body；校验后再读 body，单上传、有限连接、持续计时。接入层限制解压后的 ZIP，不能只信请求头。部署网络中断查询幂等 operation，提交成功但 reload 失败明确分开返回。

```sh
# 以下为将来产品接口示例；token 从本地受限配置读取，不放入项目或日志。
adb forward tcp:8765 tcp:8765
curl --config "$HERMIT_CURL_CONFIG" \
  --request PUT --upload-file dist.zip \
  --header "Content-Type: application/zip" \
  --header "Idempotency-Key: dev-build-104" \
  --header "X-Hermit-Expected-Release: release-103" \
  --header "X-Hermit-Content-SHA256: <dist.zip 的实际 SHA-256>" \
  http://127.0.0.1:8765/v1/apps/<appId>/release
```

v1 传完整快照。未来若添加差分上传，只能改变 materialize 的传输成本，仍需产出完整 staging、验证与提交，不逐文件覆盖运行目录。

## 11. 备份、恢复与数据生命周期

应用库导出的是“应用配置与 Hermit 管理的数据”，可包含当前本地代码；不叫完整浏览器备份。包中包含格式版本、来源、用户展示配置、记录数据、附件和内容摘要。不导出系统权限、Hermit 敏感 grants、Cookie、WebStorage、临时 token、操作句柄或不可迁移 SAF 授权。用户恢复后重新授权并重新登录。

备份默认不加密，可能包含个人内容；导出确认应准确提示并使用系统文件选择器。密码加密属于后续独立协议，不自创弱加密。没有实现时不能宣传端到端加密。

导出前暂停目标实例与写操作，读取一致的应用记录和文件清单。记录以受限 JSONL 等逻辑格式导出，避免把来自外部的任意 SQLite 文件直接作为宿主数据库打开。文件和代码流式归档并计算摘要。空间不足或中断不产生可导入的半完成备份。

恢复首先验证大小、结构、格式和摘要，再走统一 staging。默认恢复为新实例，生成新 appId 和 Web Profile；保留实例域内的 logicalFileId，使任意 JSON 中的附件引用仍成立，旧 appId 仅作来源元数据。短期 FileHandle/media URL 不得作为持久附件引用或进入备份；恢复后重新取得 handle，记录 revision 也按新 generation 重新产生。

替换已有实例数据必须由用户在应用库选择目标。单 WebView 模型确保进入应用库时目标页面会话已经取消；随后数据导入新的 dataGeneration，再用 Registry 条件更新切换指针。所有旧写操作绑定原 generation，失败保持旧 generation，切换成功后才清理旧数据。开发连接在替换前关闭。

v1 提供两条明确路径：完整备份恢复会创建新实例、新 appId 和新 Web Profile；替换已有实例时只恢复 Hermit 记录与附件，保留目标来源、代码、appId 和桌面入口，同时提升 trustRevision、使旧敏感 grants 失效并关闭开发连接。v1 不提供“以备份完整覆盖现有实例代码和来源”的第三种隐式模式。

BackupValidator 与代码 PackageValidator 共享路径/流量校验底座，但有独立容量预算。默认备份支持 1 GiB 传输/展开、50,000 文件；用户提高数据配额后，导出前必须同步核验备份上限与预计峰值空间，必要时明确提高该次备份预算并写入头部。导入按声明量、用户批准的预算和本机容量预检，不能让本机合法导出的包仅因复用了代码 ZIP 上限而无法恢复。

APK 覆盖升级保留原 appId、来源、activeReleaseId、数据 generation、grants 和快捷方式；Registry 显式迁移，失败保留原文件进入恢复 UI，禁止删除重建空库。默认关闭 Android Auto Backup，并配置适用版本的数据提取规则；不同厂商设备迁移行为需实测，不能靠一个布尔配置保证完整排除。

## 12. 数据结构、执行模型与诊断

Registry 为单独系统 SQLite，不向公共 data API 开放。v1 核心表为 instances、releases、grants、system_permission_observations、operations、profile_cleanup 和预留的 diagnostic_events。SourceBinding 作为领域概念收敛存放在 instances 的 `source_adapter/source_spec` 字段，等出现多来源或 channel 的真实需求再拆表。实例保存 activeReleaseId 与 activeDataGeneration；release 表按 appId 外键归属；删除与清理任务在同一 Registry 事务记录。

```text
files/
  system/registry.sqlite
  instances/<appId>/
    releases/<releaseId>/web/
    staging/<operationId>/
    data/<generationId>/records.sqlite
    data/<generationId>/files/
  temporary/exports/
assets/
  store/
  bridge/hermit-v1.js
```

开外键、显式迁移、参数绑定；需要一致提交的 Registry 更新走单事务。业务数据库与 Registry 不跨连接伪装成一个事务。逻辑层限制记录/文件总量，真实磁盘写入仍处理 ENOSPC。

UI 线程只负责 WebView、系统 UI 和轻量消息验证；协程调度器承担 I/O，传输、解压、数据库、能力任务各有并发上限。RuntimeSession scope 管短生命周期操作；应用级 OperationCoordinator 管可查询的安装/备份任务，但不保证进程退出后继续执行。它靠持久日志恢复，不靠后台常驻。

初始资源预算如下，是防止失控的上限，不是实测性能承诺。集中配置，后续根据参考设备数据调整：

| 资源 | v1 初始上限 |
| --- | --- |
| manifest / RPC JSON | 64 KiB / 256 KiB；嵌套深度 16 |
| ZIP 传输 / 展开 / 单文件 / 文件数 | 64 MiB / 256 MiB / 64 MiB / 10,000；异常压缩比另拒绝 |
| 实例 Hermit 数据总量 | 逻辑文件 256 MiB / 10,000 个；记录 JSON 128 MiB / 100,000 条 |
| 单 JSON 记录 / scan / batch | 64 KiB；100 条且响应不超 RPC 上限；100 操作且请求不超 RPC 上限 |
| 普通 RPC / 瞬时队列 | 每文档最多 16 个待决请求；消息 256 KiB、JSON 嵌套 16 层，超出立即错误 |
| Native HTTP | 受 Bridge 在途上限约束；连接 15 秒、读写各 30 秒；内联 1 MiB，文件响应 64 MiB |
| 部署连接 / 上传 | 最多 4 执行线程、8 个排队连接、1 个上传；会话闲置 15 分钟，ZIP 64 MiB |
| 诊断 | 按需生成脱敏状态摘要；不保留页面正文、文件内容、token 或设备序列号 |

磁盘预检考虑 staging、新旧代码、数据与导出同时存在的峰值；不只检查 ZIP 压缩大小。压缩包拒绝绝对/穿越路径、链接、重复及规范化冲突路径、特殊文件、加密包、异常元数据和保留路径，不能只检测字符串 `../`。

诊断记录方法、实例、Origin、operationId、结果、耗时和字节数，不默认记录语音正文、文件内容、数据值、Cookie、请求体或 token。URL 去 userinfo/query/fragment。开发日志默认本地，导出前显示范围；产品不要求云遥测。

原生恢复 UI 覆盖 WebView 不可用、renderer 退出、活动目录缺失、Registry 迁移失败和权限变化。只重建故障会话，不删除数据“修复”；启动到恢复页仍须让用户能导出可读数据或诊断。

## 13. 扩展方式与质量合同

扩展只沿三条边界进行：SourceAdapter 增加输入来源；CapabilityDescriptor + Adapter 增加手机能力；Artifact materialize 增加传输策略。它们共享实例、授权、Operation、资源限制和诊断，不新增并行身份系统。原生扩展必须随签名 APK 发布，页面包不加载 dex、so 或任意反射类。

新增云端账号、后台任务、多进程或全网络隔离时，需要对应的真实场景和单独架构决策。现阶段的“可扩展”指有稳定合同和替换点，不表示预先构建一个动态插件平台。

| 合同 | 不可违反的行为 |
| --- | --- |
| 身份 | 外部 appId、URL、manifest 或 handle 不能替换 Native 执行身份 |
| 管理边界 | 普通 Web App 和开发 token 均不能成为 Store 管理员 |
| 版本 | 未验证代码不激活；一个会话不混读两个 release |
| 授权 | 系统 grant 不自动变为全部 Web App 的敏感 grant |
| 数据 | 更新代码不清理业务数据；回滚代码不谎称回滚数据 |
| 隔离 | 不支持 Web Profile 隔离时不提供共享回退运行 |
| 结果 | 每个有效调用有终态；提交成功与页面显示成功分别记录 |
| 恢复 | 失败可诊断、可重试；不以清空数据伪造恢复成功 |
| 交付 | APK、签名、测试、设备和限制有可核验记录 |

v1 用户界面以中文为主，文本资源集中可本地化；空状态、进度、失败、取消、拒绝均有可操作表达。应用库和原生对话框支持 TalkBack、字体放大、深浅色、边到边布局和键盘避让。Hermit 只保证自己 UI 的可访问性，不能替第三方页面自动修复。

参考设备分别记录：应用库/本地自包含页面的冷启动与首屏 p50/p95、Bridge 往返、20 MiB 包导入/部署、50 次实例切换的内存与句柄趋势。S1 建立数值基准，后续相同条件 p95 回退超过 20% 必须解释和修复或记录决定；没有基准前不编造绝对秒数或 APK 大小保证。

## 14. 重构决策与执行入口

| 原设计的主要问题 | 本版选择 | 获得的收益与明确代价 |
| --- | --- | --- |
| 各来源与 sourceType 混成多套流程 | 两种运行形态 + 一个本地安装事务 | 来源可扩展；GitHub 首版 archive 会下载非目标内容 |
| Java/少依赖被当成不可改合同 | Kotlin 协程 + 适量成熟库 | 减少手工状态与网络代码；增加明确锁定的依赖 |
| Web Profile 不支持就共享运行 | Runtime feature 硬门槛 | 隔离语义一致；牺牲部分旧 provider 兼容 |
| Local 与 SW 各管理一套缓存 | Local 禁 SW，网关管理离线代码 | 更新可预测；SW 专用应用须适配 |
| 任意 SQL 加黑名单 | Native SQLite 支撑受限记录事务 API | 无 SQL 越界入口；首版无通用 SQL/全文查询 |
| Store 关闭即停止开发服务 | Hermit 前台开发会话 | 连续推送与预览可用；后台仍不接受部署 |
| 删除身份但“保留数据” | 明确备份 + 删除任务 | 恢复路径确定；首版不提供回收站 |
| 代码回滚/备份承诺过宽 | 代码、Hermit 数据、Web 数据分开定义 | 不夸大可恢复性；恢复后可能需重新登录 |
| 多 Origin bridge 与标准敏感 Web API 双入口 | 一个主 Origin，敏感能力统一 Hermit API | 权限合同收敛；部分站点需适配 |
| P0/P1/P2 只有功能清单 | 工作包、依赖、原型门禁、测试和交付证据 | 可以连续执行，不能靠阶段名称认定产品完成 |

下一步按 [开发计划](../plans/hermitapp-v1-development-plan.md) 从环境与架构验证进入实现。计划中的技术验证失败由执行者据实修正内部实现并同步合同；不能静默削弱隔离、数据或交付要求，也不因常规阶段完成反复要求用户批准。
