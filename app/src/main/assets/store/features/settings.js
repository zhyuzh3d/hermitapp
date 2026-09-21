(() => {
  "use strict";
  const H = window.HermitShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction, selected } = H.ui;
  const copy = (text, label) => H.copy(text, label);
  const SETTINGS_TABS = ["interface", "tts", "speech", "system"];
  function showSettingsTab(value) {
    const active = SETTINGS_TABS.includes(value) ? value : "interface";
    state.settingsTab = active;
    $$('[data-settings-tab]').forEach(button => {
      const selected = button.dataset.settingsTab === active;
      button.classList.toggle("selected", selected);
      button.setAttribute("aria-selected", String(selected));
      button.tabIndex = selected ? 0 : -1;
    });
    $$('[data-settings-pane]').forEach(pane => pane.classList.toggle("hidden", pane.dataset.settingsPane !== active));
  }
  $$('[data-settings-tab]').forEach(button => { button.onclick = () => showSettingsTab(button.dataset.settingsTab); });
  showSettingsTab(state.settingsTab);
  function renderLanguageChoice() { selected("#languageChoices", "languageChoice", H.i18n.preference()); }
  renderLanguageChoice();
  $$('[data-language-choice]').forEach(button => {
    button.onclick = () => { H.i18n.setPreference(button.dataset.languageChoice); renderLanguageChoice(); };
  });
  addEventListener("hermitlanguagechange", renderLanguageChoice);
  let theme = "system";
  const systemDark = matchMedia("(prefers-color-scheme: dark)");
  try { theme = localStorage.getItem("hermit.theme") || "system"; } catch (_) {}
  function resolvedTheme() { return theme === "system" ? (systemDark.matches ? "dark" : "light") : theme; }
  function reportTheme() {
    host.reportTheme(resolvedTheme()).catch(() => {});
  }
  function applyTheme(value) {
    theme = ["system", "light", "dark"].includes(value) ? value : "system";
    if (theme === "system") delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = theme;
    selected("#themeChoices", "themeChoice", theme);
    try { localStorage.setItem("hermit.theme", theme); } catch (_) {}
    reportTheme();
  }
  applyTheme(theme);
  addEventListener("hermitready", reportTheme);
  const systemThemeChanged = () => { if (theme === "system") reportTheme(); };
  if (systemDark.addEventListener) systemDark.addEventListener("change", systemThemeChanged);
  else systemDark.addListener(systemThemeChanged);
  $$("[data-theme-choice]").forEach(button => { button.onclick = () => applyTheme(button.dataset.themeChoice); });

  async function openLicenses() { const value = await host.call("licenses.info", {}); $("#licensesOutput").textContent = value.text; open("#licensesPanel"); }
  async function openDiagnostics() { const report = await host.call("diagnostics.info", {}); $("#diagnosticsOutput").textContent = JSON.stringify(report, null, 2); open("#diagnosticsPanel"); }
  bind("#licensesButton", openLicenses);
  bind("#diagnosticsButton", openDiagnostics);
  bind("#copyDiagnostics", () => copy($("#diagnosticsOutput").textContent, "诊断报告"));
  bind("#githubButton", () => host.call("about.openRepository", {}));
  function showRestoreResult(value) {
    if (value.theme) applyTheme(value.theme);
    const root = $("#backupRestoreResult");
    root.replaceChildren();
    const lines = [];
    if (value.backupType === "settings") lines.push("Hermit 设置");
    if (value.backupType === "happ") lines.push(value.name ? "应用：“" + value.name + "”" : "单个 happ");
    if (value.backupType === "full") {
      if (value.settingsRestored) lines.push("Hermit 设置");
      const apps = Array.isArray(value.apps) ? value.apps : [];
      lines.push("全部 happ（" + (value.happCount ?? apps.length) + " 个）");
      apps.forEach(app => { if (app && app.name) lines.push("已恢复：“" + app.name + "”"); });
    }
    if (!lines.length) lines.push("备份内容");
    const heading = document.createElement("strong"); heading.textContent = "恢复完成"; root.append(heading);
    const list = document.createElement("ul"); lines.forEach(line => { const item = document.createElement("li"); item.textContent = line; list.append(item); }); root.append(list);
    open("#backupRestoreResultPanel");
  }
  bind("#restoreBackupSettings", async () => { const value = await host.call("backup.restore", {}); if (!value.cancelled) showRestoreResult(value); });
  bind("#openBackupActions", async () => { await loadBackupApps(); open("#backupActionsPanel"); });
  bind("#backupAll", async () => { await host.call("backup.exportAll", { theme }); say("全部备份已导出。"); close("#backupActionsPanel"); });
  bind("#backupSettings", async () => { await host.call("backup.exportSettings", { theme }); say("Hermit 设置备份已导出。"); close("#backupActionsPanel"); });
  async function loadBackupApps() {
    const root = $("#backupApps"); if (!root) return;
    root.replaceChildren();
    const value = await host.call("apps.list", {});
    const apps = value.apps || [];
    if (!apps.length) { root.textContent = "暂无可备份的 happ。"; return; }
    apps.forEach(app => {
      const button = document.createElement("button"); button.className = "backup-app-row"; button.type = "button";
      const icon = document.createElement("span"); icon.className = "backup-app-icon";
      if (app.iconUrl) { icon.style.backgroundImage = "url(" + JSON.stringify(app.iconUrl).slice(1, -1) + ")"; icon.classList.add("custom"); }
      else icon.innerHTML = '<i class="fa-solid fa-cube"></i>';
      const copy = document.createElement("span"); copy.className = "backup-app-copy";
      const name = document.createElement("strong"); name.textContent = app.name || "未命名应用";
      const version = document.createElement("small"); const active = app.activeVersion || {}; version.textContent = active.name || (Number.isFinite(active.code) ? "版本 " + active.code : "版本未标注");
      copy.append(name, version); const action = document.createElement("i"); action.className = "fa-solid fa-download"; action.setAttribute("aria-hidden", "true");
      button.append(icon, copy, action); button.setAttribute("aria-label", "备份 " + (app.name || "应用"));
      button.onclick = () => busy(button, async () => { await host.call("backup.export", { appId: app.appId }); say("已导出“" + app.name + "”备份。"); });
      root.append(button);
    });
  }

  let voiceLoaded = false, voiceStatus = null;
  function option(value, label, selectedValue, disabled = false) {
    const node = document.createElement("option");
    node.value = value; node.textContent = label; node.selected = value === selectedValue; node.disabled = disabled;
    return node;
  }
  function fillProviders(selector, providers, selectedValue, automaticLabel) {
    const select = $(selector);
    select.replaceChildren(option("system", automaticLabel, selectedValue));
    for (const provider of providers || []) select.append(option(provider.id, provider.label, selectedValue, provider.enabled === false));
  }
  function chooseOption(selector, value) {
    const select = $(selector), wanted = value || "automatic";
    Array.from(select.querySelectorAll("option")).forEach(item => { item.selected = item.value === wanted; });
  }
  function setStatus(selector, text, kind) {
    const element = $(selector); element.textContent = text; element.classList.toggle("ready", kind === "ready"); element.classList.toggle("error", kind === "error");
  }
  async function loadTtsVoices(engineId, selectedVoice) {
    const select = $("#ttsVoice");
    select.disabled = true; select.replaceChildren(option("automatic", "正在读取声音…", "automatic"));
    try {
      const value = await host.call("voice.ttsVoices", engineId === "system" ? {} : { engineId });
      select.replaceChildren(option("automatic", "自动选择", selectedVoice || "automatic"));
      for (const voice of value.voices || []) {
        const locality = voice.networkRequired ? "需联网" : "可离线";
        select.append(option(voice.id, (voice.locale || "未知语言") + " · " + locality, selectedVoice));
      }
      select.disabled = !(value.voices || []).length;
    } catch (error) {
      select.replaceChildren(option("automatic", "声音列表不可用", "automatic")); select.disabled = true;
      setStatus("#ttsCapabilityStatus", error.message || "朗读引擎不可用", "error");
    }
  }
  async function loadVoiceSettings(force = false) {
    if (voiceLoaded && !force) return;
    setStatus("#ttsCapabilityStatus", "正在检测…"); setStatus("#speechCapabilityStatus", "正在检测…");
    const value = await host.call("voice.status", {});
    voiceStatus = value;
    const tts = value.tts, speech = value.speech, ttsPrefs = tts.preferences || {}, speechPrefs = speech.preferences || {};
    fillProviders("#ttsEngine", tts.engines, ttsPrefs.engineSelection || "system", "跟随系统默认");
    chooseOption("#ttsLanguage", ttsPrefs.language || "automatic");
    setStatus("#ttsCapabilityStatus", tts.operational ? ((tts.fallbackFrom ? "已自动切换 · " : "可用 · ") + (tts.engines.find(item => item.id === tts.effectiveEngine)?.label || "系统引擎")) : (tts.error || "没有可用朗读引擎"), tts.operational ? "ready" : "error");
    await loadTtsVoices($("#ttsEngine").value, ttsPrefs.voiceSelection === "automatic" ? null : ttsPrefs.voiceSelection);
    fillProviders("#speechService", speech.services, speechPrefs.serviceSelection || "system", "自动选择");
    fillProviders("#speechActivity", speech.activities, speechPrefs.activitySelection || "system", "自动选择");
    chooseOption("#speechLanguage", speechPrefs.language || "automatic");
    $("#speechOfflineSwitch").classList.toggle("on", !!speechPrefs.preferOffline);
    $("#speechOfflineSwitch").setAttribute("aria-checked", String(!!speechPrefs.preferOffline));
    const capability = speech.capability || {};
    const speechLabel = capability.state === "ready" ? (capability.onDeviceAvailable ? "连续识别 · 支持离线" : "连续识别可用") : capability.state === "activity-only" ? "仅支持一次性识别" : (capability.message || "系统未提供语音识别");
    setStatus("#speechCapabilityStatus", speechLabel, capability.state === "unavailable" ? "error" : "ready");
    $("#speechService").disabled = !(speech.services || []).length && !capability.streamingAvailable;
    $("#speechActivity").disabled = !(speech.activities || []).length;
    $("#testSystemTts").disabled = !tts.operational;
    voiceLoaded = true;
  }

  $("#ttsEngine").onchange = event => loadTtsVoices(event.currentTarget.value, null);
  $("#speechOfflineSwitch").onclick = event => {
    const button = event.currentTarget, on = button.getAttribute("aria-checked") !== "true";
    button.classList.toggle("on", on); button.setAttribute("aria-checked", String(on));
  };
  bind("#testSystemTts", () => host.call("voice.testTts", { text: "Hermit 系统朗读测试" }));
  bind("#openTtsSettings", () => host.call("voice.openSettings", { type: "tts" }));
  bind("#openSpeechSettings", () => host.call("voice.openSettings", { type: "speech" }));
  bind("#saveVoiceSettings", async () => {
    const settings = { speech: {
      serviceId: $("#speechService").value, activityId: $("#speechActivity").value,
      language: $("#speechLanguage").value, preferOffline: $("#speechOfflineSwitch").getAttribute("aria-checked") === "true"
    }};
    if (voiceStatus?.tts?.operational) settings.tts = {
      engineId: $("#ttsEngine").value, voiceId: $("#ttsVoice").value, language: $("#ttsLanguage").value
    };
    await host.call("voice.configure", settings);
    voiceLoaded = false; await loadVoiceSettings(true); say("系统语音设置已保存。");
  });

  let shellState = null;
  function renderShellState(value) {
    shellState = value;
    const online = value.configuredMode === "online";
    const currentVersion = value.runningMode === "online" ? H.version : value.localVersion;
    $("#brandDot").classList.toggle("online", online);
    $("#shellVersionSwitch").textContent = "当前界面版本：" + currentVersion + (online ? " · 实时在线" : " · 本地");
    $("#useLocalShell").classList.toggle("hidden", !online);
  }
  async function loadShellStatus() { renderShellState(await host.call("shell.status", {})); }
  let shellVersionTaps = 0, shellVersionTapTimer;
  $("#shellVersionSwitch").onclick = event => {
    clearTimeout(shellVersionTapTimer);
    shellVersionTaps += 1;
    shellVersionTapTimer = setTimeout(() => { shellVersionTaps = 0; }, 1200);
    if (shellVersionTaps < 3) return;
    shellVersionTaps = 0;
    busy(event.currentTarget, async () => {
      if (shellState && shellState.configuredMode === "online") return;
      await host.call("shell.setMode", { mode:"online" });
    });
  };
  bind("#useLocalShell", async () => {
    if (shellState && shellState.configuredMode === "local") return;
    await host.call("shell.setMode", { mode:"local" });
  });
  bind("#updateLocalShell", async () => {
    const value = await host.call("shell.updateLocal", {});
    renderShellState(value);
    say("本地界面已更新到 " + value.localVersion + "。");
  });
  window.hermitShellUnavailable = message => {
    say(message || "界面更新暂时不可用，继续使用当前本地界面。", true);
  };

  async function loadAbout() {
    const value = await host.call("about.info", {});
    $("#aboutVersion").textContent = value.version + "（" + value.versionCode + "）";
    $("#topApkVersion").textContent = value.version;
    $("#aboutAuthor").textContent = value.author;
    $("#aboutAppId").textContent = value.applicationId;
  }

  bind("#supportRepository", () => host.call("about.openRepository", {}));
  async function load() {
    const results = await Promise.allSettled([loadAbout(), loadVoiceSettings(true), loadShellStatus()]);
    if (results[1].status === "rejected") setStatus("#speechCapabilityStatus", (results[1].reason && results[1].reason.message) || "语音能力检测失败", "error");
    if (results[2].status === "rejected") $("#shellVersionSwitch").textContent = "界面模式暂不可用";
  }
  H.features.settings = { loadAbout, loadVoiceSettings, load, openLicenses, openDiagnostics, showSettingsTab };
})();
