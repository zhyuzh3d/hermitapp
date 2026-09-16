(() => {
  "use strict";
  const H = window.HermitShell;
  const { $, state } = H;
  let pendingState = null, restoring = false;

  function clone(value) {
    try { return JSON.parse(JSON.stringify(value)); } catch (_) { return null; }
  }
  function text(value, limit) {
    return typeof value === "string" ? value.slice(0, limit) : "";
  }
  function manageOverlay() {
    const ids = state.modals.map(item => item.element.id);
    if (!ids.includes("managePanel") || !state.selected) return null;
    const draft = state.manageDraft || {};
    return {
      kind:"manage",
      appId:state.selected.appId,
      child:ids.includes("uninstallPanel") ? "uninstall" : null,
      scrollY:Math.max(0, Math.round($("#managePanel .manage-sheet-scroll")?.scrollTop || 0)),
      fields:{
        name:text($("#editName")?.value, 80),
        liveUrl:text($("#editUrl")?.value, 4096),
        updateUrl:text($("#editUpdateUrl")?.value, 4096),
        versionName:text($("#devVersionName")?.value, 80),
        versionCode:Math.max(1, Math.trunc(Number($("#devVersionCode")?.value) || 1))
      },
      draft:{
        runtimeMode:["local", "live"].includes(draft.runtimeMode) ? draft.runtimeMode : "local",
        notificationEnabled:!!draft.notificationEnabled,
        allowCrossOriginNetwork:!!draft.allowCrossOriginNetwork,
        // Image bytes are intentionally not serialized into the UI snapshot.
        // The selected icon is transient until the user presses Save.
      }
    };
  }
  function capture() {
    H.navigation.captureViewState();
    const top = state.modals[state.modals.length - 1]?.element.id;
    let overlay = manageOverlay();
    if (!overlay && top === "diagnosticsPanel") overlay = { kind:"diagnostics" };
    if (!overlay && top === "licensesPanel") overlay = { kind:"licenses" };
    return {
      schema:1,
      view:state.view,
      viewState:clone(H.navigation.cachedViewState),
      scroll:{ x:Math.max(0, Math.round(scrollX || 0)), y:Math.max(0, Math.round(scrollY || 0)) },
      overlay
    };
  }
  async function restoreManage(overlay) {
    const app = state.apps.find(item => item.appId === overlay.appId);
    if (!app) { H.ui.say("原应用已不存在，已返回应用列表。", true); return; }
    await H.features.manage.openManage(app);
    const fields = overlay.fields || {}, draft = overlay.draft || {};
    $("#editName").value = text(fields.name, 80) || app.name;
    $("#editUrl").value = text(fields.liveUrl, 4096);
    $("#editUpdateUrl").value = text(fields.updateUrl, 4096);
    $("#devVersionName").value = text(fields.versionName, 80) || $("#devVersionName").value;
    $("#devVersionCode").value = Math.max(1, Math.trunc(Number(fields.versionCode) || Number($("#devVersionCode").value) || 1));
    if (state.manageDraft) {
      state.manageDraft.runtimeMode = ["local", "live"].includes(draft.runtimeMode) ? draft.runtimeMode : state.manageDraft.runtimeMode;
      state.manageDraft.notificationEnabled = !!draft.notificationEnabled;
      state.manageDraft.allowCrossOriginNetwork = !!draft.allowCrossOriginNetwork;
      H.features.manage.renderManageDraft();
    }
    const scroll = $("#managePanel .manage-sheet-scroll");
    if (scroll) requestAnimationFrame(() => { scroll.scrollTop = Math.max(0, Number(overlay.scrollY) || 0); });
    if (overlay.child === "uninstall") H.ui.open("#uninstallPanel");
  }
  async function apply(snapshot) {
    if (!snapshot || typeof snapshot !== "object" || snapshot.schema !== 1) return false;
    H.navigation.restoreCachedViewState(snapshot.viewState);
    const wanted = H.VIEWS.includes(snapshot.view) ? snapshot.view : "favorites";
    try { await H.navigation.showView(wanted); }
    catch (_) { await H.navigation.showView("favorites"); H.ui.say("原页面已不可用，已返回收藏页。", true); }
    const overlay = snapshot.overlay;
    if (overlay?.kind === "manage") await restoreManage(overlay);
    else if (overlay?.kind === "diagnostics") await H.features.settings.openDiagnostics();
    else if (overlay?.kind === "licenses") await H.features.settings.openLicenses();
    else {
      const y = Math.max(0, Number(snapshot.scroll?.y) || 0);
      requestAnimationFrame(() => window.scrollTo(0, y));
    }
    return true;
  }
  async function drain() {
    if (!H.started || restoring || !pendingState) return;
    const snapshot = pendingState; pendingState = null; restoring = true;
    try { await apply(snapshot); }
    catch (error) { H.ui.say(error.message || "界面状态恢复失败，已保留当前页面。", true); }
    finally { restoring = false; if (pendingState) drain(); }
  }
  function restore(snapshot) {
    pendingState = clone(snapshot);
    drain();
    return true;
  }
  addEventListener("hermitshellstarted", drain);
  window.hermitDevState = Object.freeze({ capture, restore });
})();
