(() => {
  "use strict";
  const H = window.HermitShell;
  const methods = new Set(["dialog.resolve", "apps.importZip", "backup.export", "backup.exportAll", "backup.exportSettings", "backup.autoBackup.get", "backup.autoBackup.pickDirectory", "backup.autoBackup.save", "backup.autoBackup.runNow", "settings.get", "settings.set", "support.open", "shell.reload", "shell.status", "shell.setMode", "about.info", "about.openRepository", "agent.refresh", "agent.resetPassword", "agent.start", "agent.status", "agent.stop", "apps.archive", "apps.enterDev", "apps.exportDev", "apps.favorite", "apps.installOnline", "apps.launch", "apps.leaveDev", "apps.list", "apps.pickIcon", "apps.pin", "apps.pruneReleases", "apps.reinstall", "apps.releases", "apps.remove", "apps.resetDev", "apps.rollback", "apps.scanQr", "apps.setCrossOriginNetwork", "apps.setNotificationEnabled", "apps.setRuntimeMode", "apps.shareInstall", "apps.shareSave", "apps.shareSend", "apps.shareStart", "apps.shareStop", "apps.update", "apps.updateFromSource", "apps.updatePresentation", "apps.updateUrls", "backup.restore", "backup.restoreData", "diagnostics.info", "licenses.info", "permissions.list", "permissions.revoke", "shell.updateLocal", "voice.status", "voice.ttsVoices", "voice.configure", "voice.testTts", "voice.openSettings"]);
  async function call(method, params = {}) {
    if (!methods.has(method)) throw new Error("未登记的宿主操作：" + method);
    if (!window.hermit || !window.hermit.isReady) throw new Error("请在 HermitApp 中使用此功能。");
    try { return await window.hermit.call("host." + method, params); }
    catch (error) {
      if (error.code === "E_UNSUPPORTED" && /未知管理方法|不支持的协议版本|当前版本尚不支持/.test(error.message || "")) {
        error.message = "当前 APK 不支持此功能，请更新 HermitApp。";
      }
      throw error;
    }
  }
  H.host = { call, ready: () => !!(window.hermit && window.hermit.isReady),
    reportTheme: theme => {
      if (!window.hermit || !window.hermit.isReady) return Promise.resolve();
      return window.hermit.call("appearance.reportTheme", { theme });
    },
    copy: text => {
      if (!window.hermit || !window.hermit.isReady) return Promise.reject(new Error("请在 HermitApp 中复制内容。"));
      return window.hermit.call("clipboard.write", { text, label: "Hermit" });
    } };
})();
