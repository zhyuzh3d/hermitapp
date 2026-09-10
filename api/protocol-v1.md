# Hermit RPC protocol v1

The Native host registers `__hermitTransportV1` before the first document script, restricted to the instance's exact origin and main frame. A document sends `hello`; Native returns a random challenge; the same reply proxy must return `ack`. Requests are accepted only after both the challenge and top-level navigation commit.

Every request carries `documentId`, Native-issued `sessionId`, and `documentEpoch`. Navigation invalidates all three bindings and cancels the session scope. Web input never chooses `appId`, profile, data generation, release, or role. `host.*` is accepted only from the fixed Store origin.

Responses use `{kind:"response", id, documentEpoch, ok, result}` or an error object `{code,message,retryable}`. Events use `{kind:"event", event, documentEpoch, data}`. Messages larger than 256 KiB and calls not completed within 60 seconds are rejected. Large binary data moves through logical file IDs, not RPC JSON.

Stable error codes are `E_INVALID_ARGUMENT`, `E_UNSUPPORTED`, `E_ORIGIN_DENIED`, `E_CAPABILITY_DENIED`, `E_OS_PERMISSION_DENIED`, `E_SESSION_EXPIRED`, `E_CANCELLED`, `E_TIMEOUT`, `E_CONFLICT`, `E_QUOTA`, `E_STORAGE`, `E_NETWORK`, and `E_INTERNAL`.
