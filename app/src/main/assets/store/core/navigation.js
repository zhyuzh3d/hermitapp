(() => {
  "use strict";
  const H = window.HermitShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction, selected } = H.ui;
  const { VIEW_STATE_KEY, VIEWS } = H;
  const { finishConfirm, focusable } = H.ui;
  function readViewState() {
    try {
      const value = JSON.parse(localStorage.getItem(VIEW_STATE_KEY) || "{}");
      return value && typeof value === "object" && value.views && typeof value.views === "object" ? value : { views: {} };
    } catch (_) { return { views: {} }; }
  }
  const cachedViewState = readViewState();
  function writeViewState() {
    try { localStorage.setItem(VIEW_STATE_KEY, JSON.stringify(cachedViewState)); } catch (_) {}
  }
  function captureViewState(view = state.view) {
    if (!VIEWS.includes(view)) return;
    const value = { scrollY: Math.max(0, Math.round(window.scrollY || 0)) };
    if (view === "development") {
      const details = $("#developmentView details");
      value.eventsOpen = !!(details && details.open);
      // The development password is Native-owned sensitive state and is never cached here.
    } else if (view === "icons") {
      value.query = $("#searchIcons").value;
      value.style = state.iconStyle;
      value.limit = state.iconLimit;
    } else if (view === "settings") {
      value.settingsTab = state.settingsTab;
    }
    cachedViewState.views[view] = value;
    cachedViewState.currentView = view;
    writeViewState();
  }
  function initialView() {
    return VIEWS.includes(cachedViewState.currentView) ? cachedViewState.currentView : "favorites";
  }
  function restoreCachedViewState(value) {
    if (!value || typeof value !== "object") return;
    const restored = {};
    for (const view of VIEWS) {
      const source = value.views && value.views[view];
      if (!source || typeof source !== "object") continue;
      const item = { scrollY:Number.isFinite(source.scrollY) ? Math.max(0, Math.round(source.scrollY)) : 0 };
      if (view === "development") item.eventsOpen = !!source.eventsOpen;
      if (view === "icons") {
        item.query = typeof source.query === "string" ? source.query.slice(0, 300) : "";
        item.style = ["all", "solid", "regular", "brands"].includes(source.style) ? source.style : "all";
        item.limit = Number.isInteger(source.limit) ? Math.max(60, Math.min(source.limit, 600)) : 60;
      }
      if (view === "settings") item.settingsTab = ["interface", "tts", "speech", "system"].includes(source.settingsTab) ? source.settingsTab : "interface";
      restored[view] = item;
    }
    cachedViewState.views = restored;
    cachedViewState.currentView = VIEWS.includes(value.currentView) ? value.currentView : initialView();
    writeViewState();
  }
  function restoreViewState(view) {
    const value = cachedViewState.views[view] || {};
    if (view === "development") {
      const details = $("#developmentView details");
      if (details) details.open = !!value.eventsOpen;
    } else if (view === "icons") {
      $("#searchIcons").value = typeof value.query === "string" ? value.query.slice(0, 300) : "";
      state.iconStyle = ["all", "solid", "regular", "brands"].includes(value.style) ? value.style : "all";
      state.iconLimit = Number.isInteger(value.limit) && value.limit >= 60 ? Math.min(value.limit, 600) : 60;
      selected("#iconFilters", "style", state.iconStyle);
    } else if (view === "settings") {
      state.settingsTab = ["interface", "tts", "speech", "system"].includes(value.settingsTab) ? value.settingsTab : "interface";
      if (H.features.settings) H.features.settings.showSettingsTab(state.settingsTab);
    }
  }
  function restoreViewScroll(view, epoch) {
    const value = cachedViewState.views[view] || {};
    const scrollY = Number.isFinite(value.scrollY) ? Math.max(0, value.scrollY) : 0;
    requestAnimationFrame(() => {
      if (state.view === view && state.viewEpoch === epoch) window.scrollTo(0, scrollY);
    });
  }
  window.hermitStoreBack = () => {
    if (H.ui.confirming) { finishConfirm(false); return true; }
    const modal = state.modals[state.modals.length - 1];
    if (modal) { H.ui.requestClose("#" + modal.element.id); return true; }
    if (state.view === "icons") { switchView("settings"); return true; }
    if (state.view !== "favorites") { switchView("favorites"); return true; }
    return false;
  };
  $$("[data-close]").forEach(button => { button.onclick = () => H.ui.requestClose(button.dataset.close); });
  $$(".modal").forEach(modal => { modal.onclick = event => { if (event.target === modal) window.hermitStoreBack(); }; });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && window.hermitStoreBack()) event.preventDefault();
    if (event.key !== "Tab" || !state.modals.length) return;
    const topEntry = state.modals[state.modals.length - 1];
    const items = focusable(topEntry.element), first = items[0], last = items[items.length - 1];
    if (!topEntry.element.contains(document.activeElement)) { event.preventDefault(); if (first) first.focus(); return; }
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); if (last) last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); if (first) first.focus(); }
  });

  async function refreshView(view) {
    if (!host.ready() && view !== "icons") return;
    if (["favorites", "all"].includes(view)) return H.features.library.refresh();
    if (view === "development") return H.features.development.refreshAgent();
    if (view === "settings") return H.features.settings.load();
    if (view === "icons") return H.features.icons.loadCatalog();
  }
  async function showView(view, runSideEffect = true) {
    if (!VIEWS.includes(view)) return;
    if (state.viewMounted) captureViewState();
    state.view = view;
    state.viewMounted = true;
    cachedViewState.currentView = view;
    writeViewState();
    const epoch = ++state.viewEpoch;
    const topbarCopy = {
      all: ["全部应用", "你的页面应用，都在这里。"],
      development: ["开发服务", "让任意电脑的智能体快速更新、刷新和发布 happ。"],
      settings: ["设置", "调整外观、界面版本与常用工具。"],
      support: ["支持 Hermit", "向项目提交反馈，并查看开源仓库。"],
      icons: ["内置图标", "搜索并复制页面可直接使用的图标。"]
    }[view];
    $("#homeBrand").classList.toggle("hidden", !!topbarCopy);
    $("#topbarCopy").classList.toggle("hidden", !topbarCopy);
    $(".topbar-versions").classList.toggle("hidden", view !== "favorites");
    $("#homePrompt").classList.toggle("hidden", view !== "favorites");
    $("#collectionPrompt").classList.toggle("hidden", !["favorites", "all"].includes(view));
    if (topbarCopy) {
      $("#topbarTitle").textContent = topbarCopy[0];
      $("#topbarDescription").textContent = topbarCopy[1];
    }
    const visibleView = ["favorites", "all"].includes(view) ? "libraryView" : view + "View";
    $$("main > .view").forEach(el => el.classList.toggle("hidden", el.id !== visibleView));
    $$(".bottom-nav [data-view]").forEach(button => {
      const active = button.dataset.view === (view === "icons" ? "settings" : view);
      button.classList.toggle("active", active);
      if (active) button.setAttribute("aria-current", "page"); else button.removeAttribute("aria-current");
    });
    restoreViewState(view);
    window.scrollTo(0, 0);
    if (["favorites", "all"].includes(view)) H.features.library.filterApps();
    if (runSideEffect) await refreshView(view);
    restoreViewScroll(view, epoch);
  }
  const switchView = view => showView(view).catch(error => {
    if (view === "icons") $("#iconCount").textContent = "图标目录读取失败，请切换页面重试。";
    say(error.message || "页面刷新失败，请重试。", true);
  });
  $$("[data-view]").forEach(button => { button.onclick = () => switchView(button.dataset.view); });
  $(".brand").onclick = event => { event.preventDefault(); switchView("favorites"); };
  addEventListener("pagehide", () => { if (state.viewMounted) captureViewState(); });
  H.navigation = { showView, switchView, captureViewState, restoreViewScroll, restoreCachedViewState, initialView, cachedViewState };
})();
