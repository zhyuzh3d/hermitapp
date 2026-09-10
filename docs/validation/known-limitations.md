# Hermit v1.0.0 已知限制

- 最低系统为 Android 12 / API 31；页面 Runtime 还要求当前 WebView 支持 Multi-Profile、WebMessageListener 和 document-start script。缺少这些 feature 时只能使用原生恢复工具，更新 WebView 后再运行页面应用。
- 本地包是静态构建产物，根目录必须有 `index.html`；v1 不运行本地 Node/Python 服务，也不承诺 Service Worker。复杂后端应使用在线服务或 Native HTTP。
- WebView 标准网络请求不构成逐 Web App 防火墙。Hermit 只对 `hermit.network.request` 实施目标 Origin、地址类别、DNS 固定和重定向约束；在线页面及普通 `fetch/WebSocket` 仍遵循 WebView 和 Android 网络规则。
- GitHub 来源仅覆盖无需登录即可下载的仓库归档，不处理 private repo、Git submodule 或 Git LFS；匿名 API 限额和上游可用性由 GitHub 决定。HTTPS ZIP 支持摘要校验，但 v1 没有独立的发布者签名体系。
- 备份默认不加密，包含 Hermit 记录、逻辑文件、配置和本地代码；不包含 Cookie、WebStorage、系统权限、逐应用授权和开发 token。敏感备份应保存到可信位置。
- 语音识别与 TTS 是否离线、支持哪些语言和声音由手机已安装引擎决定；Hermit 不把“服务缺失”伪装成权限成功。
- v1 使用统一 Hermit 图标。桌面固定入口的副标题/标识由 Launcher 决定，不支持每个页面应用上传自定义图标；删除实例后部分 OEM Launcher 可能留下需手动移除的失效入口。
- 单 Activity/单前台页面模型不提供多窗口并行页面、后台采集或常驻开发服务。开发连接进入后台即关闭，LAN 部署只适合受信网络上的短期开发。
- 当前验证覆盖 API 31 与 API 37 ARM64 模拟器；厂商相机、系统授权 UI、Launcher、WebView 和真实 Wi-Fi 的差异仍需目标实体手机验证。
- 构建输入已锁定并可复核，但没有声称不同主机上的 APK 可逐字节复现。应用商店上架、隐私政策 URL、地区合规、品牌/著作权和业务内容审核不包含在本地 APK 技术验证中。
