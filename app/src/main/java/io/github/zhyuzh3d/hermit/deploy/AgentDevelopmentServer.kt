package io.github.zhyuzh3d.hermit.deploy

import android.content.Context
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import fi.iki.elonen.NanoHTTPD
import io.github.zhyuzh3d.hermit.BuildConfig
import io.github.zhyuzh3d.hermit.install.InstallCoordinator
import io.github.zhyuzh3d.hermit.model.HermitException
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.registry.AppRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** A foreground, explicitly enabled developer control plane, separate from page RPC authority. */
class AgentDevelopmentServer(
    private val context: Context,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
    private val devWorkspaces: DevWorkspaceManager,
    private val scope: CoroutineScope,
) {
    private val workspace = AgentWorkspace(context.cacheDir, registry, installer)
    private val catalogText by lazy { asset("agent/tools.json") }
    private val catalog by lazy { JSONArray(catalogText) }
    private val guidanceVersion by lazy { AgentWorkspace.sha((guide() + resource("hermit://webapp-guide") + resource("hermit://page-api")).toByteArray()) }
    @Volatile private var active: Endpoint? = null
    @Volatile private var uiHandler: ((String, JSONObject) -> JSONObject)? = null
    private data class RenderOperation(
        val id: String, val appId: String, val revision: Long, val createdAt: Long,
        val result: CompletableDeferred<JSONObject> = CompletableDeferred(),
    )
    private val renderOperations = ConcurrentHashMap<String, RenderOperation>()
    private val devDiagnostics = ConcurrentHashMap<String, ArrayDeque<JSONObject>>()
    @Volatile var lastStopReason = "Not started"
        private set
    private var expiry: Job? = null
    @Volatile private var stateHandler: ((Boolean) -> Unit)? = null
    fun setStateHandler(handler: ((Boolean) -> Unit)?) { stateHandler = handler; handler?.invoke(active != null) }
    private val preferences = context.getSharedPreferences("agent-development", Context.MODE_PRIVATE)

    @Synchronized fun passwordForUi(): String {
        val saved = preferences.getString("password", null)
        if (saved != null && saved.matches(Regex("[0-9]{6}"))) return saved
        return newPassword(null)
    }

    private fun newPassword(previous: String?): String {
        var value: String
        do { value = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0') } while (value == previous)
        check(preferences.edit().putString("password", value).commit()) { "Unable to save password" }
        return value
    }

    @Synchronized fun resetPassword(requested: String? = null): JSONObject {
        if (requested != null && !requested.matches(Regex("[0-9]{6}"))) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "密码必须是 6 位数字")
        }
        if (requested == null) newPassword(passwordForUi())
        else check(preferences.edit().putString("password", requested).commit()) { "Unable to save password" }
        return status()
    }

    private fun authorized(header: String) = constantEquals("Bearer " + passwordForUi(), header)

    fun setUiHandler(handler: ((String, JSONObject) -> JSONObject)?) { uiHandler = handler }

    fun reportDevRender(appId: String, revision: Long, url: String) {
        renderOperations.values.filter { it.appId == appId && it.revision == revision && !it.result.isCompleted }.forEach { operation ->
            operation.result.complete(JSONObject().put("state", "rendered").put("operationId", operation.id)
                .put("appId", appId).put("revision", revision).put("url", url).put("renderedAt", System.currentTimeMillis()))
        }
    }

    fun recordDevDiagnostic(appId: String, revision: Long?, kind: String, message: String, url: String?) {
        val events = devDiagnostics.computeIfAbsent(appId) { ArrayDeque() }
        synchronized(events) {
            if (events.size >= 100) events.removeFirst()
            events.addLast(JSONObject().put("kind", kind).put("message", message.take(2048))
                .put("revision", revision ?: JSONObject.NULL).put("url", url?.take(2048) ?: JSONObject.NULL)
                .put("time", System.currentTimeMillis()))
        }
    }
    fun addresses(): List<String> {
        val networks = context.getSystemService(ConnectivityManager::class.java)
        val interfaces = networks.allNetworks.mapNotNull { network ->
            val capabilities = networks.getNetworkCapabilities(network) ?: return@mapNotNull null
            val name = networks.getLinkProperties(network)?.interfaceName ?: return@mapNotNull null
            name to (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val lan = interfaces.filter { it.second }.map { it.first }.toSet()
        val excluded = interfaces.filter { !it.second }.map { it.first }.toSet()
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && it.name !in excluded }
            .sortedBy { if (it.name in lan) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }.mapNotNull { it.hostAddress }.distinct()
    }

    @Synchronized fun start(bindAddress: String? = null, port: Int = 8766): JSONObject {
        val host = bindAddress ?: addresses().firstOrNull() ?: throw IllegalStateException("没有可用的局域网 IPv4 地址，请连接 Wi-Fi")
        if (host != "127.0.0.1" && host !in addresses()) throw IllegalArgumentException("Selected LAN address is unavailable")
        passwordForUi()
        stop("Replaced")
        val bindHost = if (host == "127.0.0.1") host else "0.0.0.0"
        val endpoint = Endpoint(bindHost, host, port)
        try { endpoint.start(15_000, false); active = endpoint } catch (error: Exception) { endpoint.stop(); throw error }
        stateHandler?.invoke(true)
        expiry = scope.launch {
            while (isActive && active === endpoint) {
                delay(15_000)
                if (SystemClock.elapsedRealtime() - endpoint.lastAccess > IDLE_MS) stop("空闲 30 分钟，开发连接已关闭")
            }
        }
        return status()
    }

    @Synchronized fun stop(reason: String): JSONObject {
        val previous = active; active = null; expiry?.cancel(); expiry = null
        previous?.stop()
        stateHandler?.invoke(false)
        renderOperations.values.forEach { if (!it.result.isCompleted) it.result.cancel() }
        renderOperations.clear()
        devDiagnostics.clear()
        lastStopReason = reason
        return JSONObject().put("stopped", previous != null)
    }

    fun status(): JSONObject {
        val endpoint = active ?: return JSONObject().put("active", false).put("reason", lastStopReason).put("addresses", JSONArray(addresses())).put("password", passwordForUi())
        return JSONObject().put("active", true).put("address", endpoint.address).put("mcpUrl", endpoint.address + "/mcp")
            .put("serverVersion", BuildConfig.VERSION_NAME).put("runId", endpoint.runId)
            .put("idleTimeoutSeconds", IDLE_MS / 1000).put("remainingSeconds", (IDLE_MS - (SystemClock.elapsedRealtime() - endpoint.lastAccess)).coerceAtLeast(0) / 1000)
            .put("password", passwordForUi()).put("addresses", JSONArray(addresses()))
            .put("usbAddress", "http://127.0.0.1:${endpoint.listeningPort}")
            .put("usbCommand", "adb forward tcp:${endpoint.listeningPort} tcp:${endpoint.listeningPort}")
            .put("events", JSONArray(synchronized(endpoint.events) { endpoint.events.toList() }))
    }

    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
    private fun guide() = asset("agent/hermit-device/SKILL.md")
    private fun resource(uri: String) = when (uri) {
        "hermit://guide" -> guide()
        "hermit://tools" -> catalogText
        "hermit://webapp-guide" -> asset("agent/webapp-authoring.md")
        "hermit://page-api" -> asset("agent/hermit-api.d.ts")
        else -> throw RpcError(-32602, "Unknown resource URI")
    }

    private class RpcError(val code: Int, override val message: String) : RuntimeException(message)

    private inner class Endpoint(private val bindHost: String, private val advertisedHost: String, port: Int) : NanoHTTPD(bindHost, port) {
        val runId: String = UUID.randomUUID().toString()
        val address get() = "http://$advertisedHost:$listeningPort"
        private val receipts = LinkedHashMap<String, Pair<String, JSONObject>>()
        val events = ArrayDeque<JSONObject>()
        private val writes = ConcurrentHashMap<String, Semaphore>()
        private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()
        @Volatile var lastAccess = SystemClock.elapsedRealtime()
        init { setAsyncRunner(BoundedAsyncRunner(4)) }
        private fun live() { if (active !== this) throw IllegalStateException("Developer session stopped") }
        private fun live(authorization: String) { live(); check(authorized(authorization)) { "Password changed; reconnect with the current password" } }
        private fun guarded(authorization: String, action: () -> Unit) = synchronized(this@AgentDevelopmentServer) { live(authorization); action() }
        private fun requireApp(id: String) {
            if (registry.getInstance(id) == null) throw IllegalArgumentException("App not found")
        }

        override fun serve(session: IHTTPSession): Response {
            try {
                if (active !== this) return httpError(503, "Developer session stopped")
                if (session.headers["host"] !in setOf("$advertisedHost:$listeningPort", "127.0.0.1:$listeningPort", "localhost:$listeningPort")) return httpError(400, "Invalid Host")
                if (session.headers["origin"] != null) return httpError(403, "Browser-origin API requests are not accepted")
                if (session.headers.entries.sumOf { it.key.length + it.value.length } > 16384) return httpError(431, "Headers too large")
                val peer = session.remoteIpAddress
                if (session.method == Method.GET) when (session.uri) {
                    "/", "/connect" -> return text(200, "text/plain", connectionGuide())
                    "/.well-known/hermit-agent" -> return json(200, discovery())
                    "/skills/hermit-device/SKILL.md" -> return text(200, "text/markdown", guide())
                    "/hermit-agent.py" -> return text(200, "text/x-python", asset("agent/hermit-agent.py"))
                }
                val authorization = session.headers["authorization"].orEmpty()
                val now = SystemClock.elapsedRealtime()
                val failure = failures[peer]
                if (failure != null && failure.first >= 5 && now - failure.second < 60_000)
                    return httpError(429, "Too many incorrect passwords; wait one minute").apply { addHeader("Retry-After", "60") }
                if (!authorized(authorization)) {
                    synchronized(failures) {
                        failures.entries.removeIf { now - it.value.second >= 60_000 }
                        if (failures.size >= 256 && !failures.containsKey(peer)) return httpError(429, "Authentication temporarily rate limited")
                        val previous = failures[peer]
                        failures[peer] = ((previous?.first ?: 0) + 1) to (previous?.second ?: now)
                    }
                    return httpError(401, "Current six-digit password required; GET /connect for instructions").apply { addHeader("WWW-Authenticate", "Bearer realm=\"Hermit developer\"") }
                }
                failures.remove(peer)
                lastAccess = SystemClock.elapsedRealtime()
                if (session.uri == "/mcp") {
                    if (session.method !in setOf(Method.POST)) return httpError(405, "Only MCP POST is supported; no SSE stream").apply { addHeader("Allow", "POST") }
                    val protocol = session.headers["mcp-protocol-version"]
                    if (protocol != null && protocol !in PROTOCOLS) return httpError(400, "Unsupported MCP-Protocol-Version")
                    val accept = session.headers["accept"].orEmpty()
                    if (!accept.contains("application/json") || !accept.contains("text/event-stream")) return httpError(406, "Accept must include application/json and text/event-stream")
                    val request = body(session, MAX_RPC)
                    if (protocol == MODERN_PROTOCOL) validateModernHeaders(session, request)
                    return rpc(authorization, request, protocol == MODERN_PROTOCOL)
                }
                if (session.method == Method.GET && session.uri.matches(Regex("/v2/builds/[a-zA-Z0-9-]+"))) return downloadBuild(authorization, session)
                if (session.method == Method.PUT && session.uri.matches(Regex("/v2/apps/[a-zA-Z0-9-]+/dev/file"))) return uploadDevFile(authorization, session)
                if (session.method == Method.PUT && session.uri.matches(Regex("/v2/apps/[a-zA-Z0-9-]+/dev/tree"))) return uploadDevTree(authorization, session)
                if (session.method == Method.PUT && session.uri.matches(Regex("/v1/apps/[a-zA-Z0-9-]+/release"))) return upload(authorization, session)
                return httpError(404, "Not found")
            } catch (error: RpcError) { return json(400, rpcError(JSONObject.NULL, error.code, error.message)) }
            catch (error: Exception) { return httpError(400, error.message?.take(300) ?: "Invalid request") }
        }

        private fun discovery() = JSONObject().put("product", "Hermit").put("schema", 2).put("serverVersion", BuildConfig.VERSION_NAME)
            .put("runId", runId).put("mcpUrl", "$address/mcp").put("protocolVersions", JSONArray(PROTOCOLS))
            .put("skillUrl", "$address/skills/hermit-device/SKILL.md")
            .put("helperUrl", "$address/hermit-agent.py").put("helperSha256", AgentWorkspace.sha(asset("agent/hermit-agent.py").toByteArray()))
            .put("clientBootstrap", JSONObject()
                .put("platforms", JSONArray(listOf("Windows", "macOS", "Linux")))
                .put("preferred", JSONObject().put("transport", "streamable-http").put("url", "$address/mcp")
                    .put("authorizationHeader", "Bearer <current six-digit password>"))
                .put("prompt", "develop-webapp")
                .put("resources", JSONArray(listOf("hermit://guide", "hermit://tools", "hermit://webapp-guide", "hermit://page-api")))
                .put("fallback", JSONObject().put("runtime", "Python 3.10+ standard library")
                    .put("url", "$address/hermit-agent.py").put("stdioCommand", "<python> hermit-agent.py --address $address stdio")))
            .put("guidanceVersion", guidanceVersion).put("schemaDigest", AgentWorkspace.sha(catalogText.toByteArray()))
            .put("transport", "streamable-http").put("authentication", "Authorization: Bearer <current six-digit password>")
            .put("tools", catalog).put("limits", JSONObject().put("rpcBytes", MAX_RPC).put("zipBytes", MAX_ZIP)
                .put("textFileBytes", 512 * 1024).put("binaryFileBytes", 64 * 1024 * 1024))

        private fun connectionGuide() = """
            Hermit ${BuildConfig.VERSION_NAME} agent development connection
            Address: $address
            Read GET /.well-known/hermit-agent and GET /skills/hermit-device/SKILL.md after first connection or when runId/schemaDigest changes.
            This is a trusted-LAN HTTP service, not an encrypted Internet endpoint.
            Ask the user for the current six-digit password displayed in Hermit. No pairing or per-computer identity.
            POST MCP JSON-RPC to /mcp with Authorization: Bearer <password> and Accept: application/json, text/event-stream.
            The one persistent password authorizes all exposed developer tools and all apps on any computer.
            Changing the password invalidates old credentials on the next request, including existing HTTP connections.
            Optional standalone Python helper: GET /hermit-agent.py. Inspect it before running. It needs Python 3.10+ standard library only.
            The helper and direct MCP endpoint work on Windows, macOS and Linux; no files from the developer's computer are assumed.
            python3 hermit-agent.py --address $address connect
            Windows launcher alternative: py -3 hermit-agent.py --address $address connect
            python3 hermit-agent.py --address $address install-skill
            python3 hermit-agent.py --address $address client-config
            Select an ordinary happ and call hermit_enter_dev_mode before any write. HermitUI is protected and never exposed as a development target.
            No frontend build or framework support is needed. Author native HTML + JS + CSS; other tools' finished static output is accepted neutrally.
            Keep Hermit foreground (its WebApps count). Stop/background/30 minutes idle closes the server; the password remains valid on restart.
        """.trimIndent()

        private fun serverInstructions() = "Authenticate with the password shown by HermitApp. Select an ordinary happ and call hermit_enter_dev_mode before writing. HermitUI is protected. Develop directly runnable HTML/JS/CSS without framework or build assumptions. Update only the single dev workspace with expectedDevRevision and a unique requestId; preserve app identity, business data and grants. Use hermit_sync_dev_changes for fast saves and hermit_wait_dev_render when render evidence is needed. The same endpoint and tools work from Windows, macOS and Linux. Server version ${BuildConfig.VERSION_NAME}; guidance $guidanceVersion."

        private fun capabilities() = JSONObject().put("tools", JSONObject()).put("resources", JSONObject()).put("prompts", JSONObject())

        private fun validateModernHeaders(session: IHTTPSession, request: JSONObject) {
            val method = request.optString("method")
            if (session.headers["mcp-method"] != method) throw RpcError(-32020, "Mcp-Method header does not match the request")
            val expectedName = when (method) {
                "tools/call", "prompts/get" -> request.optJSONObject("params")?.optString("name")
                "resources/read" -> request.optJSONObject("params")?.optString("uri")
                else -> null
            }?.takeIf { it.isNotBlank() }
            if (expectedName != null && session.headers["mcp-name"] != expectedName) {
                throw RpcError(-32020, "Mcp-Name header does not match the request")
            }
            if (expectedName == null && session.headers["mcp-name"] != null) {
                throw RpcError(-32020, "Mcp-Name is not valid for this request")
            }
        }

        private fun rpc(authorization: String, request: JSONObject, modern: Boolean): Response {
            val id = request.opt("id") ?: JSONObject.NULL
            try {
                if (request.optString("jsonrpc") != "2.0" || request.opt("method") !is String || (request.has("id") && id !is String && id !is Number)) throw RpcError(-32600, "Invalid JSON-RPC request")
                val method = request.getString("method")
                if (!request.has("id")) {
                    if (!method.startsWith("notifications/")) throw RpcError(-32600, "Only notifications may omit id")
                    return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "").apply { addHeader("Cache-Control", "no-store"); addHeader("Connection", "close") }
                }
                val params = if (!request.has("params")) JSONObject() else request.optJSONObject("params") ?: throw RpcError(-32602, "params must be an object")
                val result: JSONObject = when (method) {
                    "server/discover" -> JSONObject().put("supportedVersions", JSONArray(PROTOCOLS))
                        .put("capabilities", capabilities()).put("instructions", serverInstructions())
                        .put("ttlMs", 60_000).put("cacheScope", "private")
                    "initialize" -> {
                        if (modern) throw RpcError(-32601, "initialize is not used by MCP $MODERN_PROTOCOL")
                        val protocol = params.optString("protocolVersion")
                        if (protocol.isBlank() || params.optJSONObject("clientInfo") == null || params.optJSONObject("capabilities") == null) throw RpcError(-32602, "protocolVersion, clientInfo and capabilities required")
                        val selected = protocol.takeIf { it in LEGACY_PROTOCOLS } ?: LEGACY_PROTOCOLS.first()
                        JSONObject().put("protocolVersion", selected)
                            .put("capabilities", capabilities())
                            .put("serverInfo", JSONObject().put("name", "hermit-device").put("version", BuildConfig.VERSION_NAME))
                            .put("instructions", serverInstructions())
                    }
                    "ping" -> JSONObject()
                    "tools/list" -> { noCursor(params); JSONObject().put("tools", catalog).put("ttlMs", 60_000).put("cacheScope", "private") }
                    "tools/call" -> callTool(authorization, params)
                    "resources/list" -> { noCursor(params); JSONObject().put("resources", JSONArray(listOf("guide", "tools", "webapp-guide", "page-api").map { JSONObject().put("uri", "hermit://$it").put("name", it).put("mimeType", "text/plain") })).put("ttlMs", 60_000).put("cacheScope", "private") }
                    "resources/read" -> { val uri = params.optString("uri"); JSONObject().put("contents", JSONArray().put(JSONObject().put("uri", uri).put("mimeType", "text/plain").put("text", resource(uri)))).put("ttlMs", 60_000).put("cacheScope", "private") }
                    "resources/templates/list" -> JSONObject().put("resourceTemplates", JSONArray()).put("ttlMs", 60_000).put("cacheScope", "private")
                    "prompts/list" -> { noCursor(params); JSONObject().put("prompts", JSONArray().put(JSONObject().put("name", "develop-webapp").put("description", "Current Hermit native page development workflow"))).put("ttlMs", 60_000).put("cacheScope", "private") }
                    "prompts/get" -> { if (params.optString("name") != "develop-webapp") throw RpcError(-32602, "Unknown prompt"); JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONObject().put("type", "text").put("text", guide())))) }
                    else -> throw RpcError(-32601, "Method not found")
                }
                if (modern) {
                    result.put("resultType", "complete")
                    result.put("_meta", JSONObject().put("io.modelcontextprotocol/serverInfo",
                        JSONObject().put("name", "hermit-device").put("version", BuildConfig.VERSION_NAME)))
                }
                return json(200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
            } catch (error: RpcError) { return json(200, rpcError(id, error.code, error.message)) }
            catch (error: Exception) { return json(200, rpcError(id, -32603, "Request failed")) }
        }

        private fun callTool(authorization: String, params: JSONObject): JSONObject {
            val name = params.optString("name")
            val definition = (0 until catalog.length()).map { catalog.getJSONObject(it) }
                .find { it.getString("name") == name } ?: throw RpcError(-32602, "Unknown tool")
            val args = if (!params.has("arguments")) JSONObject() else params.optJSONObject("arguments")
                ?: throw RpcError(-32602, "arguments must be an object")
            validate(args, definition.getJSONObject("inputSchema"), "arguments")
            val readOnly = definition.getJSONObject("annotations").getBoolean("readOnlyHint")
            val appId = args.optString("appId").takeIf { it.isNotBlank() }
            val writeGate = if (readOnly) null else writes.computeIfAbsent(appId ?: "__device__") { Semaphore(1) }
            var acquired = false
            try {
                live(authorization)
                if (appId != null) {
                    if (name in DEV_WORKSPACE_TOOLS) devWorkspaces.requireDevelopableApp(appId) else requireApp(appId)
                }
                if (writeGate != null) {
                    acquired = writeGate.tryAcquire()
                    if (!acquired) throw HermitException(ErrorCodes.CONFLICT, "This happ already has a write in progress")
                }
                live(authorization)
                val requestId = args.optString("requestId").takeIf { it.isNotBlank() }
                val fingerprint = AgentWorkspace.sha((name + canonical(args)).toByteArray())
                if (requestId != null) synchronized(receipts) {
                    receipts[requestId]?.let { receipt ->
                        if (receipt.first != fingerprint) throw HermitException(ErrorCodes.CONFLICT, "requestId was already used for different input")
                        return toolResult(receipt.second)
                    }
                }
                val result = when (name) {
                    "hermit_get_guide" -> JSONObject().put("serverVersion", BuildConfig.VERSION_NAME)
                        .put("runId", runId).put("guidanceVersion", guidanceVersion).put("text", guide())
                    "hermit_runtime_status" -> ui("status", JSONObject(), authorization)
                        .put("serverVersion", BuildConfig.VERSION_NAME).put("runId", runId)
                    "hermit_list_apps" -> JSONObject().put("apps", JSONArray(registry.listInstances().map { app ->
                        app.toJson().put("devWorkspace", devWorkspaces.status(app.appId))
                    }))
                    "hermit_get_app" -> registry.getInstance(appId!!)!!.toJson()
                        .put("devWorkspace", devWorkspaces.status(appId))
                    "hermit_create_dev_app" -> workspace.createDev(
                        args.getString("name"), args.getString("happId"), devWorkspaces
                    ) { guarded(authorization, it) }
                    "hermit_enter_dev_mode" -> guardedValue(authorization) { devWorkspaces.enter(appId!!) }.also {
                        it.put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization))
                    }
                    "hermit_leave_dev_mode" -> guardedValue(authorization) { devWorkspaces.leave(appId!!) }.also {
                        it.put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization))
                    }
                    "hermit_get_dev_status" -> devWorkspaces.status(appId!!)
                    "hermit_list_dev_files" -> devWorkspaces.list(appId!!)
                    "hermit_read_dev_file" -> devWorkspaces.read(appId!!, args.getString("path"))
                    "hermit_apply_dev_files", "hermit_sync_dev_changes" -> {
                        val started = SystemClock.elapsedRealtime()
                        val changed = guardedValue(authorization) {
                            devWorkspaces.apply(appId!!, args.getLong("expectedDevRevision"), args.getJSONArray("files"))
                        }
                        changed.put("timing", JSONObject().put("nativeCommitMs", SystemClock.elapsedRealtime() - started))
                        refreshDev(changed, args, appId!!, authorization)
                    }
                    "hermit_put_dev_file" -> JSONObject().put("method", "PUT")
                        .put("url", "$address/v2/apps/$appId/dev/file?path=" + java.net.URLEncoder.encode(args.getString("path"), "UTF-8"))
                        .put("headers", JSONObject()
                            .put("Content-Type", args.optString("contentType", "application/octet-stream"))
                            .put("Content-Length", args.getLong("bytes"))
                            .put("X-Hermit-Expected-Dev-Revision", args.getLong("expectedDevRevision"))
                            .put("Idempotency-Key", args.getString("requestId"))
                            .put("X-Hermit-Content-SHA256", args.getString("sha256")))
                    "hermit_replace_dev_tree" -> JSONObject().put("method", "PUT")
                        .put("url", "$address/v2/apps/$appId/dev/tree")
                        .put("headers", JSONObject().put("Content-Type", "application/zip")
                            .put("Content-Length", args.getLong("bytes"))
                            .put("X-Hermit-Expected-Dev-Revision", args.getLong("expectedDevRevision"))
                            .put("Idempotency-Key", args.getString("requestId"))
                            .put("X-Hermit-Content-SHA256", args.getString("sha256")))
                    "hermit_open_app" -> {
                        val app = registry.getInstance(appId!!)!!
                        val operation = if (app.launchChannel == io.github.zhyuzh3d.hermit.model.LaunchChannel.DEV) {
                            registry.getDevWorkspace(appId)?.let { createRenderOperation(appId, it.revision) }
                        } else null
                        val ui = ui("open", JSONObject().put("appId", appId)
                            .put("route", args.optString("route").takeIf { it.isNotBlank() } ?: JSONObject.NULL), authorization)
                        if (ui.optString("state") == "not-visible") operation?.let(::discardRenderOperation)
                        ui.put("renderOperationId", operation?.id ?: JSONObject.NULL)
                    }
                    "hermit_reload_app" -> {
                        val app = registry.getInstance(appId!!)!!
                        val workspace = if (app.launchChannel == io.github.zhyuzh3d.hermit.model.LaunchChannel.DEV) registry.getDevWorkspace(appId) else null
                        val operation = workspace?.let { createRenderOperation(appId, it.revision) }
                        val ui = ui("reload", JSONObject().put("appId", appId)
                            .put("revision", workspace?.revision ?: JSONObject.NULL), authorization)
                        if (ui.optString("state") == "not-visible") operation?.let(::discardRenderOperation)
                        ui.put("renderOperationId", operation?.id ?: JSONObject.NULL)
                    }
                    "hermit_wait_dev_render" -> waitForRender(args.getString("operationId"), args.optLong("timeoutMs", 5000))
                    "hermit_get_dev_diagnostics" -> diagnostics(appId!!)
                    "hermit_build_dev_package" -> guardedValue(authorization) {
                        devWorkspaces.build(appId!!, args.getLong("expectedDevRevision"),
                            args.getLong("versionCode"), args.getString("versionName"))
                    }.also {
                        it.put("downloadUrl", "$address/v2/builds/" + it.getString("buildId"))
                    }
                    "hermit_install_dev_package" -> {
                        val artifact = devWorkspaces.artifact(args.getString("buildId"))
                            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "构建产物已过期或不存在")
                        if (artifact.appId != appId || artifact.revision != args.getLong("expectedDevRevision")) {
                            throw HermitException(ErrorCodes.CONFLICT, "构建产物不属于当前开发 revision")
                        }
                        val stable = registry.getInstance(appId!!)!!.activeReleaseId
                        if (stable != args.getString("expectedStableReleaseId")) {
                            throw HermitException(ErrorCodes.CONFLICT, "正式版本已经变化")
                        }
                        val installed = runBlocking(Dispatchers.IO) {
                            artifact.file.inputStream().use { input ->
                                installer.installZip(input, null, appId, "agent-dev-publish",
                                    "agent-dev:" + args.getString("requestId"), stable, artifact.sha256,
                                    commitGuard = { guarded(authorization, it) })
                            }
                        }
                        devWorkspaces.installed(appId, artifact.revision, installed.releaseId)
                            .put("releaseId", installed.releaseId).put("operationId", installed.operationId)
                            .put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization))
                    }
                    "hermit_reset_dev_workspace" -> guardedValue(authorization) { devWorkspaces.reset(appId!!) }.also {
                        it.put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization))
                    }

                    // One compatibility cycle: these methods still publish immutable releases directly.
                    "hermit_create_app" -> workspace.create(args.getString("name")) { guarded(authorization, it) }
                    "hermit_list_files" -> workspace.files(appId!!)
                    "hermit_read_file" -> workspace.read(appId!!, args.getString("path"))
                    "hermit_apply_files" -> workspace.apply(appId!!, args) { guarded(authorization, it) }
                        .also { if (args.optBoolean("reload", true)) it.put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization)) }
                    "hermit_list_releases" -> JSONObject().put("activeReleaseId", workspace.local(appId!!).activeReleaseId)
                        .put("releases", JSONArray(registry.listReleases(appId).map {
                            JSONObject().put("releaseId", it.releaseId).put("treeHash", it.treeHash)
                                .put("createdAt", it.createdAt).put("provenance", it.provenance)
                        }))
                    "hermit_rollback" -> {
                        workspace.local(appId!!)
                        guarded(authorization) { registry.activateRelease(appId, args.getString("releaseId"), args.getString("expectedReleaseId")) }
                        JSONObject().put("appId", appId).put("releaseId", args.getString("releaseId"))
                            .put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization))
                    }
                    else -> throw RpcError(-32602, "Unknown tool")
                }
                if (requestId != null) synchronized(receipts) {
                    if (receipts.size >= 128) receipts.remove(receipts.keys.first())
                    receipts[requestId] = fingerprint to result
                }
                record(name, appId, "ok")
                return toolResult(result)
            } catch (error: Exception) {
                val code = (error as? HermitException)?.code ?: "E_OPERATION_FAILED"
                record(name, appId, code)
                return toolResult(JSONObject().put("code", code).put("message", error.message?.take(400) ?: "Operation failed"), true)
            } finally {
                if (acquired) writeGate?.release()
            }
        }

        private fun refreshDev(result: JSONObject, args: JSONObject, appId: String, authorization: String): JSONObject {
            val refreshMode = args.optString("refreshMode", if (args.optBoolean("reload", true)) "auto" else "none")
            if (refreshMode == "none") return result.put("refreshState", "skipped").put("renderOperationId", JSONObject.NULL)
            val revision = result.getLong("revision")
            val operation = createRenderOperation(appId, revision)
            val payload = JSONObject().put("appId", appId).put("revision", revision).put("refreshMode", refreshMode)
                .put("changedPaths", result.optJSONArray("changedPaths") ?: JSONArray())
                .put("route", args.optString("route").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            val runtime = safeUi("refresh", payload, authorization)
            if (runtime.optString("state") == "not-visible") {
                discardRenderOperation(operation)
                return result.put("refreshState", "not-visible").put("renderOperationId", JSONObject.NULL)
            }
            return result.put("refreshState", runtime.optString("state", "scheduled"))
                .put("runtime", runtime).put("renderOperationId", operation.id)
        }

        private fun createRenderOperation(appId: String, revision: Long): RenderOperation {
            val operation = RenderOperation(UUID.randomUUID().toString(), appId, revision, System.currentTimeMillis())
            renderOperations[operation.id] = operation
            renderOperations.entries.removeIf { it.value.createdAt < System.currentTimeMillis() - RENDER_TTL_MS }
            return operation
        }

        private fun discardRenderOperation(operation: RenderOperation) {
            renderOperations.remove(operation.id)
            operation.result.cancel()
        }

        private fun waitForRender(operationId: String, requestedTimeout: Long): JSONObject {
            val operation = renderOperations[operationId]
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "渲染操作不存在或已过期")
            val timeout = requestedTimeout.coerceIn(0, 10_000)
            val completed = runBlocking {
                if (timeout == 0L) {
                    if (operation.result.isCompleted) runCatching { operation.result.await() }.getOrNull() else null
                } else withTimeoutOrNull(timeout) { operation.result.await() }
            }
            return completed ?: JSONObject().put("state", "render-timeout").put("operationId", operation.id)
                .put("appId", operation.appId).put("revision", operation.revision)
                .put("diagnostics", diagnostics(operation.appId).getJSONArray("events"))
        }

        private fun diagnostics(appId: String): JSONObject {
            val events = devDiagnostics[appId]
            val values = if (events == null) emptyList() else synchronized(events) { events.toList() }
            return JSONObject().put("appId", appId)
                .put("revision", registry.getDevWorkspace(appId)?.revision ?: JSONObject.NULL)
                .put("events", JSONArray(values))
        }

        private fun ui(action: String, args: JSONObject, authorization: String): JSONObject = runBlocking {
            withContext(Dispatchers.Main) {
                synchronized(this@AgentDevelopmentServer) {
                    live(authorization)
                    uiHandler?.invoke(action, args) ?: throw IllegalStateException("Hermit has no foreground activity")
                }
            }
        }

        private fun <T> guardedValue(authorization: String, action: () -> T): T =
            synchronized(this@AgentDevelopmentServer) { live(authorization); action() }

        private fun safeUi(action: String, args: JSONObject, authorization: String) =
            try { ui(action, args, authorization) } catch (_: Exception) {
                JSONObject().put("state", "not-visible").put("appId", args.optString("appId").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            }

        private fun downloadBuild(authorization: String, session: IHTTPSession): Response {
            live(authorization)
            val buildId = session.uri.substringAfterLast('/')
            val artifact = devWorkspaces.artifact(buildId) ?: return httpError(404, "Build artifact expired or not found")
            return newFixedLengthResponse(Response.Status.OK, "application/zip", FileInputStream(artifact.file), artifact.bytes).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Hermit-Content-SHA256", artifact.sha256)
                addHeader("Content-Disposition", "attachment; filename=\"happ-$buildId.zip\"")
                addHeader("Connection", "close")
            }
        }

        private fun uploadDevFile(authorization: String, session: IHTTPSession): Response {
            val appId = session.uri.split('/')[3]; requireApp(appId)
            val gate = writes.computeIfAbsent(appId) { Semaphore(1) }
            if (!gate.tryAcquire()) return httpError(409, "This happ already has a write in progress")
            try {
                live(authorization)
                val path = session.parameters["path"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: return httpError(400, "path query parameter required")
                val expected = session.headers["x-hermit-expected-dev-revision"]?.toLongOrNull()
                    ?: return httpError(400, "X-Hermit-Expected-Dev-Revision required")
                val key = session.headers["idempotency-key"]?.takeIf { it.length in 8..128 }
                    ?: return httpError(400, "Idempotency-Key required")
                val hash = session.headers["x-hermit-content-sha256"]?.lowercase()?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                    ?: return httpError(400, "SHA256 required")
                val length = contentLength(session, MAX_ZIP)
                val fingerprint = AgentWorkspace.sha("dev-file|$appId|$path|$expected|$length|$hash".toByteArray())
                synchronized(receipts) { receipts["http:$key"]?.let { receipt ->
                    if (receipt.first != fingerprint) return httpError(409, "Idempotency-Key was used for different input")
                    return json(200, receipt.second)
                } }
                lateinit var result: JSONObject
                guarded(authorization) {
                    result = devWorkspaces.put(appId, expected, path, FixedLengthInputStream(session.inputStream, length), length, hash)
                }
                result = refreshDev(result, JSONObject().put("refreshMode", session.headers["x-hermit-refresh-mode"] ?: "auto"), appId, authorization)
                synchronized(receipts) { receipts["http:$key"] = fingerprint to result }
                record("put_dev_file", appId, "ok")
                return json(200, result)
            } catch (error: HermitException) {
                return httpError(if (error.code == ErrorCodes.CONFLICT) 409 else 400, error.message ?: "Upload failed")
            } finally { gate.release() }
        }

        private fun uploadDevTree(authorization: String, session: IHTTPSession): Response {
            val appId = session.uri.split('/')[3]; requireApp(appId)
            val gate = writes.computeIfAbsent(appId) { Semaphore(1) }
            if (!gate.tryAcquire()) return httpError(409, "This happ already has a write in progress")
            val incoming = File.createTempFile("dev-tree-", ".zip", context.cacheDir)
            try {
                live(authorization)
                if (session.headers["content-type"]?.substringBefore(';') != "application/zip") return httpError(415, "application/zip required")
                val expected = session.headers["x-hermit-expected-dev-revision"]?.toLongOrNull()
                    ?: return httpError(400, "X-Hermit-Expected-Dev-Revision required")
                val key = session.headers["idempotency-key"]?.takeIf { it.length in 8..128 }
                    ?: return httpError(400, "Idempotency-Key required")
                val hash = session.headers["x-hermit-content-sha256"]?.lowercase()?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                    ?: return httpError(400, "SHA256 required")
                val length = contentLength(session, MAX_ZIP)
                val fingerprint = AgentWorkspace.sha("dev-tree|$appId|$expected|$length|$hash".toByteArray())
                synchronized(receipts) { receipts["http:$key"]?.let { receipt ->
                    if (receipt.first != fingerprint) return httpError(409, "Idempotency-Key was used for different input")
                    return json(200, receipt.second)
                } }
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(incoming).use { output ->
                    val input = FixedLengthInputStream(session.inputStream, length); val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) { val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read); digest.update(buffer, 0, read) }
                    output.fd.sync()
                }
                if (!digest.digest().joinToString("") { "%02x".format(it) }.equals(hash, true)) return httpError(400, "ZIP digest mismatch")
                lateinit var result: JSONObject
                guarded(authorization) { incoming.inputStream().use { result = devWorkspaces.replace(appId, expected, it) } }
                result = refreshDev(result, JSONObject().put("refreshMode", session.headers["x-hermit-refresh-mode"] ?: "auto"), appId, authorization)
                synchronized(receipts) { receipts["http:$key"] = fingerprint to result }
                record("replace_dev_tree", appId, "ok")
                return json(200, result)
            } catch (error: HermitException) {
                return httpError(if (error.code == ErrorCodes.CONFLICT) 409 else 400, error.message ?: "Upload failed")
            } finally { incoming.delete(); gate.release() }
        }

        private fun upload(authorization: String, session: IHTTPSession): Response {
            val appId = session.uri.split('/')[3]; requireApp(appId)
            val gate = writes.computeIfAbsent(appId) { Semaphore(1) }
            if (!gate.tryAcquire()) return httpError(409, "This happ already has a write in progress")
            try {
                live(authorization); workspace.local(appId)
                if (session.headers["content-type"]?.substringBefore(';') != "application/zip") return httpError(415, "application/zip required")
                val length = contentLength(session, MAX_ZIP)
                val expected = session.headers["x-hermit-expected-release"]?.takeIf { it.isNotBlank() } ?: return httpError(400, "X-Hermit-Expected-Release required")
                val key = session.headers["idempotency-key"]?.takeIf { it.length in 8..128 } ?: return httpError(400, "Idempotency-Key required")
                val hash = session.headers["x-hermit-content-sha256"]?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) } ?: return httpError(400, "SHA256 required")
                val result = runBlocking(Dispatchers.IO) {
                    installer.installZip(FixedLengthInputStream(session.inputStream, length), null, appId, "agent", "agent:" + key, expected, hash, commitGuard = { guarded(authorization, it) })
                }
                record("publish_zip", appId, "ok")
                return json(200, JSONObject().put("appId", appId).put("releaseId", result.releaseId).put("operationId", result.operationId)
                    .put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization)))
            } catch (error: HermitException) { return httpError(if (error.code == ErrorCodes.CONFLICT) 409 else 400, error.message ?: "Upload failed") }
            finally { gate.release() }
        }

        private fun record(tool: String, appId: String?, result: String) = synchronized(events) {
            if (events.size >= 20) events.removeFirst()
            events.addLast(JSONObject().put("tool", tool).put("appId", appId ?: JSONObject.NULL).put("result", result).put("time", System.currentTimeMillis()))
        }
    }

    companion object {
        private val DEV_WORKSPACE_TOOLS = setOf(
            "hermit_enter_dev_mode", "hermit_leave_dev_mode", "hermit_get_dev_status",
            "hermit_list_dev_files", "hermit_read_dev_file", "hermit_apply_dev_files",
            "hermit_sync_dev_changes", "hermit_put_dev_file", "hermit_replace_dev_tree",
            "hermit_get_dev_diagnostics", "hermit_build_dev_package",
            "hermit_install_dev_package", "hermit_reset_dev_workspace",
        )
        private const val MODERN_PROTOCOL = "2026-07-28"
        private val LEGACY_PROTOCOLS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        private val PROTOCOLS = listOf(MODERN_PROTOCOL) + LEGACY_PROTOCOLS
        private const val IDLE_MS = 30L * 60 * 1000
        private const val MAX_RPC = 4L * 1024 * 1024
        private const val MAX_ZIP = 64L * 1024 * 1024
        private const val RENDER_TTL_MS = 2L * 60 * 1000
        private fun constantEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
        private fun noCursor(params: JSONObject) { if (params.has("cursor")) throw RpcError(-32602, "No pagination cursor is available") }
        private fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
        private fun validate(value: Any, schema: JSONObject, path: String) {
            fun bad(): Nothing = throw RpcError(-32602, "Invalid $path")
            when (schema.optString("type")) {
                "object" -> {
                    if (value !is JSONObject) bad()
                    val fields = schema.optJSONObject("properties") ?: JSONObject()
                    val required = schema.optJSONArray("required") ?: JSONArray()
                    for (i in 0 until required.length()) if (!value.has(required.getString(i))) bad()
                    value.keys().forEach { key -> if (!fields.has(key)) bad(); validate(value.get(key), fields.getJSONObject(key), "$path.$key") }
                }
                "array" -> { if (value !is JSONArray || value.length() !in schema.optInt("minItems", 0)..schema.optInt("maxItems", 1024)) bad(); for (i in 0 until value.length()) validate(value.get(i), schema.getJSONObject("items"), "$path[$i]") }
                "string" -> if (value !is String || value.length !in schema.optInt("minLength", 0)..schema.optInt("maxLength", 1_000_000)) bad()
                "boolean" -> if (value !is Boolean) bad()
                "integer" -> {
                    if (value !is Number) bad()
                    val number = value.toLong()
                    if (number < schema.optLong("minimum", Long.MIN_VALUE) || number > schema.optLong("maximum", Long.MAX_VALUE)) bad()
                }
            }
        }
        private fun contentLength(session: NanoHTTPD.IHTTPSession, max: Long): Long {
            if (session.headers["transfer-encoding"] != null) throw RpcError(-32600, "Chunked request bodies are not supported; send Content-Length")
            return session.headers["content-length"]?.toLongOrNull()?.takeIf { it in 1..max } ?: throw RpcError(-32600, "Missing or excessive Content-Length")
        }
        private fun body(session: NanoHTTPD.IHTTPSession, max: Long): JSONObject {
            if (session.headers["content-type"]?.substringBefore(';')?.trim() != "application/json") throw RpcError(-32600, "application/json required")
            val length = contentLength(session, max)
            val bytes = ByteArray(length.toInt()); var offset = 0
            while (offset < bytes.size) { val n = session.inputStream.read(bytes, offset, bytes.size - offset); if (n <= 0) throw RpcError(-32700, "Truncated JSON"); offset += n }
            try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                val source = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                var depth = 0; var quoted = false; var escaped = false
                for (c in source) { if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false } else { if (c == '"') quoted = true else if (c == '{' || c == '[') { depth++; if (depth > 24) throw RpcError(-32700, "JSON too deep") } else if (c == '}' || c == ']') depth-- } }
                val tokener = JSONTokener(source); val value = tokener.nextValue()
                if (value !is JSONObject || tokener.nextClean() != '\u0000') throw RpcError(-32600, "One JSON object is required")
                return value
            } catch (e: RpcError) { throw e } catch (_: Exception) { throw RpcError(-32700, "Invalid JSON") }
        }
        private fun rpcError(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))
        private fun toolResult(value: JSONObject, error: Boolean = false) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value.toString()))).put("structuredContent", value).put("isError", error)
        private fun text(code: Int, mime: String, body: String): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.lookup(code), "$mime; charset=utf-8", body).apply {
            // Early authentication/Origin failures leave the body unread. Never reuse that socket.
            addHeader("Connection", "close")
            addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff"); addHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        }
        private fun json(code: Int, value: JSONObject) = text(code, "application/json", value.toString())
        private fun httpError(code: Int, message: String) = json(code, JSONObject().put("error", message))
    }
}
