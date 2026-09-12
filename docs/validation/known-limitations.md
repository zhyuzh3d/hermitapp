# Hermit 当前版本已知限制

- 最低系统为 Android 10 / API 29。只要设备有可创建的系统 WebView，Hermit 会自动选择隔离模式、兼容消息模式或传统 `JavascriptInterface` 模式运行页面。旧国产 provider 因此不再被 feature 门槛直接阻断，但兼容模式会共享网页 Cookie/站点存储，传统桥还不能隔离 iframe 消息；不要在这种模式中运行不信任的页面或脚本。Hermit 的记录、文件和授权仍按 appId 管理，但这不等于浏览器级沙箱。
- 本地包是静态构建产物，根目录必须有 `index.html`；v1 不运行本地 Node/Python 服务，也不承诺 Service Worker。复杂后端应使用在线服务或 Native HTTP。
- WebView 标准网络请求不构成逐 Web App 防火墙。Hermit 只对 `hermit.network.request` 实施目标 Origin、地址类别、DNS 固定和重定向约束；在线页面及普通 `fetch/WebSocket` 仍遵循 WebView 和 Android 网络规则。
- GitHub 是海外可选来源，仅覆盖无需登录即可下载的仓库归档，不处理 private repo、Git submodule 或 Git LFS；在国内不可达、限流或故障时不影响本地 ZIP/目录、局域网推送与通用 HTTPS 包。HTTPS ZIP 支持摘要校验，但当前没有独立的发布者签名体系。
- 备份默认不加密，包含 Hermit 记录、逻辑文件、配置和本地代码；不包含 Cookie、WebStorage、系统权限、逐应用授权和开发 token。敏感备份应保存到可信位置。
- 语音识别与 TTS 是否离线、支持哪些语言和声音由手机已安装引擎决定；Hermit 不把“服务缺失”伪装成权限成功。
- 原始麦克风录音与语音识别分离，录音不需要识别引擎；它仍取决于麦克风硬件、逐应用授权和 Android RECORD_AUDIO 权限。录音仅在前台会话存活，默认五分钟、最多 30 分钟，后台或取消时未提交的临时音频会被删除。
- 二维码识别使用 APK 内置 CameraX 取景和 ZXing 解码，不依赖 Google Play 服务、外网或运行时模型下载。Hermit 会为应用库扫码单独请求相机权限；拒绝时仍可手工输入。扫码只预填 HTTP(S) 网址，不会自动安装。
- “支持”页面依赖 `hermit.10knet.com` 和当前网络。Hermit 会先预检并提供本地重试页，但不能保证捐赠站点或其站外支付服务可用；站外链接由系统应用接管。
- v1 使用统一 Hermit 图标。桌面固定入口的副标题/标识由 Launcher 决定，不支持每个页面应用上传自定义图标；删除实例后部分 OEM Launcher 可能留下需手动移除的失效入口。
- 单 Activity/单前台页面模型不提供多窗口并行页面、后台采集或常驻开发服务。开发连接进入后台即关闭，LAN 部署只适合受信网络上的短期开发。
- 历史验证覆盖 API 31 与 API 37 ARM64 模拟器；荣耀 Android 11 旧 WebView 的兼容 Runtime 正在形成实体机证据。API 29、无 GMS 本地扫码、厂商相机、系统授权 UI、Launcher 和真实 Wi-Fi 仍需补齐目标实体手机证据。
- 构建输入已锁定并可复核，但没有声称不同主机上的 APK 可逐字节复现。应用商店上架、隐私政策 URL、地区合规、品牌/著作权和业务内容审核不包含在本地 APK 技术验证中。
