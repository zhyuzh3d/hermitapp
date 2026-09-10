(() => {
  "use strict";
  const $ = selector => document.querySelector(selector);
  const state = { apps: [], selected: null, deploy: null };
  const notice = $("#notice");
  const say = (message, error = false) => {
    notice.textContent = message;
    notice.classList.toggle("error", error);
    notice.classList.remove("hidden");
    clearTimeout(say.timer);
    say.timer = setTimeout(() => notice.classList.add("hidden"), 6000);
  };
  const busy = async (button, work) => {
    const label = button.textContent;
    button.disabled = true;
    button.textContent = "处理中…";
    try { return await work(); }
    catch (error) { say(error.message || "操作失败", true); throw error; }
    finally { button.disabled = false; button.textContent = label; }
  };
  const close = selector => $(selector).classList.add("hidden");

  async function refresh() {
    const result = await hermit.host.apps.list({});
    state.apps = result.apps || [];
    $("#count").textContent = state.apps.length ? state.apps.length + " 个" : "";
    $("#empty").classList.toggle("hidden", state.apps.length !== 0);
    const root = $("#apps");
    root.replaceChildren();
    for (const app of state.apps) {
      const fragment = $("#appTemplate").content.cloneNode(true);
      fragment.querySelector(".app-card").dataset.search = (app.name + " " + app.startUrl + " " + app.sourceAdapter).toLocaleLowerCase();
      fragment.querySelector(".app-name").textContent = app.name;
      fragment.querySelector(".app-source").textContent = app.mode === "local"
        ? "本地副本 · " + (app.activeReleaseId || "").slice(0, 8) : app.startUrl;
      fragment.querySelector(".launch").onclick = () => hermit.host.apps.launch({ appId: app.appId });
      fragment.querySelector(".pin").onclick = event => busy(event.currentTarget, async () => {
        const value = await hermit.host.apps.pin({ appId: app.appId });
        say(value.requested ? "已请求添加桌面图标" : "当前桌面不支持固定图标");
      });
      fragment.querySelector(".manage").onclick = () => openManage(app).catch(error => say(error.message, true));
      fragment.querySelector(".update").disabled = !["https-package", "github"].includes(app.sourceAdapter);
      fragment.querySelector(".update").onclick = event => busy(event.currentTarget, async () => {
        const value = await hermit.host.apps.updateFromSource({ appId: app.appId });
        say("已更新到版本 " + value.releaseId.slice(0, 8)); await refresh();
      });
      fragment.querySelector(".develop").disabled = app.mode !== "local";
      fragment.querySelector(".develop").onclick = event => busy(event.currentTarget, () => openDeploy(app));
      fragment.querySelector(".develop-lan").disabled = app.mode !== "local";
      fragment.querySelector(".develop-lan").onclick = event => busy(event.currentTarget, () => openDeploy(app, "lan"));
      fragment.querySelector(".backup").onclick = event => busy(event.currentTarget, async () => {
        if (!confirm("备份包含该应用在 Hermit 中的记录和附件，默认不加密。继续导出？")) return;
        const value = await hermit.host.backup.export({ appId: app.appId });
        if (!value.cancelled) say("备份已导出");
      });
      fragment.querySelector(".remove").onclick = async event => {
        if (!confirm("删除“" + app.name + "”及其本机数据？此操作无法撤销。")) return;
        await busy(event.currentTarget, () => hermit.host.apps.remove({ appId: app.appId }));
        await refresh();
      };
      root.append(fragment);
    }
    $("#searchApps").dispatchEvent(new Event("input"));
  }

  async function openManage(app) {
    state.selected = app;
    $("#editName").value = app.name;
    $("#editUrl").value = app.startUrl;
    $("#editUrlLabel").classList.toggle("hidden", app.mode !== "online");
    $("#managePanel").classList.remove("hidden");
    const grants = await hermit.host.permissions.list({ appId: app.appId });
    const grantRoot = $("#grants");
    grantRoot.replaceChildren();
    if (!grants.grants.length) grantRoot.textContent = "尚未持久授权。";
    for (const grant of grants.grants) {
      const row = document.createElement("div"); row.className = "row";
      const label = document.createElement("span");
      label.textContent = grant.capability + (grant.scope ? " · " + grant.scope : "") + "：" + (grant.decision === "allow" ? "允许" : "拒绝");
      const revoke = document.createElement("button"); revoke.textContent = "重置";
      revoke.onclick = async () => {
        await hermit.host.permissions.revoke({ appId: app.appId, capability: grant.capability, scope: grant.scope });
        row.remove();
        if (!grantRoot.children.length) grantRoot.textContent = "尚未持久授权。";
      };
      row.append(label, revoke); grantRoot.append(row);
    }
    const releaseRoot = $("#releases"); releaseRoot.replaceChildren();
    if (app.mode !== "local") { releaseRoot.textContent = "在线应用没有本地版本。"; return; }
    const versions = await hermit.host.apps.releases({ appId: app.appId });
    for (const release of versions.releases) {
      const row = document.createElement("div"); row.className = "row";
      const label = document.createElement("code");
      label.textContent = (release.versionName || release.releaseId.slice(0, 8)) + (release.releaseId === versions.activeReleaseId ? " · 当前" : "");
      row.append(label);
      if (release.releaseId !== versions.activeReleaseId) {
        const activate = document.createElement("button"); activate.textContent = "切换";
        activate.onclick = async () => {
          await hermit.host.apps.rollback({ appId: app.appId, releaseId: release.releaseId });
          say("代码版本已切换"); close("#managePanel"); await refresh();
        };
        row.append(activate);
      }
      releaseRoot.append(row);
    }
  }

  async function openDeploy(app, mode = "adb") {
    const value = await hermit.host.deploy.start({ appId: app.appId, mode });
    state.deploy = value;
    const connection = value.mode === "lan"
      ? ["export HERMIT_ADDRESS='" + value.address + "'", "export HERMIT_SPKI_PIN='" + value.spkiPin + "'"]
      : ["adb forward tcp:8765 tcp:8765"];
    $("#deployConfig").textContent = [
      ...connection,
      "Address: " + value.address,
      "App-Id: " + app.appId,
      "Authorization: Bearer " + value.token,
      value.spkiPin ? "TLS: 自签名证书 + 强制 SPKI 公钥固定" : "Transport: ADB-forwarded device loopback",
      "上传还需要 Idempotency-Key、X-Hermit-Expected-Release 与 X-Hermit-Content-SHA256。"
    ].join("\n");
    $("#deployPanel").classList.remove("hidden");
  }

  $("#addButton").onclick = () => $("#addPanel").classList.remove("hidden");
  $("#searchApps").oninput = event => {
    const query = event.currentTarget.value.trim().toLocaleLowerCase();
    let visible = 0;
    document.querySelectorAll(".app-card").forEach(card => {
      const matched = !query || card.dataset.search.includes(query);
      card.classList.toggle("hidden", !matched);
      if (matched) visible++;
    });
    $("#noResults").classList.toggle("hidden", !query || visible !== 0);
  };
  $("#closeAdd").onclick = () => close("#addPanel");
  $("#closeManage").onclick = () => close("#managePanel");
  $("#closeDeploy").onclick = () => close("#deployPanel");
  $("#closeDiagnostics").onclick = () => close("#diagnosticsPanel");
  $("#closeLicenses").onclick = () => close("#licensesPanel");
  $("#licensesButton").onclick = event => busy(event.currentTarget, async () => {
    const value = await hermit.host.licenses.info({});
    $("#licensesOutput").textContent = value.text;
    $("#licensesPanel").classList.remove("hidden");
  });
  $("#diagnosticsButton").onclick = event => busy(event.currentTarget, async () => {
    const report = await hermit.host.diagnostics.info({});
    $("#diagnosticsOutput").textContent = JSON.stringify(report, null, 2);
    $("#diagnosticsPanel").classList.remove("hidden");
  });
  $("#copyDiagnostics").onclick = event => busy(event.currentTarget, async () => {
    await hermit.clipboard.write({ label: "Hermit 诊断", text: $("#diagnosticsOutput").textContent });
    say("诊断报告已复制");
  });
  $("#installOnline").onclick = event => busy(event.currentTarget, async () => {
    const value = await hermit.host.apps.installOnline({ url: $("#url").value, name: $("#name").value });
    if (value.cancelled) return;
    $("#url").value = ""; $("#name").value = ""; close("#addPanel"); say("已添加“" + value.name + "”"); await refresh();
  });
  $("#importZip").onclick = event => busy(event.currentTarget, async () => {
    const value = await hermit.host.apps.importZip({ name: $("#name").value });
    if (!value.cancelled) { $("#name").value = ""; close("#addPanel"); say("ZIP 副本导入成功"); await refresh(); }
  });
  $("#importDirectory").onclick = event => busy(event.currentTarget, async () => {
    const value = await hermit.host.apps.importDirectory({ name: $("#name").value });
    if (!value.cancelled) { $("#name").value = ""; close("#addPanel"); say("目录快照导入成功"); await refresh(); }
  });
  $("#restoreBackup").onclick = event => busy(event.currentTarget, async () => {
    if (!confirm("恢复会创建新实例，不继承登录态和页面授权。继续？")) return;
    const value = await hermit.host.backup.restore({});
    if (!value.cancelled) { close("#addPanel"); say("已恢复“" + value.name + "”"); await refresh(); }
  });
  $("#installPackage").onclick = event => busy(event.currentTarget, async () => {
    const value = await hermit.host.apps.installPackageUrl({ url: $("#packageUrl").value, name: $("#name").value });
    say("远程包已安装"); close("#addPanel"); await refresh(); return value;
  });
  $("#installGithub").onclick = event => busy(event.currentTarget, async () => {
    const value = await hermit.host.apps.installGitHub({ owner: $("#githubOwner").value, repo: $("#githubRepo").value,
      ref: $("#githubRef").value, path: $("#githubPath").value, name: $("#name").value });
    say("GitHub 应用已安装"); close("#addPanel"); await refresh(); return value;
  });
  $("#saveApp").onclick = event => busy(event.currentTarget, async () => {
    const app = state.selected;
    const value = await hermit.host.apps.update({ appId: app.appId, name: $("#editName").value, url: $("#editUrl").value });
    if (value.cancelled) return;
    close("#managePanel"); say("应用设置已保存"); await refresh();
  });
  $("#restoreAppData").onclick = event => busy(event.currentTarget, async () => {
    const app = state.selected;
    if (!app || !confirm("用所选备份替换“" + app.name + "”的 Hermit 记录与附件？原数据不可恢复，地址/代码保持不变。")) return;
    const value = await hermit.host.backup.restoreData({ appId: app.appId });
    if (!value.cancelled) { close("#managePanel"); say("数据恢复完成，页面授权已重置"); await refresh(); }
  });
  $("#copyDeploy").onclick = async () => {
    await hermit.clipboard.write({ label: "Hermit 开发连接", text: $("#deployConfig").textContent });
    say("开发配置已复制");
  };
  $("#stopDeploy").onclick = async () => {
    await hermit.host.deploy.stop({}); state.deploy = null; close("#deployPanel"); say("开发连接已停止");
  };
  addEventListener("hermitready", () => refresh().catch(error => say(error.message, true)), { once: true });
  if (hermit.isReady) refresh().catch(error => say(error.message, true));
})();
