(() => {
  "use strict";
  const H = window.HermitShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction, selected } = H.ui;
  const copy = (text, label) => H.copy(text, label);
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

  bind("#licensesButton", async () => { const value = await host.call("licenses.info", {}); $("#licensesOutput").textContent = value.text; open("#licensesPanel"); });
  bind("#diagnosticsButton", async () => { const report = await host.call("diagnostics.info", {}); $("#diagnosticsOutput").textContent = JSON.stringify(report, null, 2); open("#diagnosticsPanel"); });
  bind("#copyDiagnostics", () => copy($("#diagnosticsOutput").textContent, "诊断报告"));
  bind("#githubButton", () => host.call("about.openRepository", {}));

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

  bind("#updateLocalShell", async () => {
    const value = await host.call("shell.updateLocal", {});
    say("本地界面已更新到 " + value.localVersion + "，正在重新加载。");
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
    const results = await Promise.allSettled([loadAbout(), loadVoiceSettings(true)]);
    if (results[1].status === "rejected") setStatus("#speechCapabilityStatus", (results[1].reason && results[1].reason.message) || "语音能力检测失败", "error");
  }
  H.features.settings = { loadAbout, loadVoiceSettings, load };
})();
