# WebApp 编写指南

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

必须同时保留 `hermitready` 监听和即时 `isReady` 检查：现代 WebView 会在文档起始阶段提供 API，旧 WebView 的线上实时页面可能要等首次加载完成后才收到兼容注入。不要在脚本第一行无条件调用 `hermit`。网页 Cookie/WebStorage 使用共享资料空间并遵守同源规则；旧式桥接模式另外不能保证 iframe 级 Bridge 隔离。页面可通过 `hermit.runtime.info()` 的 `runtimeMode` 判断本地或实时运行，通过 `bridgeMode`、`siteDataPolicy` 和 `isolatedProfiles` 解释宿主兼容环境。

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

Hermit 1.2 新增全应用“智能体开发模式”：在应用库 → 设置开启，向可信智能体提供手机显示的局域网 HTTP 基址和六位数字密码。所有电脑共用同一个长期有效密码，不配对、不绑定电脑或客户端；修改密码后旧密码立即失效。密码只用于 Hermit 已开放的开发管理接口，不替代系统和逐应用能力授权。HTTP 未加密，仅适用于可信局域网，不应公网暴露。

访问根地址获取连接说明；`/.well-known/hermit-agent` 提供当前工具与指南版本，`/mcp` 是标准 Streamable HTTP 接口，`/skills/hermit-device/SKILL.md` 是随 APK 更新的动态指南。智能体每次开发先获取当前指南，不把密码写入页面、Skill、日志或仓库。Skill 安装和 MCP 注册由客户端执行，不能承诺所有智能体软件收到地址就自动完成注册；服务同时提供无需第三方 Python 库的 HTTP/stdio 助手。

主链保持原生文件编辑 → 增量文件事务或完整目录 ZIP 发布 → 打开/刷新。MCP 提供应用/文件/版本查询、新建、代码更新和回滚；更新要求当前 releaseId 与唯一 requestId，防止多台电脑互相覆盖。所有发布激活不可变代码快照，保留 appId、业务数据、登录态与授权。前台运行 WebApp 时连接继续有效；Hermit 进入后台或空闲 30 分钟后停服，再次开启仍使用原密码。详见 [智能体开发实施计划](../plans/hermitapp-agent-development-plan.md)。

推荐工作流是编辑原生页面文件、导入或部署文件快照、在手机刷新。应用“管理 → 开发与更新”提供 USB 与局域网部署入口。`scripts/pack.sh` 只归档文件，`scripts/deploy.sh` 负责校验与上传，不进行 JS/CSS 编译。

本地相对路径建议写成 `./app.js`、`./style.css`；在线页面照常用自己的 HTTP(S) 服务。通用 Web 标准模块可使用，但不要把 npm 包名直接交给浏览器解析。需要外部 API 的页面不能因本地安装就宣称完全离线。

业务数据建议用 `hermit.data` / `hermit.files`，代码更新不会清除这些数据。页面使用相机、定位等能力仍须经过逐应用授权与相应 Android 系统授权。图标资源不涉及敏感权限。

页面不能根据 Android 版本或品牌猜测硬件和系统服务。先调用 `hermit.runtime.capabilities()`：稳定 API 始终存在，`implemented` 表示 Hermit 已实现，`supported` 表示当前设备有实现所需的硬件或系统服务，`usable` 表示当前页面角色可调用；`audio.features` 进一步区分 `microphoneRecording` 与 `speakerPlayback`。不支持的调用返回 `E_UNSUPPORTED`，页面据此隐藏入口或提供降级说明。

原始麦克风录音不依赖系统语音识别：调用 `hermit.audio.startRecording()`，完成后用 `hermit.audio.stopRecording()` 得到持久化的 `logicalFileId`；取消或页面退到后台会立即释放麦克风并删除临时文件。录音默认最多五分钟，可在 1 秒至 30 分钟之间调整。`hermit.audio.play()` 可以播放 `hermit.files` 中的音频，普通页面自带或网络音频也可使用标准 `<audio>` / Web Audio，播放遵守用户手势策略。TTS 和语音识别是另外两项可选系统服务：手机没有相应引擎时分别报告不支持，绝不假装成功，也不自动连接云服务。

仓库的 Gradle/JDK/Android SDK 用来构建宿主 APK；Node 脚本用于宿主契约检查及离线资源维护。它们不是 WebApp 的开发依赖。`sdk/hermit-api.d.ts` 仅给编辑器提示，不要求 TypeScript。

参考资源：[Font Awesome 官方自托管说明](https://docs.fontawesome.com/web/setup/host-yourself/webfonts)、[Free 开源仓库及许可](https://github.com/FortAwesome/Font-Awesome)。完整许可随 APK 提供，可在“设置 → 开源许可”查看。
