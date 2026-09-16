(() => {
  "use strict";
  const H = window.HermitShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction, selected } = H.ui;
  const { resetFilters } = H.features.library;
  const { showView, cachedViewState } = H.navigation;
  function resetAddIcon() {
    state.addDraft.customIcon = null;
    state.addDraft.defaultIcon = null;
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
    state.addDraft.customIcon = dataUrl;
    showAddIcon(dataUrl);
  }
  function renderFavoriteChoice() {
    $$('[data-add-favorite]').forEach(button => {
      button.classList.toggle("on", state.addToFavorites);
      button.setAttribute("aria-checked", String(state.addToFavorites));
    });
  }
  function openAdd(kind, value = {}) {
    state.addDraft = { kind, token:value.token || null, customIcon:null, defaultIcon:value.iconUrl || null };
    renderFavoriteChoice();
    $("#urlField").classList.remove("hidden");
    $("#versionField").classList.add("hidden");
    $("#url").value = value.url || "";
    $("#name").value = value.name || (value.url ? new URL(value.url).hostname : "");
    $("#addVersion").value = value.version || "1.0.0";
    $("#addSourceLabel").textContent = value.scanned ? "二维码链接" : "在线网址";
    $("#manifestStatus").textContent = "普通网页会实时运行；ZIP 会下载校验后本地安装；Git 仓库优先读取 hermit-install.json，缺失时再选择仓库中实际存在的发布目录。";
    resetAddIcon();
    if (value.iconPreview) showAddIcon(value.iconPreview);
    open("#addPanel");
    setTimeout(() => $("#url").focus({ preventScroll:true }), 80);
  }
  bind("#addZip", async () => {
    await installed(await host.call("apps.importZip", { favorite: state.addToFavorites }), "压缩包已校验并安装。" );
  });
  $$('[data-add-favorite]').forEach(button => {
    button.onclick = () => {
      state.addToFavorites = !state.addToFavorites;
      renderFavoriteChoice();
    };
  });
  renderFavoriteChoice();
  $("#addUrl").onclick = () => openAdd("online");
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
    state.libraryFilter = state.addToFavorites ? "favorites" : "all";
    cachedViewState.views.favorites = { scrollY: 0, libraryFilter:state.libraryFilter };
    await showView("favorites"); say(message);
  }
  bind("#pickAppIcon", async () => {
    const value = await host.call("apps.pickIcon", {});
    if (value.cancelled) { say("已取消操作。"); return; }
    const cropped = await H.ui.cropIcon(value.preview);
    if (cropped) setCustomAddIcon(cropped);
  });
  bind("#confirmAdd", async () => {
    const draft = state.addDraft;
    if (!draft) throw new Error("添加信息已失效，请重新选择来源。");
    const name = $("#name").value.trim();
    if (!name) { $("#name").focus(); throw new Error("应用名称不能为空。"); }
    const common = { name, version:$("#addVersion").value.trim(), favorite:state.addToFavorites, iconPreviewDataUrl:draft.customIcon || "" };
    let onlineUrl = null;
    if (draft.kind === "online") {
      onlineUrl = validUrl("#url");
      if (new URL(onlineUrl).protocol === "http:") {
        const accepted = await confirmAction("允许未加密的 HTTP 页面？", onlineUrl + "\n\n网页内容和凭据可能被同一网络中的其他人读取或篡改。仅在你信任当前网络和服务时继续。", "仍然添加");
        if (!accepted) return;
        common.insecureConfirmed = true;
      }
    }
    const value = await host.call("apps.installOnline", Object.assign(common, { url:onlineUrl }));
    const message = value.installStrategy === "local" ? "来源内容已校验并安装为本地 happ，可创建开发副本。"
      : "普通网页已添加为实时 happ；没有本地代码，不能进入开发模式。";
    await installed(value, message);
  });
  window.hermitOpenSharedUrl = url => openAdd("online", { url, shared:true });
  H.features.install = { validUrl };
})();
