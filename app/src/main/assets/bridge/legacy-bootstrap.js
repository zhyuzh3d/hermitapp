(() => {
  "use strict";
  if (window.__hermitTransportV1) return;
  const native = window.__hermitLegacyNativeV1;
  if (!native || typeof native.postMessage !== "function") return;
  const listeners = new Set();
  window.__hermitLegacyReceiveV1 = value => {
    const event = Object.freeze({ data: String(value) });
    for (const listener of [...listeners]) {
      try { listener(event); } catch (error) { console.error(error); }
    }
  };
  Object.defineProperty(window, "__hermitTransportV1", {
    configurable: false,
    value: Object.freeze({
      postMessage(value) { native.postMessage(String(value)); },
      addEventListener(type, listener) { if (type === "message" && typeof listener === "function") listeners.add(listener); },
      removeEventListener(type, listener) { if (type === "message") listeners.delete(listener); }
    })
  });
})();
