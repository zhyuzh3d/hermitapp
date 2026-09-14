(() => {
  "use strict";
  const H = window.HermitShell;
  async function save(app, desired) {
    const completed = [];
    let current = Object.assign({}, app);
    async function step(method, params, label) {
      const value = await H.host.call(method, Object.assign({ appId: app.appId }, params));
      if (value && value.cancelled) {
        const error = new Error("保存已取消。"); error.code = "E_CANCELLED"; throw error;
      }
      completed.push(label);
      current = Object.assign(current, value);
    }
    try {
      if (desired.liveUrl !== (app.liveUrl || "") || desired.updateUrl !== (app.updateUrl || ""))
        await step("apps.updateUrls", { liveUrl: desired.liveUrl, updateUrl: desired.updateUrl, insecureConfirmed:!!desired.insecureConfirmed }, "应用地址");
      const currentCustomIcon = Object.prototype.hasOwnProperty.call(app, "customIconDataUrl") ? (app.customIconDataUrl || "") : (app.iconDataUrl || "");
      const desiredIcon = typeof desired.customIconDataUrl === "string" ? desired.customIconDataUrl : currentCustomIcon;
      const iconChanged = desiredIcon !== currentCustomIcon;
      if (desired.name !== app.name || iconChanged) {
        const presentation = { name: desired.name };
        if (iconChanged) presentation.iconDataUrl = desiredIcon;
        await step(iconChanged ? "apps.updatePresentation" : "apps.update", presentation, desired.name !== app.name && iconChanged ? "名称和图标" : iconChanged ? "图标" : "名称");
      }
      if (desired.notificationEnabled !== !!current.notificationEnabled)
        await step("apps.setNotificationEnabled", { enabled: desired.notificationEnabled }, "通知设置");
      if (desired.allowCrossOriginNetwork !== !!current.allowCrossOriginNetwork)
        await step("apps.setCrossOriginNetwork", { enabled: desired.allowCrossOriginNetwork }, "网络设置");
      if (desired.runtimeMode !== current.runtimeMode)
        await step("apps.setRuntimeMode", { runtimeMode: desired.runtimeMode }, "运行方式");
      return current;
    } catch (error) {
      if (completed.length) {
        error.message = "已保存" + completed.join("、") + "；其余修改未完成。" + error.message;
        error.code = "E_PARTIAL_SAVE";
      }
      error.completed = completed;
      throw error;
    }
  }
  H.features.appSettings = { save };
})();
