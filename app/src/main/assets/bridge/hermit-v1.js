(() => {
  "use strict";
  if (window.hermit) return;
  const transport = window.__hermitTransportV1;
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const documentId = Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
  const pending = new Map();
  const queued = [];
  const listeners = new Map();
  let ready = false;
  let sessionId = null;
  let documentEpoch = -1;
  let sequence = 0;
  const MAX_PENDING = 16;

  function send(message) { transport.postMessage(JSON.stringify(message)); }
  function request(method, params) {
    if (pending.size >= MAX_PENDING) {
      const error = new Error("Too many concurrent Hermit requests");
      error.code = "E_QUOTA";
      return Promise.reject(error);
    }
    return new Promise((resolve, reject) => {
      const id = documentId + "-" + (++sequence);
      const timeout = setTimeout(() => {
        if (!pending.has(id)) return;
        pending.delete(id);
        const error = new Error("Hermit request timed out");
        error.code = "E_TIMEOUT";
        reject(error);
      }, 60000);
      const item = { id, resolve, reject, timeout, message: {
        kind: "request", v: 1, id, documentId, sessionId, documentEpoch,
        method, params: params || {}
      }};
      pending.set(id, item);
      if (ready) send(item.message); else queued.push(item);
    });
  }
  function flush() {
    while (queued.length) {
      const item = queued.shift();
      item.message.sessionId = sessionId;
      item.message.documentEpoch = documentEpoch;
      send(item.message);
    }
  }
  function failAll(code, message) {
    ready = false;
    for (const item of pending.values()) {
      clearTimeout(item.timeout);
      const error = new Error(message);
      error.code = code;
      item.reject(error);
    }
    pending.clear();
    queued.length = 0;
  }

  transport.addEventListener("message", event => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (message.kind === "challenge" && message.documentId === documentId) {
      send({ kind: "ack", documentId, challenge: message.challenge });
      return;
    }
    if (message.kind === "ready" && message.documentId === documentId) {
      ready = true;
      sessionId = message.sessionId;
      documentEpoch = message.documentEpoch;
      flush();
      window.dispatchEvent(new CustomEvent("hermitready"));
      return;
    }
    if (message.kind === "response") {
      if (message.documentEpoch !== documentEpoch) return;
      const item = pending.get(message.id);
      if (!item) return;
      pending.delete(message.id);
      clearTimeout(item.timeout);
      if (message.ok) item.resolve(message.result);
      else {
        const error = new Error(message.error && message.error.message || "Hermit request failed");
        Object.assign(error, message.error || {});
        item.reject(error);
      }
      return;
    }
    if (message.kind === "event" && message.documentEpoch === documentEpoch) {
      const set = listeners.get(message.event);
      if (set) for (const listener of set) {
        try { listener(message.data); } catch (error) { console.error(error); }
      }
    }
  });

  window.addEventListener("pagehide", event => {
    if (!event.persisted) failAll("E_SESSION_EXPIRED", "Page left");
  });
  window.addEventListener("pageshow", event => {
    if (event.persisted) send({ kind: "hello", v: 1, documentId });
  });
  window.addEventListener("__hermitrecover", () => {
    failAll("E_SESSION_EXPIRED", "Navigation did not commit; document session renewed");
    send({ kind: "hello", v: 1, documentId });
  });
  send({ kind: "hello", v: 1, documentId });

  const namespace = prefix => new Proxy({}, {
    get: (_, name) => (...args) => request(prefix + "." + String(name), args.length === 1 && typeof args[0] === "object" ? args[0] : { args })
  });
  const api = {
    call: request,
    runtime: namespace("runtime"),
    app: namespace("app"),
    permissions: namespace("permissions"),
    data: namespace("data"),
    files: namespace("files"),
    tts: namespace("tts"),
    speech: namespace("speech"),
    location: namespace("location"),
    camera: namespace("camera"),
    share: namespace("share"),
    clipboard: namespace("clipboard"),
    haptics: namespace("haptics"),
    network: namespace("network"),
    host: {
      apps: namespace("host.apps"),
      deploy: namespace("host.deploy"),
      operations: namespace("host.operations"),
      backup: namespace("host.backup"),
      permissions: namespace("host.permissions"),
      diagnostics: namespace("host.diagnostics"),
      licenses: namespace("host.licenses")
    },
    on(name, listener) {
      if (!listeners.has(name)) listeners.set(name, new Set());
      listeners.get(name).add(listener);
      return () => listeners.get(name).delete(listener);
    },
    get isReady() { return ready; }
  };
  Object.defineProperty(window, "hermit", { value: Object.freeze(api), configurable: false });
})();
