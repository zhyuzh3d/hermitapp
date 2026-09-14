(() => {
  "use strict";
  const H = window.HermitShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction, selected } = H.ui;
  const { appSource, appRuntime, hasLocal, hasLive, sourceLabel, runtimeLabel, refresh, resetFilters, pin, renderPinButton } = H.features.library;
  const { validUrl } = H.features.install;
  const { showView, cachedViewState } = H.navigation;
  const capabilityLabels = { "camera.capture": "拍照", "microphone.record": "麦克风录音", speech: "语音识别", "location.approximate": "大致位置", "location.precise": "精确位置", "clipboard.read": "读取剪贴板", network: "网络请求", notifications: "发送通知" };
  function canUpdateFromSource(app) { return !!app.updateUrl; }
  function nextVersionName(value) {
    const parts = String(value || "0.0.0").match(/^(\d+)\.(\d+)\.(\d+)$/);
    return parts ? parts[1] + "." + parts[2] + "." + (Number(parts[3]) + 1) : "1.0.0";
  }
  function renderDevWorkspace(app) {
    const dev = app.devWorkspace || {}, missing = dev.state === "missing" || !dev.revision;
    const labels = { clean:"开发副本与正式版本一致。", dirty:"开发副本有未发布修改。", "base-outdated":"正式版本已更新，开发副本仍保留未发布修改。", missing:"尚未创建开发工作副本。" };
    $("#devWorkspaceStatus").textContent = labels[missing ? "missing" : dev.state] || "开发副本状态暂不可用。";
    $("#runStableApp").classList.toggle("selected", app.launchChannel !== "dev");
    $("#runDevApp").classList.toggle("selected", app.launchChannel === "dev");
    $("#runStableApp").setAttribute("aria-pressed", String(app.launchChannel !== "dev"));
    $("#runDevApp").setAttribute("aria-pressed", String(app.launchChannel === "dev"));
    $("#runStableApp").disabled = app.launchChannel !== "dev";
    $("#runDevApp").disabled = app.launchChannel === "dev";
    $("#runDevApp").textContent = missing ? "创建并运行开发副本" : "运行开发副本";
    $("#resetDevApp").disabled = missing;
    $("#exportDevApp").disabled = missing;
    const version = app.activeVersion || {};
    $("#devVersionCode").value = Math.max(1, Number(version.code || 0) + 1);
    $("#devVersionName").value = nextVersionName(version.name);
  }
  function setDraftSwitch(selector, enabled) {
    const control = $(selector); control.classList.toggle("on", enabled); control.setAttribute("aria-checked", String(enabled));
  }
  function renderIconPreview(selector, dataUrl) {
    const preview = $(selector);
    preview.replaceChildren(); preview.style.backgroundImage = "";
    if (dataUrl) {
      preview.style.backgroundImage = "url(" + JSON.stringify(dataUrl).slice(1,-1) + ")";
      preview.classList.add("custom");
      return;
    }
    preview.classList.remove("custom");
    preview.append(Object.assign(document.createElement("i"), { className:"fa-solid fa-image", ariaHidden:"true" }));
  }
  function renderManageDraft() {
    const app = state.selected, draft = state.manageDraft;
    if (!app || !draft) return;
    const hasDraftLive = !!$("#editUrl").value.trim();
    if (draft.runtimeMode === "live" && !hasDraftLive && hasLocal(app)) draft.runtimeMode = "local";
    if (draft.runtimeMode === "local" && !hasLocal(app) && hasDraftLive) draft.runtimeMode = "live";
    $("#manageTitle").textContent = "设置：" + ($("#editName").value.trim() || app.name);
    $$("#runtimeChoices button").forEach(button => {
      const mode = button.dataset.runtimeMode;
      const available = mode === "local" ? hasLocal(app) : hasDraftLive;
      button.disabled = !available;
      button.classList.toggle("selected", draft.runtimeMode === mode);
      button.setAttribute("aria-pressed", String(draft.runtimeMode === mode));
    });
    $("#saveApp").disabled = !isDirty();
    const managePinState = renderPinButton($("#managePin"), app);
    $("#managePin").disabled = isDirty() || managePinState === "unsupported";
    $("#updateApp").disabled = !canUpdateFromSource(app) || isDirty();
    $("#reinstallApp").disabled = !app.downloadUrl || isDirty();
    $("#removeCustomAppIcon").classList.toggle("hidden", !draft.customIconDataUrl);
    renderIconPreview("#editIconPreview", draft.customIconDataUrl || app.defaultIconDataUrl || "");
    setDraftSwitch("#notificationSwitch", draft.notificationEnabled);
    setDraftSwitch("#crossOriginSwitch", draft.allowCrossOriginNetwork);
    $("#runtimeHint").textContent = hasLocal(app) && hasDraftLive
      ? "本地代码和线上实时页面都可用。选择后请点底部“保存修改”才会生效。"
      : hasLocal(app) ? "当前只有本地代码；填写 liveUrl 后可选择线上实时运行。"
      : "当前没有本地代码，需要填写 liveUrl 才能线上实时运行。";
  }
  $("#editName").oninput = renderManageDraft;
  $("#editUrl").oninput = renderManageDraft;
  $("#editUpdateUrl").oninput = renderManageDraft;
  async function openManage(app) {
    const epoch = ++state.managedEpoch;
    if (!app) throw new Error("应用已不存在，请刷新应用库。");
    state.selected = app;
    const customIconDataUrl = Object.prototype.hasOwnProperty.call(app, "customIconDataUrl")
      ? (app.customIconDataUrl || "")
      : (app.iconDataUrl || "");
    state.manageDraft = { runtimeMode: appRuntime(app), notificationEnabled: !!app.notificationEnabled, allowCrossOriginNetwork: !!app.allowCrossOriginNetwork, customIconDataUrl };
    $("#editName").value = app.name; $("#editUrl").value = app.liveUrl || ""; $("#editUpdateUrl").value = app.updateUrl || "";
    renderIconPreview("#editIconPreview", customIconDataUrl || app.defaultIconDataUrl || "");
    $("#downloadUrl").textContent = app.downloadUrl || "无";
    $("#updateApp").disabled = !canUpdateFromSource(app);
    $("#reinstallApp").disabled = !app.downloadUrl;
    $("#developerDetails").classList.toggle("hidden", !hasLocal(app));
    $("#releaseDetails").classList.toggle("hidden", !hasLocal(app));
    renderManageDraft();
    if (hasLocal(app)) renderDevWorkspace(app);
    $("#grants").textContent = "正在读取授权…"; $("#releases").textContent = "正在读取版本…";
    $("#manageMeta").textContent = sourceLabel(app) + " · " + runtimeLabel(app);
    open("#managePanel");
    const [grantResult, releaseResult] = await Promise.allSettled([
      host.call("permissions.list", { appId: app.appId }),
      hasLocal(app) ? host.call("apps.releases", { appId: app.appId }) : Promise.resolve(null)
    ]);
    if (epoch !== state.managedEpoch || $("#managePanel").classList.contains("hidden")) return;
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
          await host.call("permissions.revoke", { appId: app.appId, capability: grant.capability, scope: grant.scope });
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
          await host.call("apps.rollback", { appId: app.appId, releaseId: release.releaseId });
          close("#managePanel"); say("代码版本已切换。"); await refresh();
        });
        row.append(activate);
      }
      releaseRoot.append(row);
    }
  }
  bind("#saveApp", async () => {
    const app = state.selected;
    const draft = state.manageDraft;
    if (!app || !draft) throw new Error("应用设置已失效，请重新打开。");
    if (!$("#editName").value.trim()) { $("#editName").focus(); throw new Error("应用名称不能为空。"); }
    const liveUrl = $("#editUrl").value.trim();
    const updateUrl = $("#editUpdateUrl").value.trim();
    if (liveUrl) validUrl("#editUrl");
    if (updateUrl) validUrl("#editUpdateUrl");
    if (draft.runtimeMode === "live" && !liveUrl) throw new Error("线上实时运行需要填写 liveUrl。");
    if (draft.allowCrossOriginNetwork && !app.allowCrossOriginNetwork && !await confirmAction("允许跨 Origin 网络？", "保存后，此 happ 的脚本、接口和页面可以主动连接 liveUrl Origin 之外的地址，运行时会持续显示提示。", "保存并允许")) return;
    if (draft.runtimeMode !== appRuntime(app)) {
      const message = draft.runtimeMode === "live"
        ? "保存后，Hermit 将直接加载 liveUrl，页面代码可随服务器变化。"
        : "保存后，Hermit 将优先运行手机内已验证的代码版本。";
      if (!await confirmAction("保存运行方式？", message, "保存并切换")) return;
    }
    try {
      let insecureConfirmed = false;
      if (liveUrl && liveUrl !== (app.liveUrl || "") && new URL(liveUrl).protocol === "http:") {
        insecureConfirmed = await confirmAction("允许未加密的 HTTP 页面？", liveUrl + "\n\n网页内容和凭据可能被同一网络中的其他人读取或篡改。仅在你信任当前网络和服务时继续。", "仍然保存");
        if (!insecureConfirmed) return;
      }
      await H.features.appSettings.save(app, Object.assign({}, draft, { name: $("#editName").value.trim(), liveUrl, updateUrl, insecureConfirmed }));
    } catch (error) {
      try { await refresh(); state.selected = state.apps.find(item => item.appId === app.appId) || app; } catch (_) {}
      renderManageDraft();
      throw error;
    }
    close("#managePanel"); say("应用设置已保存。"); await refresh();
  });
  $$("#runtimeChoices [data-runtime-mode]").forEach(button => {
    button.onclick = event => {
      if (event.currentTarget.disabled || !state.manageDraft) return;
      state.manageDraft.runtimeMode = event.currentTarget.dataset.runtimeMode;
      renderManageDraft();
    };
  });
  async function reopenAfterDevChange(appId, message) {
    await refresh();
    const updated = state.apps.find(item => item.appId === appId);
    if (!updated) throw new Error("应用已不存在。");
    close("#managePanel");
    await openManage(updated);
    say(message);
  }
  bind("#runDevApp", async () => {
    const app = state.selected;
    await host.call("apps.enterDev", { appId:app.appId });
    await reopenAfterDevChange(app.appId, "以后打开此 happ 将运行开发副本。");
  });
  bind("#runStableApp", async () => {
    const app = state.selected;
    await host.call("apps.leaveDev", { appId:app.appId });
    await reopenAfterDevChange(app.appId, "已切换为运行正式版本，开发副本仍保留。");
  });
  bind("#resetDevApp", async () => {
    const app = state.selected;
    if (!await confirmAction("从正式版本重建开发副本？", "未发布的开发文件会被正式版本替换，此操作无法撤销。", "重建开发副本", true)) return;
    await host.call("apps.resetDev", { appId:app.appId });
    await reopenAfterDevChange(app.appId, "开发副本已从当前正式版本重建。");
  });
  bind("#exportDevApp", async () => {
    const app = state.selected;
    const versionCode = Number($("#devVersionCode").value);
    const versionName = $("#devVersionName").value.trim();
    if (!Number.isSafeInteger(versionCode) || versionCode < 1 || !versionName) throw new Error("请填写有效的新版本号和版本名称。");
    const value = await host.call("apps.exportDev", { appId:app.appId, versionCode, versionName });
    say(value.cancelled ? "已取消导出。" : "发布 ZIP 已导出；正式版本尚未改变。");
  });
  bind("#managePin", () => pin(state.selected));
  bind("#editAppIcon", async () => {
    if (!state.manageDraft) throw new Error("应用设置已失效，请重新打开。");
    const value = await host.call("apps.pickIcon", {});
    if (value.cancelled) { say("已取消操作。"); return; }
    const cropped = await H.ui.cropIcon(value.dataUrl);
    if (!cropped) return;
    state.manageDraft.customIconDataUrl = cropped;
    renderIconPreview("#editIconPreview", cropped);
    renderManageDraft();
  });
  bind("#removeCustomAppIcon", () => {
    const app = state.selected, draft = state.manageDraft;
    if (!app || !draft) throw new Error("应用设置已失效，请重新打开。");
    draft.customIconDataUrl = "";
    renderIconPreview("#editIconPreview", app.defaultIconDataUrl || "");
    renderManageDraft();
  });
  $("#notificationSwitch").onclick = () => { if (state.manageDraft) { state.manageDraft.notificationEnabled = !state.manageDraft.notificationEnabled; renderManageDraft(); } };
  $("#crossOriginSwitch").onclick = () => { if (state.manageDraft) { state.manageDraft.allowCrossOriginNetwork = !state.manageDraft.allowCrossOriginNetwork; renderManageDraft(); } };
  bind("#updateApp", async () => {
    const app = state.selected;
    await host.call("apps.updateFromSource", { appId: app.appId });
    await refresh(); await openManage(state.apps.find(x => x.appId === app.appId)); say("已从更新地址下载并安装代码，数据已保留。");
  });
  bind("#reinstallApp", async () => {
    const app = state.selected;
    if (!await confirmAction("重新下载安装？", "将替换当前代码，Hermit 数据保留。", "重新安装")) return;
    await host.call("apps.reinstall", { appId:app.appId });
    await refresh(); await openManage(state.apps.find(x => x.appId === app.appId)); say("已从原地址重新安装，数据已保留。");
  });
  bind("#restoreBackupSettings", async () => {
    if (!await confirmAction("恢复应用备份", "将创建独立的新应用，不继承登录状态和页面授权。", "选择备份")) return;
    const value = await host.call("backup.restore", {});
    if (!value.cancelled) {
      resetFilters(); cachedViewState.views.all = { scrollY: 0, query: "" };
      await showView("all"); say("已恢复“" + value.name + "”。");
    }
  });
  bind("#restoreAppData", async () => {
    const app = state.selected;
    if (!await confirmAction("替换“" + app.name + "”的数据？", "原有 Hermit 记录和附件会被备份内容覆盖，页面授权重置。此操作无法撤销，请先导出当前备份。", "选择备份并替换", true)) return;
    const value = await host.call("backup.restoreData", { appId: app.appId });
    if (!value.cancelled) { close("#managePanel"); await refresh(); say("数据已恢复，页面授权已重置。"); }
  });
  $("#uninstallApp").onclick = () => open("#uninstallPanel");
  bind("#removeApp", async () => {
    const app = state.selected;
    if (!await confirmAction("彻底清除“" + app.name + "”？", "将删除这个应用及其全部 Hermit 本机数据，无法恢复。", "彻底清除", true)) return;
    await host.call("apps.remove", { appId: app.appId }); close("#uninstallPanel"); close("#managePanel"); await refresh(); say("应用已卸载并彻底清除数据。");
  });
  bind("#archiveApp", async () => {
    const app = state.selected;
    await host.call("apps.archive", { appId:app.appId }); close("#uninstallPanel"); close("#managePanel"); await refresh(); say("应用已卸载，数据仍保留在 HermitApp 中。");
  });
  bind("#backupApp", async () => {
    const app = state.selected;
    if (!await confirmAction("导出“" + app.name + "”的备份", "包含代码、Hermit 记录和附件，不包含网站登录状态。备份不加密，请保存到可信位置。", "选择保存位置")) return;
    const value = await host.call("backup.export", { appId: app.appId });
    say(value.cancelled ? "已取消导出。" : "备份已导出。");
  });
  function isDirty() {
    const app = state.selected, draft = state.manageDraft;
    const customIconDataUrl = app && Object.prototype.hasOwnProperty.call(app, "customIconDataUrl") ? (app.customIconDataUrl || "") : ((app && app.iconDataUrl) || "");
    return !!(app && draft && ($("#editName").value.trim() !== app.name || $("#editUrl").value.trim() !== (app.liveUrl || "") || $("#editUpdateUrl").value.trim() !== (app.updateUrl || "") || draft.customIconDataUrl !== customIconDataUrl || draft.runtimeMode !== appRuntime(app) || draft.notificationEnabled !== !!app.notificationEnabled || draft.allowCrossOriginNetwork !== !!app.allowCrossOriginNetwork));
  }
  H.features.manage = { openManage, isDirty, renderManageDraft };
})();
