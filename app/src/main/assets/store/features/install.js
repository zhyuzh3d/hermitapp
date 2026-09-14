(() => {
  "use strict";
  const H = window.HermitShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction, selected } = H.ui;
  const { resetFilters } = H.features.library;
  const { showView, cachedViewState } = H.navigation;
  function resetAddIcon() {
    state.addDraft.customIconDataUrl = null;
    state.addDraft.defaultIconDataUrl = null;
    $("#addIconPreview").style.backgroundImage = "";
    $("#addIconPreview").classList.remove("custom");
    $("#addIconPreview").replaceChildren(Object.assign(document.createElement("i"), { className:"fa-solid fa-image" }));
  }
  function showAddIcon(dataUrl) {
    $("#addIconPreview").replaceChildren();
    $("#addIconPreview").style.backgroundImage = "url(" + JSON.stringify(dataUrl).slice(1,-1) + ")";
    $("#addIconPreview").classList.add("custom");
  }
  function setCustomAddIcon(dataUrl) {
    state.addDraft.customIconDataUrl = dataUrl;
    showAddIcon(dataUrl);
  }
  function openAdd(kind, value = {}) {
    state.addToFavorites = state.view === "favorites";
    state.addDraft = { kind, token:value.token || null, customIconDataUrl:null, defaultIconDataUrl:value.iconDataUrl || null };
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
    if (value.iconDataUrl) showAddIcon(value.iconDataUrl);
    open("#addPanel");
    setTimeout(() => (kind === "online" ? $("#url") : $("#name")).focus({ preventScroll:true }), 80);
  }
  bind("#addZip", async () => {
    state.addToFavorites = state.view === "favorites";
    await installed(await host.call("apps.importZip", { favorite: state.addToFavorites }), "本地 ZIP 已导入。");
  });
  $("#addUrl").onclick = () => openAdd("online");
  bind("#addFolder", async () => {
    const value = await host.call("apps.pickDirectory", {});
    if (!value.cancelled) openAdd("directory", value);
  });
  async function scanQr() {
    const value = await host.call("apps.scanQr", {});
    if (value.cancelled) { say("已取消操作。"); return; }
    openAdd("online", { url:value.url, scanned:true });
  }
  bind("#scanQr", scanQr);
  function validUrl(selector, httpsOnly = false) {
    const input = $(selector), raw = input.value.trim();
    try { const url = new URL(raw); if (!["http:", "https:"].includes(url.protocol) || (httpsOnly && url.protocol !== "https:")) throw new Error(); return raw; }
    catch (_) { input.focus(); throw new Error(httpsOnly ? "请输入完整的 HTTPS 地址。" : "请输入以 https:// 或 http:// 开头的完整网址。"); }
  }
  async function installed(value, message) {
    if (value && value.cancelled) { say("已取消添加。"); return; }
    $("#name").value = ""; state.addDraft = null; close("#addPanel"); resetFilters();
    const targetView = state.addToFavorites ? "favorites" : "all";
    cachedViewState.views[targetView] = { scrollY: 0, query: "" };
    await showView(targetView); say(message);
  }
  bind("#pickAppIcon", async () => {
    const value = await host.call("apps.pickIcon", {});
    if (value.cancelled) { say("已取消操作。"); return; }
    const cropped = await H.ui.cropIcon(value.dataUrl);
    if (cropped) setCustomAddIcon(cropped);
  });
  bind("#confirmAdd", async () => {
    const draft = state.addDraft;
    if (!draft) throw new Error("添加信息已失效，请重新选择来源。");
    const name = $("#name").value.trim();
    if (!name) { $("#name").focus(); throw new Error("应用名称不能为空。"); }
    const common = { name, version:$("#addVersion").value.trim(), favorite:state.addToFavorites, iconDataUrl:draft.customIconDataUrl || "" };
    const value = draft.kind === "directory"
      ? await host.call("apps.confirmDirectory", Object.assign(common, { token:draft.token }))
      : await host.call("apps.installOnline", Object.assign(common, { url:validUrl("#url") }));
    const message = draft.kind === "directory" ? "本地 happ 已添加。" : value.installStrategy === "local" ? "线上 happ 已安装并采用本地运行。" : "线上 happ 已添加并采用实时运行。";
    await installed(value, message);
  });
  H.features.install = { validUrl };
})();
