# happ 编写指南

Hermit 的 happ（WebApp）开发基线是纯原生 HTML + JavaScript + CSS。源文件就是运行文件，不需要 React/Vue、Vite/Webpack、Node/npm、构建或转译。这个原则同样适用于 AI 生成的 happ、官方示例和 HermitUI。

Hermit 不为框架提供额外支持，但不会拒绝框架已经生成的最终静态产物。只要它遵守普通页面的入口、相对资源路径、WebView 能力和权限边界，就按普通页面添加。Hermit 不安装源码依赖、不自动寻找 dist、不构建源码、不提供专属框架适配。依赖服务端 SSR 的源码不能当作本地静态页面运行，这是运行形态限制，不是框架黑名单。

## 最小应用

创建一个目录，保存以下三个文件。`hermit.json` 可选，简单页面只要根目录有 `index.html` 即可。

`index.html`：

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>我的工具</title>
  <link rel="stylesheet" href="style.css">
  <script src="app.js" defer></script>
</head>
<body>
  <h1>我的工具</h1>
  <button id="save"><i class="fa-solid fa-floppy-disk" aria-hidden="true"></i> 保存</button>
  <p id="status" role="status"></p>
</body>
</html>
```

`style.css`：

```css
body { margin: 24px; background: #fff; color: #18181b; font: 16px system-ui; }
button { padding: 12px 18px; border: 0; border-radius: 12px; background: #18181b; color: #fff; }
button i { color: #b8ed70; margin-right: 8px; }
```

`app.js`：

```js
const save = document.querySelector('#save');
const status = document.querySelector('#status');
save.disabled = true;
function ready() { save.disabled = false; }
window.addEventListener('hermitready', ready);
if (window.hermit && window.hermit.isReady) ready();
save.onclick = async () => {
  save.disabled = true;
  try {
    await hermit.data.put({ collection: 'notes', key: 'hello', value: { text: '你好' } });
    status.textContent = '已保存';
  } catch (error) {
    status.textContent = error.message;
  } finally { save.disabled = false; }
};
```

在“添加 → 本地导入”中选择这个文件夹，或者把目录内容归档为 ZIP 后导入。编辑原文件后需要再次部署才能更新 Hermit 已保存的快照。ZIP 是归档与传输格式，不是前端构建。

正式发布包建议在根目录提供严格的 `hermit.json` schema 2：

```json
{
  "schema": 2,
  "happId": "com.example.tool",
  "name": "我的工具",
  "version": { "code": 1, "name": "1.0.0" },
  "entry": "index.html",
  "routing": "hash",
  "icon": "assets/icon.png",
  "liveUrl": "https://example.com/tool/",
  "updateUrl": "https://example.com/tool/latest.json"
}
```

`happId` 是发布链的稳定标识，不代表域名所有权；`version.code` 必须递增。`entry/routing/icon/liveUrl/updateUrl/display` 可省略。`icon` 指向包内的 PNG、JPEG、WebP 或 GIF 图片；安装时 Hermit 会居中裁切并保存为 192×192 PNG，应用卡片和新增的桌面入口会直接使用它。`display.orientation` 可设为 `unspecified`、`portrait` 或 `landscape`；`display.keyboard` 可设为 `resize` 或 `overlay`。显示策略只在该 happ 前台时生效，`overlay` 会让输入法覆盖页面底部而不压缩 WebView。没有 `liveUrl` 的包是纯本地 happ：页面不能访问网络，但仍可使用获准的 Native Bridge。没有清单的 `index.html` 目录继续兼容，不过不具备自动识别发布链和恢复历史实例的保证。

Hermit 把每次安装展开为只读 release，更新先生成新 release 再原子切换，并至少保留前一版供回退。包代码不能写入自身目录；动态文件使用 `hermit.files`，结构化数据使用 `hermit.data`。可选的 `hermit.sig` 只证明同一发布公钥的版本连续性，不自动取得任何权限。

必须同时保留 `hermitready` 监听和即时 `isReady` 检查：现代 WebView 会在文档起始阶段提供 API，旧 WebView 的线上实时页面可能要等首次加载完成后才收到兼容注入。不要在脚本第一行无条件调用 `hermit`。网页 Cookie/WebStorage 使用共享资料空间并遵守同源规则；旧式桥接模式另外不能保证 iframe 级 Bridge 隔离。页面可通过 `hermit.runtime.info()` 的 `runtimeMode` 判断本地或实时运行，通过 `bridgeMode`、`siteDataPolicy` 和 `isolatedProfiles` 解释宿主兼容环境。happ 可以调用 `hermit.app.setRuntimeMode({ runtimeMode: "local" | "live" })` 切换自己的运行方式；宿主只接受当前实例已经具备的本地 release 或 `liveUrl`，切换结果写入 Registry 并重新加载当前实例。

## 完整离线 Font Awesome Free

Hermit 1.1.0 随 APK 内置 Font Awesome Free 7.3.1，含实心、常规、品牌，共 2,883 个图标/样式组合及四个 WOFF2 字体文件；同名不同样式分别计数。Pro 不包含在内。官方资源按固定 npm 版本获取并提交到宿主仓库，页面作者不使用 npm。

本地和在线 WebApp 的应用 Origin 均自动加载公共样式。直接使用标准 HTML：

```html
<i class="fa-solid fa-camera" aria-hidden="true"></i>
<i class="fa-regular fa-heart" aria-hidden="true"></i>
<i class="fa-brands fa-github" aria-hidden="true"></i>
<button aria-label="设置"><i class="fa-solid fa-gear" aria-hidden="true"></i></button>
```

在 Hermit 的“图标库”中搜索名称、英文关键词或常见中文词，筛选样式，点击图标复制可直接粘贴的 HTML。只有图标的按钮需添加可访问名称。不是每个图标都有 regular 样式，以目录为准。

需要显式声明时，可在 head 中添加以下样式；这也是无需 Bridge 的使用方式：

```html
<link rel="stylesheet" href="/__hermit/icons/fontawesome/css/all.min.css">
```

动态 DOM 同样使用原生 JS：

```js
await hermit.icons.load(); // 等待 CSS 完成加载，不是原生权限请求
document.body.append(hermit.icons.create('heart', { style: 'regular', label: '喜欢' }));
// 也可使用 window.HermitIcons；version、stylesheet 可用于检查当前资源。
```

样式就绪与字体解码是两件事；截图或测量字形前，可再用 `document.fonts.load('900 16px "Font Awesome 7 Free"')` 等待对应字体。普通页面无须等待字体即可建立 DOM。

公共资源在当前 Origin 的 `/__hermit/icons/fontawesome/` 下被 APK 拦截提供，不访问 CDN、不复制到每个 WebApp，也不开放宿主私有目录。`/__hermit/` 为保留命名空间，不应被应用自己占用。HTTP(S) 在线页面使用同源路径，不引入 HTTPS 页面的混合内容问题。

在线站点有自己的 CSP 或 Service Worker 时，仍需遵守它们。CSP 的 `style-src`（或更具体的 `style-src-elem`）与 `font-src` 应允许同源资源；仅允许 nonce/hash 的站点需自行声明或授权这条样式。Service Worker 不应缓存或吞掉保留资源请求，可将该路径交给 `fetch(request)`；Hermit 不会自动修改网站安全策略。离开 Hermit 的浏览器没有此虚拟路径，若需兼容浏览器预览，可以自行按官方许可放置相同的 CSS/字体，并在浏览器环境引用它们。

## 开发、更新与边界

Hermit 提供全应用“智能体开发模式”：在 HermitUI 的开发 Tab 开启，向可信智能体提供手机显示的局域网 HTTP 基址和六位数字密码；没有 Wi-Fi 时可仅启动 USB 服务并执行 `adb forward tcp:8766 tcp:8766`。局域网和 USB 使用同一套接口、同一个密码和同一份开发数据。所有电脑共用密码，不配对、不绑定电脑或客户端；修改密码后旧密码立即失效。密码只用于 Hermit 已开放的开发管理接口，不替代系统和逐应用能力授权。局域网 HTTP 未加密，只适用于可信网络，不应公网暴露。

访问根地址获取连接说明；`/.well-known/hermit-agent` 提供当前工具与指南版本，`/mcp` 是标准 Streamable HTTP 接口，`/skills/hermit-device/SKILL.md` 是随 APK 更新的动态指南。智能体每次开发先获取当前指南，不把密码写入页面、Skill、日志或仓库。Skill 安装和 MCP 注册由客户端执行，不能承诺所有智能体软件收到地址就自动完成注册；服务同时提供无需第三方 Python 库的 HTTP/stdio 助手。

HermitUI 仍不是可写开发目标。官方 HermitWeb 通过网站发布链路更新后，如果 HermitUI 当前可见，智能体可以调用 `hermit_reload_shell` 让它从已配置的地址重新加载；传入 `runtimeMode: "online"` 可选择官方实时页面，普通进程重启保留该选择，APK 替换则按恢复机制回到内置 UI。该操作只刷新页面，不读取或修改 HermitUI 文件。

每个普通 happ 只有一份可变开发工作副本。智能体必须先让目标进入开发模式，之后才能按 `expectedDevRevision` 创建、覆盖、移动、重命名或删除任意文件；正式 release 在发布前始终不变。常规保存只传变化文件并原子切换清单，CSS 单独变化时热替换样式，其他变化在同一 WebView 中刷新当前路径。返回 `renderOperationId` 时可等待页面就绪确认，并读取有界的控制台、HTTP 和页面错误诊断。HermitUI 不出现在可写目标中，保留身份也会被 Native 拒绝。

新 happ 先通过 `hermit_create_dev_app` 安装最小包并进入开发模式。现有 happ 使用 `hermit_enter_dev_mode`，随后用 `hermit_sync_dev_changes` 或助手的 `sync-dir` / `watch` 增量更新。完成后，`hermit_build_dev_package` 生成带新版本号的确定性 ZIP；`hermit_install_dev_package` 通过标准更新事务安装并切回正式版本。开发副本继承原 `appId` 的数据、登录态与授权，发布也不会新建实例。详见 [统一开发工作区计划](../plans/hermitapp-unified-dev-workspace-plan.md)。

本地相对路径建议写成 `./app.js`、`./style.css`。有 `liveUrl` 时，本地 release 会挂载到这个真实 URL：包内存在的 GET/HEAD 静态文件优先，包内不存在的同源路径和 POST 等动态请求才访问服务器，因此 `./app.js` 读取本地文件，而缺失的 `/api/getinfo` 正常请求远端。页面不需要也不应注入 `<base>` 或改写 `fetch`。纯本地 happ 使用 Hermit 派生的内部 URL，所有页面网络请求均被阻止。

标准 Origin 始终由当前 URL 的协议、主机和有效端口计算。跨 Origin 图片、CSS、字体和音视频可作为被动资源加载；脚本、iframe、Worker、fetch/XHR、WebSocket、表单和导航默认阻止，只有用户为该实例显式开启跨域网络后才放行。用户修改实例 `liveUrl` 就是在确认新的网页 Origin；包更新不能静默覆盖这个选择。

## 系统通知

通知是逐 happ 授权能力。首次调用时，Hermit 按 Android 当前状态请求系统通知权限或显示 happ 授权确认；用户可随时在 HermitUI 关闭。即时通知和单次、每日、每周、每月、每年计划均由 Native 执行，后台不会运行页面 JavaScript：

```js
await hermit.notifications.notify({ id: 'saved', title: '已保存', body: '笔记已安全保存' });
await hermit.notifications.schedule({
  notification: { id: 'daily-check', title: '每日打卡', body: '现在可以完成今天的记录' },
  triggerAt: new Date(2026, 8, 14, 15, 0).getTime(),
  recurrence: 'daily'
});
```

计划采用设备系统时间和系统时区；Android 12+ 还需用户允许“闹钟和提醒”。`cancel`、`cancelAll` 和 `getScheduled` 用于管理计划。通知被点击并进入 happ 后，页面在调用 `app.ready()` 后收到 `notifications.opened` 事件。

有 `liveUrl` 的 happ 可以用 `notifications.setEndpoint({ endpoint: '/api/hermit/notifications' })` 登记严格同源的 HTTP(S) 地址。Hermit 最低每 15 分钟统一 GET 一次，也会在登记时触发一次补查；请求只携带该 Origin Cookie 中名为 `notify-token` 的字段。响应只接受 `notifications`、`cancel` 与 `cursor` 数据，不执行代码。纯本地 happ、跨 Origin 和本机回环地址不能登记 endpoint。

业务数据建议用 `hermit.data` / `hermit.files`，代码更新不会清除这些数据。`hermit.files.pickImage()` 使用 Android 照片选择器让用户从图库选择图片，并返回经过尺寸和体积约束的 JPEG data URL；`hermit.files.pickInline()` 用于用户主动选择不超过调用方上限的小型文件（例如待提交给 ASR 的短音频），不把 Android 物理路径暴露给页面。`hermit.files.import({ accept: "video/*" })` 可将用户选择的大文件保存为逻辑文件，供后续上传使用。`hermit.files.readText()` 默认只内联 256 KiB，处理模型返回的较大 JSON 时可显式传 `maxBytes`，宿主最高限制为 8 MiB。页面使用相机、定位等能力仍须经过逐应用授权与相应 Android 系统授权。图标资源不涉及敏感权限。

跨域流式响应使用 `network.openStream/readStream/closeStream`。`openStream` 与 `network.request` 接受同样的 URL、方法、Header 和超时设置，也接受 `bodyLogicalFileId` 或由文本与逻辑文件组成的 `multipart`；宿主逐 Origin 授权，跨 Origin 重定向会移除凭据。页面应循环读取不超过 64 KiB 的 Base64 字节块，并在取消、离开当前任务或解析失败时调用 `closeStream`。流 ID 只属于创建它的页面会话，页面退出后宿主自动关闭。

录音开始后，宿主约每 100 ms 发出一次 `audio.recording.level` 事件，数据包含 `recordingId`、原始 `amplitude`、0 到 1 的 `level` 和 `peakDb`。页面应按 `recordingId` 过滤事件，用它绘制真实录音电平，并在停止、取消或页面离开时解除监听。

需要双向文字或二进制帧时使用 `network.openSocket/readSocket/sendSocket/closeSocket`。`openSocket` 接受完整的 `ws://` 或 `wss://` 地址和自定义 Header，并复用 HTTP(S) 对应 Origin 的网络授权；`readSocket` 是最长 60 秒的有界长轮询，返回文字、Base64 二进制、关闭、错误或超时事件。单帧最多 1 MiB，单连接收发各最多 64 MiB，队列与连接数也受限；页面必须串行读取、处理背压并在结束或取消时关闭连接。Socket ID 与流 ID 一样按页面会话和数据代次隔离，页面退出后自动释放。

页面不能根据 Android 版本、手机品牌或服务商猜测硬件和系统服务。先调用 `hermit.runtime.capabilities()`：稳定 API 始终存在，`implemented` 表示 Hermit 已实现，`supported` 表示当前设备有实现所需的硬件或系统服务，`usable` 表示当前页面角色可调用；`features` 只报告可移植能力，例如 TTS 是否可用、语音识别支持连续模式还是一次性系统界面、传感器清单以及 Wi-Fi、蓝牙、红外和闪光灯事实，不向 happ 泄露厂商组件并要求其分支适配。再用 `permissions.status()` 区分逐 happ grant 与 Android 权限，不能把硬件缺失、系统开关关闭、Android 未授权和 happ 未授权混成一个布尔值。不支持的调用返回 `E_UNSUPPORTED`，页面据此隐藏入口或提供降级说明。

原始麦克风录音不依赖系统语音识别：调用 `hermit.audio.startRecording()`，完成后用 `hermit.audio.stopRecording()` 得到持久化的 `logicalFileId`；取消或页面退到后台会立即释放麦克风并删除临时文件。录音默认最多五分钟，可在 1 秒至 30 分钟之间调整。`hermit.audio.play()` 可以播放 `hermit.files` 中的音频，普通页面自带或网络音频也可使用标准 `<audio>` / Web Audio，播放遵守用户手势策略。TTS 和语音识别是另外两项可选系统服务：页面用 `tts.availability()`、`tts.voices()`、`speech.availability()` 与 `speech.languages()` 查询统一能力。`tts.voices()` 的语言和声音来自当前 TTS 引擎，`speech.languages()` 的候选来自当前识别服务；返回空目录时页面应跟随系统默认，不能自行补造候选。页面可调用 `tts.speak()`、连续事件式 `speech.start()` 或带系统界面的一次性 `speech.recognizeOnce()`。用户在 HermitUI 中选择系统引擎、声音、识别服务、语言和离线偏好；页面不会收到服务包名，也不需要知道背后是讯飞、小米、华为或其他 Android 兼容实现。手机没有相应服务时 availability 返回稳定的不可用状态，调用返回可处理的 `E_UNSUPPORTED`，不会自动连接未获用户选择的第三方云服务。

传感器调用先用 `sensors.availability()` 读取逐项硬件清单，再订阅实际存在的类型。`orientation` 由旋转向量计算方位角、俯仰角和翻滚角，可作为指南针；陀螺仪不存在时 `gyroscope.available=false`。订阅最高 60 Hz、只在当前前台页面会话存活，切换页面或退到后台自动释放。计步器额外需要 Android 活动识别权限。

```js
const catalog = await hermit.sensors.availability();
const compass = await hermit.sensors.watch({ type: 'orientation', rateHz: 10 });
const off = hermit.on('sensors.changed', sample => {
  if (sample.subscriptionId === compass.subscriptionId) console.log(sample.values);
});
off();
await hermit.sensors.clearWatch({ subscriptionId: compass.subscriptionId });
```

Wi-Fi 扫描使用 `wifi.scan()`，受 Android 位置信息开关、权限和扫描节流约束；`wifi.requestNetwork()` 只请求 Android 10+ 的临时网络并显示系统确认，不能静默保存、删除或切换系统网络。BLE 使用 `bluetooth.scan/connect/services/read/write/subscribe`，连接、扫描和所有 GATT 资源在页面离开或 Hermit 进入后台时释放；经典蓝牙配对与系统级配置通过 `bluetooth.openSettings()` 完成。`infrared` 只暴露 Android 标准消费级红外发射，系统 API 没有通用红外接收能力。所有地址、密码和传感器数据都只回给当前会话，不写入诊断日志。

仓库的 Gradle/JDK/Android SDK 用来构建宿主 APK；Node 脚本用于宿主契约检查及离线资源维护。它们不是 WebApp 的开发依赖。`sdk/hermit-api.d.ts` 仅给编辑器提示，不要求 TypeScript。

参考资源：[Font Awesome 官方自托管说明](https://docs.fontawesome.com/web/setup/host-yourself/webfonts)、[Free 开源仓库及许可](https://github.com/FortAwesome/Font-Awesome)。完整许可随 APK 提供，可在“设置 → 开源许可”查看。
