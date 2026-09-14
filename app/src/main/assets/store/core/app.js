(() => {
  "use strict";
  const H = window.HermitShell, { $, state, host } = H;
  H.beforeClose = async selector => {
    if (selector === "#hostPromptPanel") { await H.ui.cancelHostPrompt(); return false; }
    if (selector !== "#managePanel" || !H.features.manage.isDirty()) return true;
    return H.ui.confirmAction("放弃尚未保存的修改？", "已保存的应用数据不受影响。", "放弃修改", true);
  };
  H.onOperationSettled = modal => { if (modal && modal.id === "managePanel") H.features.manage.renderManageDraft(); };
  let starting = false, connected = false;
  function libraryError(error) {
    $("#loading").classList.add("hidden");
    $("#empty").classList.add("hidden");
    $("#libraryUnavailable").classList.remove("hidden");
    $("#libraryErrorText").textContent = error.message || "应用库读取失败，请重试。";
  }
  async function start() {
    if (starting || !host.ready()) return;
    starting = true; connected = true;
    $("#browserNotice").classList.add("hidden");
    $("#hostActions").classList.remove("hidden");
    $("#loading").classList.remove("hidden");
    if (!state.viewMounted) await H.navigation.showView("favorites", false);
    try { await H.features.library.refresh(); } catch (error) { libraryError(error); }
    starting = false;
    H.navigation.restoreViewScroll(state.view, state.viewEpoch);
    const reads = [H.features.settings.loadAbout(), H.features.development.refreshAgent()];
    const results = await Promise.allSettled(reads);
    if (results[0].status === "rejected") $("#topApkVersion").textContent = "暂不可用";
    if (results[1].status === "rejected") $("#agentStatus").textContent = "开发状态读取失败，进入开发页可重试。";
  }
  $("#retryLibrary").onclick = start;
  addEventListener("hermitready", start);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && connected && host.ready()) H.navigation.showView(state.view).catch(error => H.ui.say(error.message, true));
  });
  if (host.ready()) start();
  else {
    H.navigation.showView("favorites", false);
    $("#loading").classList.add("hidden"); $("#empty").classList.add("hidden");
    $("#browserNotice").classList.remove("hidden");
    $("#hostActions").classList.add("hidden");
    $("#topApkVersion").textContent = "未连接";
    $("#agentStatus").textContent = "请在 HermitApp 中查看开发状态。";
    $("#aboutVersion").textContent = "未连接 HermitApp";
    $("#topWebVersion").textContent = H.version;
  }
})();
