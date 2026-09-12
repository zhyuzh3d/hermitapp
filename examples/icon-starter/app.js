(() => {
  'use strict';
  const text = document.querySelector('#text'), save = document.querySelector('#save'), status = document.querySelector('#status');
  let started = false;
  async function ready() {
    if (started) return;
    started = true;
    try {
      const record = await hermit.data.get({ collection: 'notes', key: 'draft' });
      text.value = record?.value?.text || '';
      status.textContent = '图标离线可用；笔记保存在当前应用的独立空间。';
    } catch (error) { status.textContent = error.message; }
    finally { save.disabled = false; }
  }
  save.onclick = async () => {
    save.disabled = true;
    try {
      await hermit.data.put({ collection: 'notes', key: 'draft', value: { text: text.value } });
      status.textContent = '已保存。关闭页面、更新代码后仍可读取。';
    } catch (error) { status.textContent = error.message; }
    finally { save.disabled = false; }
  };
  window.addEventListener('hermitready', ready);
  if (window.hermit?.isReady) ready();
  if (!window.hermit) status.textContent = '请导入 Hermit 1.1.0 或更高版本，使用内置图标与保存功能。';
})();
