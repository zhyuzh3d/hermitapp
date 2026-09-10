# Hermit page SDK

Hermit injects `window.hermit` at document start for the exact top-level origin. Do not bundle a native transport or persist `sessionId`; each navigation receives a new document session.

Wait for `hermitready` or check `hermit.isReady`, then call a namespace method with one object argument. Store durable binary references as `logicalFileId`. Treat revisions and subscription IDs as opaque. Catch errors by `error.code`; user cancellation, a denied page grant, a denied Android permission, and an unavailable system service are intentionally different results.

The source of truth is `api/capabilities.json`, `api/protocol-v1.md`, and `hermit-api.d.ts`. Host methods are not available to installed page applications.
