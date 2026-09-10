(() => {
  const text = document.querySelector("#text");
  const status = document.querySelector("#status");
  const notes = document.querySelector("#notes");
  let pendingAttachment = null;
  const report = value => status.textContent = value;
  async function refresh() {
    const result = await hermit.data.scan({ collection: "notes", limit: 100 });
    notes.replaceChildren(...result.items.reverse().map(item => {
      const row = document.createElement("article"); row.className = "note";
      const body = document.createElement("p"); body.textContent = item.value.text; row.append(body);
      if (item.value.attachment) {
        const label = document.createElement("small"); label.textContent = "附件：" + item.value.attachment.name; row.append(label);
        const share = document.createElement("button"); share.textContent = "分享";
        share.onclick = () => hermit.files.share({ logicalFileId: item.value.attachment.logicalFileId }).catch(error => report(error.message));
        const save = document.createElement("button"); save.textContent = "导出";
        save.onclick = () => hermit.files.export({ logicalFileId: item.value.attachment.logicalFileId }).catch(error => report(error.message));
        row.append(share, save);
      }
      return row;
    }));
  }
  document.querySelector("#save").onclick = async () => {
    const value = text.value.trim(); if (!value) return;
    await hermit.data.put({ collection: "notes", key: String(Date.now()).padStart(16, "0"),
      value: { text: value, createdAt: Date.now(), attachment: pendingAttachment }, expectedRevision: "absent" });
    text.value = ""; pendingAttachment = null; report("已保存"); await refresh();
  };
  document.querySelector("#speak").onclick = () => hermit.tts.speak({ text: text.value || "请先输入内容", language: "zh-CN" }).catch(error => report(error.message));
  document.querySelector("#attach").onclick = async () => {
    const file = await hermit.files.import({});
    if (!file.cancelled) { pendingAttachment = file; report("附件将在保存笔记时关联：" + file.name); }
  };
  document.querySelector("#listen").onclick = async () => {
    const offFinal = hermit.on("speech.final", value => { text.value += (text.value ? "\n" : "") + (value.alternatives[0]?.text || ""); offFinal(); });
    try { await hermit.speech.start({ language: "zh-CN", partial: true }); } catch (error) { offFinal(); report(error.message); }
  };
  addEventListener("hermitready", () => refresh().catch(error => report(error.message)), { once: true });
  if (hermit.isReady) refresh().catch(error => report(error.message));
})();
