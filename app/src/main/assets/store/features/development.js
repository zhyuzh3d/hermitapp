(() => {
  "use strict";
  const H = window.HermitShell;
  const { $, state, host } = H;
  const { say, bind, confirmAction } = H.ui;
  const copy = async (text, label) => { await host.copy(text); say(label + "已复制。"); };
  let agentState = null, agentPoll = false;

  function renderAgent(value) {
    agentState = value;
    $("#agentStatus").textContent = value.active ? "已开启 · 普通 happ 可开发" : "未开启" + (value.reason ? " · " + value.reason : "");
    $(".developer-card").classList.toggle("active", !!value.active);
    $("#developmentDot").classList.toggle("hidden", !value.active);
    $("#agentToggle").classList.toggle("on", !!value.active);
    $("#agentToggle").setAttribute("aria-checked", String(!!value.active));
    $("#agentToggle").setAttribute("aria-label", value.active ? "关闭智能体开发模式" : "开启智能体开发模式");
    $("#copyAgentAddress").disabled = !value.active;
    $("#copyAgentUsb").disabled = !value.active;
    $("#startAgentUsb").classList.toggle("hidden", !!value.active);
    const firstIp = (value.addresses || [])[0];
    const address = value.address || (firstIp ? "http://" + firstIp + ":8766" : "");
    $("#agentUrl").textContent = address || "未连接 Wi-Fi";
    $("#agentUrl").dataset.address = address;
    $("#agentUsb").textContent = value.usbCommand || "adb forward tcp:8766 tcp:8766";
    if (document.activeElement !== $("#agentPassword")) $("#agentPassword").value = value.password || "";
    $("#agentEvents").textContent = (value.events || []).slice().reverse().map(event => new Date(event.time).toLocaleTimeString() + "  " + event.tool + "  " + event.result).join("\n") || "暂无操作";
  }

  async function refreshAgent() {
    if (agentPoll) return;
    agentPoll = true;
    try { renderAgent(await host.call("agent.status")); } finally { agentPoll = false; }
  }

  bind("#agentToggle", async () => {
    if (agentState && agentState.active) {
      await host.call("agent.stop");
      renderAgent(await host.call("agent.status"));
      say("开发模式已关闭。");
      return;
    }
    const address = agentState && (agentState.addresses || [])[0];
    if (!address) throw new Error("当前没有局域网地址，可使用下方“仅 USB 启动”。");
    if (!await confirmAction("开启智能体开发模式？", "持有密码的电脑可修改普通 happ 的开发副本。请仅在可信局域网使用。", "开启开发模式")) return;
    renderAgent(await host.call("agent.start", { address, mode:"lan" }));
    say("开发模式已开启，请仅向可信智能体分享连接信息。");
  });

  bind("#startAgentUsb", async () => {
    if (!await confirmAction("仅通过 USB 启动？", "电脑需要执行页面显示的 adb forward 命令，接口和开发副本与局域网模式完全相同。", "启动 USB 模式")) return;
    renderAgent(await host.call("agent.start", { mode:"usb" }));
    say("USB 开发服务已启动，请在电脑执行转发命令。");
  });

  bind("#saveAgentPassword", async () => {
    const password = $("#agentPassword").value.trim();
    if (!/^[0-9]{6}$/.test(password)) throw new Error("密码必须是 6 位数字。");
    const value = await host.call("agent.resetPassword", { password });
    renderAgent(value);
    say("新密码已生效，旧密码已失效。");
  });
  $("#agentPassword").oninput = event => { event.currentTarget.value = event.currentTarget.value.replace(/\D/g, "").slice(0, 6); };
  bind("#copyAgentAddress", () => {
    const address = $("#agentUrl").dataset.address;
    if (!address) throw new Error("局域网服务尚未启动。");
    return copy(address, "局域网地址");
  });
  bind("#copyAgentUsb", () => copy($("#agentUsb").textContent, "USB 命令"));
  setInterval(async () => {
    if (document.hidden || !host.ready() || state.view !== "development") return;
    try { await refreshAgent(); } catch (_) {}
  }, 3000);
  H.features.development = { refreshAgent };
  H.copy = copy;
})();
