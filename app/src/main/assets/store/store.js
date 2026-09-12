(() => {
  "use strict";
  try {
    if (!window.CSS || !CSS.supports || !CSS.supports("selector(:is(*))")) document.documentElement.classList.add("legacy-webview");
  } catch (_) { document.documentElement.classList.add("legacy-webview"); }
  if (!Element.prototype.replaceChildren) {
    Element.prototype.replaceChildren = function (...nodes) {
      while (this.firstChild) this.removeChild(this.firstChild);
      this.append(...nodes);
    };
  }
  const $ = selector => document.querySelector(selector);
  const $$ = selector => [...document.querySelectorAll(selector)];
  const HERMIT_WEB_VERSION = "1.7.0";
  const state = { apps: [], selected: null, deploy: null, filter: "all", iconStyle: "all", iconLimit: 60, modals: [], view: "favorites", addToFavorites: true, addDraft: null, managedEpoch: 0 };
  const notice = $("#notice");
  const say = (message, error = false) => {
    $("#noticeText").textContent = message;
    $("#noticeIcon").className = "fa-solid " + (error ? "fa-circle-exclamation" : "fa-circle-check");
    notice.classList.toggle("error", error); notice.classList.remove("hidden");
    clearTimeout(say.timer);
    if (!error) say.timer = setTimeout(() => notice.classList.add("hidden"), 4500);
  };
  $("#dismissNotice").onclick = () => notice.classList.add("hidden");
  async function busy(button, work) {
    if (button.disabled) return;
    const children = [...button.childNodes];
    const spinner = document.createElement("i"); spinner.className = "fa-solid fa-circle-notch fa-spin"; spinner.setAttribute("aria-hidden", "true");
    if (button.classList.contains("icon-button")) button.replaceChildren(spinner);
    else button.replaceChildren(spinner, document.createTextNode("处理中…"));
    button.disabled = true; button.setAttribute("aria-busy", "true");
    try { return await work(); }
    catch (error) { say(error.message || "操作失败，请重试。", true); }
    finally { button.disabled = false; button.removeAttribute("aria-busy"); button.replaceChildren(...children); }
  }
  const bind = (selector, work) => { $(selector).onclick = event => busy(event.currentTarget, work); };
  const focusable = root => [...root.querySelectorAll("button:not(:disabled),input,a[href],summary,[tabindex='0']")].filter(el => el.getClientRects().length && !el.closest("[inert]"));
  function syncModals() {
    const topEntry = state.modals[state.modals.length - 1];
    const top = topEntry && topEntry.element;
    $("#shell").inert = !!top;
    $$(".modal").forEach(el => { el.inert = el !== top; });
    document.body.classList.toggle("modal-open", !!top);
  }
  function open(selector) {
    const element = $(selector);
    if (state.modals.some(item => item.element === element)) return;
    state.modals.push({ element, previous: document.activeElement });
    element.classList.remove("hidden"); syncModals();
    // Focus a close button, not an input: opening a sheet should not summon the keyboard.
    const initialFocus = element.querySelector("[data-close]") || focusable(element)[0];
    if (initialFocus) initialFocus.focus({ preventScroll: true });
  }
  function close(selector) {
    const index = state.modals.findIndex(item => item.element === $(selector));
    if (index < 0) return;
    const removed = state.modals.splice(index);
    removed.forEach(item => item.element.classList.add("hidden")); syncModals();
    const previous = removed[0].previous;
    const topEntry = state.modals[state.modals.length - 1];
    const fallback = (topEntry && topEntry.element) || $("#shell");
    if (previous && previous.isConnected && !previous.closest("[inert]") && !previous.disabled) previous.focus({ preventScroll: true });
    else {
      const fallbackFocus = focusable(fallback)[0];
      if (fallbackFocus) fallbackFocus.focus({ preventScroll: true });
    }
  }
  let confirmResult;
  function confirmAction(title, message, label = "继续", destructive = false) {
    $("#confirmTitle").textContent = title; $("#confirmMessage").textContent = message;
    $("#acceptConfirm").textContent = label;
    $("#acceptConfirm").classList.toggle("danger", destructive);
    open("#confirmPanel"); $("#cancelConfirm").focus();
    return new Promise(resolve => { confirmResult = resolve; });
  }
  function finishConfirm(accepted) {
    const result = confirmResult; confirmResult = null;
    close("#confirmPanel"); if (result) result(accepted);
  }
  $("#cancelConfirm").onclick = () => finishConfirm(false);
  $("#acceptConfirm").onclick = () => finishConfirm(true);
  window.hermitStoreBack = () => {
    if (confirmResult) { finishConfirm(false); return true; }
    const modal = state.modals[state.modals.length - 1];
    if (modal) { close("#" + modal.element.id); return true; }
    if (state.view === "icons") { showView("settings"); return true; }
    if (state.view !== "favorites") { showView("favorites"); return true; }
    if ($("#searchApps").value || state.filter !== "all") { resetFilters(); return true; }
    return false;
  };
  $$("[data-close]").forEach(button => { button.onclick = () => close(button.dataset.close); });
  $$(".modal").forEach(modal => { modal.onclick = event => { if (event.target === modal) window.hermitStoreBack(); }; });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && window.hermitStoreBack()) event.preventDefault();
    if (event.key !== "Tab" || !state.modals.length) return;
    const topEntry = state.modals[state.modals.length - 1];
    const items = focusable(topEntry.element), first = items[0], last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); if (last) last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); if (first) first.focus(); }
  });

  function selected(group, key, value) {
    $$(group + " button").forEach(button => {
      const active = button.dataset[key] === value;
      button.classList.toggle("selected", active); button.setAttribute("aria-pressed", String(active));
    });
  }
  function showView(view, runSideEffect = true) {
    state.view = view;
    $("#favoriteTopbar").classList.toggle("hidden", view !== "favorites");
    const visibleView = ["favorites", "all"].includes(view) ? "libraryView" : view + "View";
    $$("main > .view").forEach(el => el.classList.toggle("hidden", el.id !== visibleView));
    $$(".bottom-nav [data-view]").forEach(button => {
      const active = button.dataset.view === view;
      button.classList.toggle("active", active);
      if (active) button.setAttribute("aria-current", "page"); else button.removeAttribute("aria-current");
    });
    if (["favorites", "all"].includes(view)) filterApps();
    window.scrollTo(0, 0);
    if (view === "icons") loadCatalog().catch(error => { $("#iconCount").textContent = "图标目录读取失败，请切换页面重试。"; say(error.message, true); });
    if (view === "development") refreshAgent();
  }
  $$("[data-view]").forEach(button => { button.onclick = () => showView(button.dataset.view); });
  $(".brand").onclick = event => { event.preventDefault(); showView("favorites"); };
  let theme = "system";
  try { theme = localStorage.getItem("hermit.theme") || "system"; } catch (_) {}
  function applyTheme(value) {
    theme = ["system", "light", "dark"].includes(value) ? value : "system";
    if (theme === "system") delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = theme;
    selected("#themeChoices", "themeChoice", theme);
    try { localStorage.setItem("hermit.theme", theme); } catch (_) {}
  }
  applyTheme(theme);
  $$("[data-theme-choice]").forEach(button => { button.onclick = () => applyTheme(button.dataset.themeChoice); });

  const sourceLabels = { "online-manifest": "线上安装包", "https-package": "线上安装包", github: "GitHub（海外可选）", zip: "本地 ZIP", import: "本地 ZIP", directory: "本地文件夹", agent: "本地创建", online: "线上网址" };
  function appSource(app) { return app.source || (app.mode === "online" ? "online" : "local"); }
  function appRuntime(app) { return app.runtimeMode || (app.mode === "online" ? "live" : "local"); }
  function hasLocal(app) { return typeof app.localAvailable === "boolean" ? app.localAvailable : app.mode === "local"; }
  function hasLive(app) { return typeof app.liveAvailable === "boolean" ? app.liveAvailable : app.mode === "online"; }
  function sourceLabel(app) { return sourceLabels[app.sourceAdapter] || (appSource(app) === "online" ? "线上 happ" : "本地 happ"); }
  function runtimeLabel(app) { return appRuntime(app) === "live" ? "线上实时运行" : "本地运行"; }
  function filterApps() {
    const query = $("#searchApps").value.trim().toLocaleLowerCase();
    const candidates = state.view === "favorites" ? state.apps.filter(app => app.favorite) : state.apps;
    let visible = 0;
    $$(".app-card").forEach(card => {
      const inCollection = state.view !== "favorites" || card.dataset.favorite === "true";
      const matched = inCollection && (!query || card.dataset.search.includes(query)) && (state.filter === "all" || card.dataset.source === state.filter);
      card.classList.toggle("hidden", !matched); if (matched) visible++;
    });
    $("#clearSearch").classList.toggle("hidden", !query);
    const trulyEmpty = candidates.length === 0 && !query && state.filter === "all";
    $("#empty").classList.toggle("hidden", !trulyEmpty);
    $("#noResults").classList.toggle("hidden", visible !== 0 || (!query && state.filter === "all"));
    $("#resultsCount").textContent = candidates.length ? visible + " 个应用" : "";
    $("#emptyTitle").textContent = state.view === "favorites" ? "收藏你的第一个应用" : "添加你的第一个应用";
    $("#emptyMessage").textContent = state.view === "favorites" ? "点亮应用卡片上的爱心，常用工具就会集中在这里。" : "添加在线网址或导入原生 HTML、JavaScript 和 CSS 页面。";
  }
  function resetFilters() { $("#searchApps").value = ""; state.filter = "all"; selected("#appFilters", "filter", "all"); filterApps(); }
  $("#searchApps").oninput = filterApps;
  $("#clearSearch").onclick = () => { $("#searchApps").value = ""; filterApps(); $("#searchApps").focus(); };
  $("#resetFilters").onclick = resetFilters;
  $$("[data-filter]").forEach(button => { button.onclick = () => { state.filter = button.dataset.filter; selected("#appFilters", "filter", state.filter); filterApps(); }; });
  async function pin(app) {
    const value = await hermit.host.apps.pin({ appId: app.appId });
    say(value.requested ? "已请求添加到手机桌面，请确认系统提示。" : "当前桌面不支持固定图标。");
  }
  async function refresh() {
    const result = await hermit.host.apps.list({});
    state.apps = result.apps || [];
    $("#loading").classList.add("hidden");
    const root = $("#apps"); root.replaceChildren();
    for (const app of state.apps) {
      const fragment = $("#appTemplate").content.cloneNode(true), card = fragment.querySelector(".app-card");
      card.dataset.appId = app.appId;
      card.dataset.search = [app.name, app.startUrl, app.liveUrl, app.sourceAdapter, sourceLabel(app), runtimeLabel(app)].join(" ").toLocaleLowerCase();
      card.dataset.source = appSource(app);
      card.dataset.favorite = String(!!app.favorite);
      fragment.querySelector(".app-name").textContent = app.name;
      fragment.querySelector(".app-source").textContent = runtimeLabel(app) + (appRuntime(app) === "live" ? " · " + app.startUrl : " · 数据独立保存");
      const color = ["blue", "violet", "orange", "green"][[...app.appId].reduce((n,c) => n + c.charCodeAt(0), 0) % 4];
      fragment.querySelector(".app-icon").classList.add(color);
      const appIcon = fragment.querySelector(".app-icon"), appIconMark = appIcon.querySelector("i");
      if (app.iconDataUrl) { appIcon.style.backgroundImage = "url(" + JSON.stringify(app.iconDataUrl).slice(1,-1) + ")"; appIcon.classList.add("custom"); appIconMark.classList.add("hidden"); }
      else appIconMark.className = "fa-solid " + (appSource(app) === "local" ? "fa-cube" : "fa-globe");
      fragment.querySelector(".source-badge").textContent = sourceLabel(app);
      fragment.querySelector(".launch").onclick = event => busy(event.currentTarget, () => hermit.host.apps.launch({ appId: app.appId }));
      const favorite = fragment.querySelector(".favorite"), favoriteIcon = favorite.querySelector("i");
      favorite.classList.toggle("active", !!app.favorite);
      favorite.setAttribute("aria-pressed", String(!!app.favorite));
      favorite.setAttribute("aria-label", app.favorite ? "取消收藏" : "收藏应用");
      favorite.title = app.favorite ? "取消收藏" : "收藏应用";
      favoriteIcon.className = (app.favorite ? "fa-solid" : "fa-regular") + " fa-heart";
      favorite.onclick = event => busy(event.currentTarget, async () => {
        const updated = await hermit.host.apps.favorite({ appId: app.appId, favorite: !app.favorite });
        app.favorite = !!updated.favorite;
        await refresh();
        say(app.favorite ? "已加入收藏。" : "已取消收藏。");
      });
      fragment.querySelector(".pin").onclick = event => busy(event.currentTarget, () => pin(app));
      fragment.querySelector(".manage").onclick = () => openManage(app).catch(error => say(error.message, true));
      root.append(fragment);
    }
    filterApps();
  }
  const capabilityLabels = { "camera.capture": "拍照", "microphone.record": "麦克风录音", speech: "语音识别", "location.approximate": "大致位置", "location.precise": "精确位置", "clipboard.read": "读取剪贴板", network: "网络请求" };
  async function openManage(app) {
    const epoch = ++state.managedEpoch;
    state.selected = app;
    $("#editName").value = app.name; $("#editUrl").value = app.liveUrl || "";
    $("#manageSubtitle").textContent = app.name + " · " + sourceLabel(app) + " · " + runtimeLabel(app);
    $("#editUrlLabel").classList.toggle("hidden", appSource(app) !== "online" || !hasLive(app));
    $("#updateApp").classList.toggle("hidden", !["online-manifest", "https-package", "github"].includes(app.sourceAdapter));
    $("#developerDetails").classList.toggle("hidden", !hasLocal(app));
    $("#releaseDetails").classList.toggle("hidden", !hasLocal(app));
    $$("#runtimeChoices button").forEach(button => {
      const mode = button.dataset.runtimeMode;
      const available = mode === "local" ? hasLocal(app) : hasLive(app);
      button.disabled = !available;
      button.classList.toggle("selected", appRuntime(app) === mode);
      button.setAttribute("aria-pressed", String(appRuntime(app) === mode));
    });
    $("#runtimeHint").textContent = appSource(app) === "local"
      ? "本地 happ 只能本地运行。"
      : hasLocal(app) && hasLive(app)
        ? "此线上 happ 已安装本地代码；默认本地运行，也可切换为直接加载线上页面。"
        : hasLocal(app) ? "此线上 happ 只有本地安装包，没有可实时运行的页面地址。" : "此线上 happ 尚无本地安装包，只能实时运行。";
    $$("#managePanel details").forEach(el => { el.open = false; });
    $("#grants").textContent = "正在读取授权…"; $("#releases").textContent = "正在读取版本…";
    open("#managePanel");
    const [grantResult, releaseResult] = await Promise.allSettled([
      hermit.host.permissions.list({ appId: app.appId }),
      hasLocal(app) ? hermit.host.apps.releases({ appId: app.appId }) : Promise.resolve(null)
    ]);
    if (epoch !== state.managedEpoch) return;
    const grantRoot = $("#grants"); grantRoot.replaceChildren();
    if (grantResult.status === "rejected") grantRoot.textContent = "授权信息读取失败，请重新打开管理面板。";
    else {
      const grants = grantResult.value.grants || [];
      if (!grants.length) grantRoot.textContent = "尚无持久授权。使用相关功能时会向你申请。";
      for (const grant of grants) {
        const row = document.createElement("div"); row.className = "row";
        const label = document.createElement("span");
        label.textContent = (capabilityLabels[grant.capability] || grant.capability) + (grant.scope ? " · " + grant.scope : "") + " · " + (grant.decision === "allow" ? "已允许" : "已拒绝");
        const revoke = document.createElement("button"); revoke.textContent = "重置";
        revoke.onclick = () => busy(revoke, async () => {
          await hermit.host.permissions.revoke({ appId: app.appId, capability: grant.capability, scope: grant.scope });
          row.remove(); say("已重置，下次使用会重新询问。");
          if (!grantRoot.children.length) grantRoot.textContent = "尚无持久授权。";
        });
        row.append(label, revoke); grantRoot.append(row);
      }
    }
    const releaseRoot = $("#releases"); releaseRoot.replaceChildren();
    if (releaseResult.status === "rejected") { releaseRoot.textContent = "版本信息读取失败，请重新打开管理面板。"; return; }
    const versions = releaseResult.value;
    if (!versions) return;
    for (const release of versions.releases) {
      const row = document.createElement("div"); row.className = "row";
      const label = document.createElement("span");
      label.textContent = (release.versionName || release.releaseId.slice(0,8)) + (release.releaseId === versions.activeReleaseId ? " · 当前使用" : "");
      row.append(label);
      if (release.releaseId !== versions.activeReleaseId) {
        const activate = document.createElement("button"); activate.textContent = "切换";
        activate.onclick = () => busy(activate, async () => {
          if (!await confirmAction("切换代码版本", "页面代码会切换到此版本，现有数据保留。请确保旧版代码与当前数据兼容。", "切换版本")) return;
          await hermit.host.apps.rollback({ appId: app.appId, releaseId: release.releaseId });
          close("#managePanel"); say("代码版本已切换。"); await refresh();
        });
        row.append(activate);
      }
      releaseRoot.append(row);
    }
  }
  function resetAddIcon() {
    state.addDraft.iconDataUrl = null;
    $("#addIconPreview").style.backgroundImage = "";
    $("#addIconPreview").classList.remove("custom");
    $("#addIconPreview").replaceChildren(Object.assign(document.createElement("i"), { className:"fa-solid fa-image" }));
  }
  function setAddIcon(dataUrl) {
    state.addDraft.iconDataUrl = dataUrl;
    $("#addIconPreview").replaceChildren();
    $("#addIconPreview").style.backgroundImage = "url(" + JSON.stringify(dataUrl).slice(1,-1) + ")";
    $("#addIconPreview").classList.add("custom");
  }
  function openAdd(kind, value = {}) {
    state.addToFavorites = state.view === "favorites";
    state.addDraft = { kind, token:value.token || null, iconDataUrl:null };
    $("#favoriteOnAdd").classList.toggle("hidden", !state.addToFavorites);
    $("#urlField").classList.toggle("hidden", kind !== "online");
    $("#pathField").classList.toggle("hidden", kind !== "directory");
    $("#versionField").classList.toggle("hidden", kind === "online");
    $("#url").value = value.url || "";
    $("#sourcePath").value = value.path || "";
    $("#name").value = value.name || (value.url ? new URL(value.url).hostname : "");
    $("#addVersion").value = value.version || "1.0.0";
    $("#addSourceLabel").textContent = kind === "directory" ? "文件夹快照" : value.scanned ? "二维码链接" : "在线网址";
    $("#manifestStatus").textContent = kind === "directory" ? (value.manifestFound ? "已找到并解析 hermit.json，可在下方修改后添加。" : "未找到 hermit.json，已生成基础配置，请检查后添加。") : "将检查同源 hermit-install.json；有安装包时默认下载到本地运行，否则线上实时运行。";
    resetAddIcon();
    if (value.iconDataUrl) setAddIcon(value.iconDataUrl);
    open("#addPanel");
    setTimeout(() => (kind === "online" ? $("#url") : $("#name")).focus({ preventScroll:true }), 80);
  }
  $("#addUrl").onclick = () => openAdd("online");
  bind("#addFolder", async () => {
    const value = await hermit.host.apps.pickDirectory({});
    if (!value.cancelled) openAdd("directory", value);
  });
  async function scanQr() {
    const value = await hermit.host.apps.scanQr({});
    if (value.cancelled) return;
    openAdd("online", { url:value.url, scanned:true });
  }
  bind("#scanQr", scanQr);
  function validUrl(selector, httpsOnly = false) {
    const input = $(selector), raw = input.value.trim();
    try { const url = new URL(raw); if (!["http:", "https:"].includes(url.protocol) || (httpsOnly && url.protocol !== "https:")) throw new Error(); return raw; }
    catch (_) { input.focus(); throw new Error(httpsOnly ? "请输入完整的 HTTPS 地址。" : "请输入以 https:// 或 http:// 开头的完整网址。"); }
  }
  async function installed(value, message) {
    if (value && value.cancelled) return;
    $("#name").value = ""; state.addDraft = null; close("#addPanel"); resetFilters(); showView(state.addToFavorites ? "favorites" : "all"); await refresh(); say(message);
  }
  bind("#pickAppIcon", async () => {
    const value = await hermit.host.apps.pickIcon({});
    if (value.cancelled) return;
    setAddIcon(value.dataUrl);
  });
  bind("#confirmAdd", async () => {
    const draft = state.addDraft;
    if (!draft) throw new Error("添加信息已失效，请重新选择来源。");
    const name = $("#name").value.trim();
    if (!name) { $("#name").focus(); throw new Error("应用名称不能为空。"); }
    const common = { name, version:$("#addVersion").value.trim(), favorite:state.addToFavorites, iconDataUrl:draft.iconDataUrl || "" };
    const value = draft.kind === "directory"
      ? await hermit.host.apps.confirmDirectory(Object.assign(common, { token:draft.token }))
      : await hermit.host.apps.installOnline(Object.assign(common, { url:validUrl("#url") }));
    const message = draft.kind === "directory" ? "本地 happ 已添加。" : value.installStrategy === "local" ? "线上 happ 已安装并采用本地运行。" : "线上 happ 已添加并采用实时运行。";
    await installed(value, message);
  });
  bind("#saveApp", async () => {
    const app = state.selected;
    if (!$("#editName").value.trim()) { $("#editName").focus(); throw new Error("应用名称不能为空。"); }
    const url = appSource(app) === "online" && hasLive(app) ? validUrl("#editUrl") : (app.liveUrl || "");
    const value = await hermit.host.apps.update({ appId: app.appId, name: $("#editName").value.trim(), url });
    if (value.cancelled) return;
    close("#managePanel"); await refresh(); say("应用设置已保存。");
  });
  $$("#runtimeChoices [data-runtime-mode]").forEach(button => {
    button.onclick = event => busy(event.currentTarget, async () => {
      const app = state.selected, runtimeMode = event.currentTarget.dataset.runtimeMode;
      if (!app || appRuntime(app) === runtimeMode) return;
      if (runtimeMode === "live" && !await confirmAction("开启线上实时运行？", "Hermit 将直接加载线上页面，代码可随服务器变化；切换运行方式会重置此 happ 的持久授权。", "开启实时运行")) return;
      if (runtimeMode === "local" && !await confirmAction("切换到本地运行？", "Hermit 将运行已经下载到手机的代码版本；切换运行方式会重置此 happ 的持久授权。", "切换到本地")) return;
      await hermit.host.apps.setRuntimeMode({ appId:app.appId, runtimeMode });
      close("#managePanel"); await refresh(); say(runtimeMode === "live" ? "已切换到线上实时运行。" : "已切换到本地运行。");
    });
  });
  bind("#managePin", () => pin(state.selected));
  bind("#updateApp", async () => {
    const app = state.selected;
    await hermit.host.apps.updateFromSource({ appId: app.appId });
    await refresh(); await openManage(state.apps.find(x => x.appId === app.appId)); say("已更新到最新代码，数据已保留。");
  });
  bind("#backupApp", async () => {
    const app = state.selected;
    if (!await confirmAction("导出应用备份", "备份包含此应用的 Hermit 记录和附件，默认不加密。请选择可信的保存位置。", "选择保存位置")) return;
    const value = await hermit.host.backup.export({ appId: app.appId });
    if (!value.cancelled) say("备份已导出。");
  });
  bind("#restoreBackupSettings", async () => {
    if (!await confirmAction("恢复应用备份", "将创建独立的新应用，不继承登录状态和页面授权。", "选择备份")) return;
    const value = await hermit.host.backup.restore({});
    if (!value.cancelled) { resetFilters(); showView("all"); await refresh(); say("已恢复“" + value.name + "”。"); }
  });
  bind("#restoreAppData", async () => {
    const app = state.selected;
    if (!await confirmAction("替换“" + app.name + "”的数据？", "原有 Hermit 记录和附件会被备份内容覆盖，页面授权重置。此操作无法撤销，请先导出当前备份。", "选择备份并替换", true)) return;
    const value = await hermit.host.backup.restoreData({ appId: app.appId });
    if (!value.cancelled) { close("#managePanel"); await refresh(); say("数据已恢复，页面授权已重置。"); }
  });
  bind("#removeApp", async () => {
    const app = state.selected;
    if (!await confirmAction("删除“" + app.name + "”？", "将删除此应用及其全部本机数据。此操作无法撤销，请确认已经保存需要的备份。", "删除应用", true)) return;
    await hermit.host.apps.remove({ appId: app.appId }); close("#managePanel"); await refresh(); say("应用已删除。");
  });
  async function openDeploy(mode) {
    const app = state.selected;
    const value = await hermit.host.deploy.start({ appId: app.appId, mode }); state.deploy = value;
    const connection = value.mode === "lan" ? ["export HERMIT_ADDRESS='" + value.address + "'", "export HERMIT_SPKI_PIN='" + value.spkiPin + "'"] : ["adb forward tcp:8765 tcp:8765"];
    $("#deployConfig").textContent = [...connection, "Address: " + value.address, "App-Id: " + app.appId, "Authorization: Bearer " + value.token,
      value.spkiPin ? "TLS: 自签名证书 + SPKI 公钥固定" : "Transport: ADB-forwarded device loopback",
      "上传还需要 Idempotency-Key、X-Hermit-Expected-Release 与 X-Hermit-Content-SHA256。"].join("\n");
    open("#deployPanel");
  }
  bind("#developApp", () => openDeploy("adb")); bind("#developLanApp", () => openDeploy("lan"));
  const copy = async (text, label) => { await hermit.clipboard.write({ text, label }); say(label + "已复制。"); };
  let agentState = null, agentPoll = false;
  function renderAgent(value) {
    agentState = value;
    $("#agentStatus").textContent = value.active ? "已开启 · 所有应用可开发" : "未开启" + (value.reason ? " · " + value.reason : "");
    $(".developer-card").classList.toggle("active", !!value.active);
    $("#developmentDot").classList.toggle("hidden", !value.active);
    $("#agentToggle").classList.toggle("on", !!value.active);
    $("#agentToggle").setAttribute("aria-checked", String(!!value.active));
    $("#agentToggle").setAttribute("aria-label", value.active ? "关闭智能体开发模式" : "开启智能体开发模式");
    const firstIp = (value.addresses || [])[0];
    const address = value.address || (firstIp ? "http://" + firstIp + ":8766" : "");
    $("#agentUrl").textContent = address || "未连接 Wi-Fi";
    $("#agentUrl").dataset.address = address;
    if (document.activeElement !== $("#agentPassword")) $("#agentPassword").value = value.password || "";
    $("#agentEvents").textContent = (value.events || []).slice().reverse().map(event => new Date(event.time).toLocaleTimeString() + "  " + event.tool + "  " + event.result).join("\n") || "暂无操作";
  }
  async function refreshAgent() {
    if (agentPoll) return;
    agentPoll = true;
    try { renderAgent(await hermit.host.agent.status()); } finally { agentPoll = false; }
  }
  bind("#agentToggle", async () => {
    if (agentState && agentState.active) {
      await hermit.host.agent.stop();
      renderAgent(await hermit.host.agent.status());
      say("开发模式已关闭。");
      return;
    }
    const address = agentState && (agentState.addresses || [])[0];
    if (!address) throw new Error("请连接 Wi-Fi 后重试。");
    renderAgent(await hermit.host.agent.start({ address }));
    say("开发模式已开启，请仅向可信智能体分享连接信息。");
  });
  bind("#saveAgentPassword", async () => {
    const password = $("#agentPassword").value.trim();
    if (!/^[0-9]{6}$/.test(password)) throw new Error("密码必须是 6 位数字。");
    const value = await hermit.host.agent.resetPassword({ password });
    renderAgent(value);
    if (value.password !== password) throw new Error("当前 APK 版本暂不支持指定密码，请安装新版。");
    say("新密码已生效，旧密码已失效。");
  });
  $("#agentPassword").oninput = event => { event.currentTarget.value = event.currentTarget.value.replace(/\D/g, "").slice(0, 6); };
  bind("#copyAgentAddress", async () => {
    const address = $("#agentUrl").dataset.address;
    if (!address) throw new Error("请连接 Wi-Fi 后重试。");
    await copy(address, "局域网地址");
  });
  setInterval(async () => {
    if (document.hidden) return;
    try { await refreshAgent(); } catch (_) {}
  }, 3000);
  bind("#copyDeploy", () => copy($("#deployConfig").textContent, "开发配置"));
  bind("#stopDeploy", async () => { await hermit.host.deploy.stop({}); state.deploy = null; close("#deployPanel"); $("#deployConfig").textContent = ""; say("开发连接已停止。"); });
  bind("#licensesButton", async () => { const value = await hermit.host.licenses.info({}); $("#licensesOutput").textContent = value.text; open("#licensesPanel"); });
  bind("#diagnosticsButton", async () => { const report = await hermit.host.diagnostics.info({}); $("#diagnosticsOutput").textContent = JSON.stringify(report, null, 2); open("#diagnosticsPanel"); });
  bind("#copyDiagnostics", () => copy($("#diagnosticsOutput").textContent, "诊断报告"));
  bind("#githubButton", () => hermit.host.about.openRepository({}));

  let shellState = null;
  function renderShellState(value) {
    shellState = value;
    const online = value.configuredMode === "online";
    const currentVersion = value.runningMode === "online" ? HERMIT_WEB_VERSION : value.localVersion;
    $("#brandDot").classList.toggle("online", online);
    $("#shellVersionSwitch").textContent = "当前界面版本：" + currentVersion;
    $("#useLocalShell").disabled = !online;
    $("#useLocalShell").classList.toggle("hidden", !online);
  }
  async function loadShellStatus() { renderShellState(await hermit.host.shell.status({})); }
  let shellVersionTaps = 0, shellVersionTapTimer;
  $("#shellVersionSwitch").onclick = event => {
    clearTimeout(shellVersionTapTimer);
    shellVersionTaps += 1;
    shellVersionTapTimer = setTimeout(() => { shellVersionTaps = 0; }, 1200);
    if (shellVersionTaps < 3) return;
    shellVersionTaps = 0;
    busy(event.currentTarget, async () => {
      if (shellState && shellState.configuredMode === "online") return;
      await hermit.host.shell.setMode({ mode:"online" });
    });
  };
  bind("#useLocalShell", async () => {
    if (shellState && shellState.configuredMode === "local") return;
    await hermit.host.shell.setMode({ mode:"local" });
  });
  bind("#updateLocalShell", async () => {
    const value = await hermit.host.shell.updateLocal({});
    renderShellState(value);
    say("本地界面已更新到 " + value.localVersion + "。");
  });
  window.hermitShellUnavailable = message => {
    say(message || "官网实时界面暂时不可用，当前使用本地版。", true);
    loadShellStatus().catch(() => {});
  };

  async function loadAbout() {
    const value = await hermit.host.about.info({});
    $("#aboutVersion").textContent = value.version + "（" + value.versionCode + "）";
    $("#topApkVersion").textContent = value.version;
    $("#aboutAuthor").textContent = value.author;
    $("#aboutAppId").textContent = value.applicationId;
  }

  let catalogPromise;
  function loadCatalog() {
    if (!catalogPromise) catalogPromise = new Promise((resolve, reject) => {
      const script = document.createElement("script"); script.src = "icon-catalog.js";
      script.onload = resolve; script.onerror = () => { catalogPromise = null; script.remove(); reject(new Error("离线图标目录读取失败。")); };
      document.head.append(script);
    });
    return catalogPromise.then(renderIcons);
  }
  const synonyms = { "相机":"camera", "拍照":"camera", "文件":"file", "文件夹":"folder", "搜索":"search", "设置":"gear", "用户":"user", "心":"heart", "收藏":"star", "删除":"trash", "添加":"plus", "音乐":"music", "声音":"volume", "下载":"download", "上传":"upload", "主页":"home", "房子":"house", "位置":"location", "时间":"clock", "日历":"calendar", "锁":"lock", "安全":"shield", "图片":"image", "邮件":"envelope", "消息":"message", "通知":"bell", "手机":"mobile", "电脑":"computer", "返回":"arrow-left", "分享":"share", "编辑":"pen", "电话":"phone", "视频":"video", "网络":"wifi", "云":"cloud", "工具":"tool", "列表":"list", "购物":"cart", "书":"book", "地球":"globe", "代码":"code", "麦克风":"microphone" };
  function renderIcons() {
    if (!window.hermitIconCatalog) return;
    const raw = $("#searchIcons").value.trim().toLowerCase(), query = synonyms[raw] || raw;
    const items = window.hermitIconCatalog.icons.filter(icon => (state.iconStyle === "all" || icon.style === state.iconStyle) && (!query || [icon.name,icon.label,...icon.terms].join(" ").toLowerCase().includes(query)));
    const root = $("#iconGrid"); root.replaceChildren();
    for (const icon of items.slice(0,state.iconLimit)) {
      const button = document.createElement("button"); button.className = "catalog-icon";
      button.setAttribute("aria-label", "复制 " + icon.style + " " + icon.name + " 图标代码");
      const mark = hermit.icons.create(icon.name, { style: icon.style });
      const label = document.createElement("span"); label.textContent = icon.name;
      const style = document.createElement("small"); style.textContent = { solid:"实心",regular:"常规",brands:"品牌" }[icon.style];
      button.append(mark,label,style);
      button.onclick = () => busy(button, () => copy('<i class="fa-' + icon.style + ' fa-' + icon.name + '" aria-hidden="true"></i>', "图标代码"));
      root.append(button);
    }
    $("#iconCount").textContent = items.length ? items.length + " 个图标 · 已显示 " + Math.min(items.length,state.iconLimit) : "没有匹配的图标，试试英文名称或其他关键词。";
    $("#moreIcons").classList.toggle("hidden", state.iconLimit >= items.length);
  }
  $("#searchIcons").oninput = () => { state.iconLimit = 60; renderIcons(); };
  $$("[data-style]").forEach(button => { button.onclick = () => { state.iconStyle = button.dataset.style; state.iconLimit = 60; selected("#iconFilters","style",state.iconStyle); renderIcons(); }; });
  $("#moreIcons").onclick = () => { state.iconLimit += 60; renderIcons(); };
  let initialized = false;
  function start() {
    if (initialized) return; initialized = true;
    showView("favorites", false);
    Promise.all([refresh(), refreshAgent(), loadAbout(), loadShellStatus()]).catch(error => {
      initialized = false; $("#loading").replaceChildren();
      const retry = document.createElement("button"); retry.textContent = "重新读取应用库"; retry.onclick = start;
      $("#loading").append(retry); say(error.message, true);
    });
  }
  addEventListener("hermitready", start);
  if (window.hermit && window.hermit.isReady) start();
})();
