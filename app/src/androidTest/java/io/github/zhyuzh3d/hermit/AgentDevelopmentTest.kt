package io.github.zhyuzh3d.hermit

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zhyuzh3d.hermit.deploy.AgentDevelopmentServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AgentDevelopmentTest {
    private lateinit var app: HermitApplication
    private lateinit var server: AgentDevelopmentServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var base: String
    private lateinit var password: String
    private var before = emptySet<String>()

    @Before fun start() {
        app = ApplicationProvider.getApplicationContext()
        before = app.registry.listInstances().map { it.appId }.toSet()
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        server = app.agentServer
        base = server.start("127.0.0.1", 0).getString("address")
        password = server.passwordForUi()
    }

    @After fun finish() {
        server.stop("Test complete")
        scenario.close()
        app.registry.listInstances().filter { it.appId !in before }.forEach {
            app.registry.deleteInstance(it.appId); app.installer.deleteAppFiles(it.appId); app.registry.finishDelete(it.appId)
        }
    }

    private fun request(path: String, method: String = "GET", body: ByteArray? = null, credential: String? = password, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 5000; connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("MCP-Protocol-Version", "2025-11-25")
            if (credential != null) connection.setRequestProperty("Authorization", "Bearer $credential")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) { connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
            val code = connection.responseCode
            val result = (if (code >= 400) connection.errorStream else connection.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to result
        } finally { connection.disconnect() }
    }

    private fun rpc(method: String, args: JSONObject = JSONObject(), credential: String = password): JSONObject {
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", args)
        val response = request("/mcp", "POST", payload.toString().toByteArray(), credential)
        assertEquals(200, response.first)
        val result = JSONObject(response.second)
        assertFalse(result.toString(), result.has("error"))
        return result.getJSONObject("result")
    }

    private fun tool(name: String, args: JSONObject = JSONObject(), failure: Boolean = false): JSONObject {
        val result = rpc("tools/call", JSONObject().put("name", name).put("arguments", args))
        assertEquals(result.toString(), failure, result.getBoolean("isError"))
        return result.getJSONObject("structuredContent")
    }

    private fun create(): JSONObject = tool("hermit_create_app", JSONObject().put("name", "Agent test").put("requestId", UUID.randomUUID().toString()))

    @Test fun sharedPasswordPersistsAndReplacesWithoutPairingOrClientIdentity() {
        assertTrue(password.matches(Regex("[0-9]{6}")))
        val ping = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping").toString().toByteArray()
        assertEquals(401, request("/mcp", "POST", ping, null).first)
        assertEquals(200, request("/mcp", "POST", ping).first)
        // Independent HTTP connections need no initialize identity, pairing, session ID or peer registration.
        assertEquals(200, request("/mcp", "POST", ping, password, mapOf("User-Agent" to "second-computer")).first)
        val old = password
        server.resetPassword(); password = server.passwordForUi()
        assertTrue(old != password)
        assertEquals(401, request("/mcp", "POST", ping, old).first)
        assertEquals(200, request("/mcp", "POST", ping).first)
        server.stop("Test restart"); base = server.start("127.0.0.1", 0).getString("address")
        assertTrue(password == server.passwordForUi())
        val recreated = AgentDevelopmentServer(app, app.registry, app.installer, app.applicationScope)
        assertTrue(password == recreated.passwordForUi())
        assertEquals(200, request("/mcp", "POST", ping).first)
        assertEquals(404, request("/pair", "POST", "{}".toByteArray()).first)
    }

    @Test fun protocolDiscoveryOriginAndBruteForceLimits() {
        val discovery = JSONObject(request("/.well-known/hermit-agent", credential = null).second)
        assertFalse(discovery.toString().contains(password)); assertFalse(discovery.has("pairUrl"))
        assertEquals(12, discovery.getJSONArray("tools").length())
        assertEquals(200, request("/skills/hermit-device/SKILL.md", credential = null).first)
        assertEquals(200, request("/hermit-agent.py", credential = null).first)
        val initialized = rpc("initialize", JSONObject().put("protocolVersion", "2025-11-25").put("clientInfo", JSONObject().put("name", "test").put("version", "1")).put("capabilities", JSONObject()))
        assertEquals("2025-11-25", initialized.getString("protocolVersion"))
        assertTrue(initialized.getString("instructions").contains("password"))
        assertEquals(12, rpc("tools/list").getJSONArray("tools").length())
        assertTrue(rpc("resources/read", JSONObject().put("uri", "hermit://webapp-guide")).getJSONArray("contents").getJSONObject(0).getString("text").contains("HTML"))
        val notice = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}".toByteArray()
        assertEquals(202, request("/mcp", "POST", notice).first)
        assertEquals(405, request("/mcp").first)
        assertEquals(403, request("/mcp", "POST", notice, headers = mapOf("Origin" to "https://untrusted.invalid")).first)
        assertEquals(400, request("/mcp", "POST", notice, headers = mapOf("MCP-Protocol-Version" to "invalid")).first)
        repeat(5) { assertEquals(401, request("/mcp", "POST", notice, "invalid").first) }
        assertEquals(429, request("/mcp", "POST", notice, "invalid").first)
    }

    @Test fun createPatchRetryConflictReadOpenReloadRollbackAndAllAppsVisible() {
        val created = create(); val id = created.getString("appId"); val first = created.getString("activeReleaseId")
        val secondApp = create()
        assertTrue(tool("hermit_list_apps").getJSONArray("apps").toString().contains(secondApp.getString("appId")))
        val args = JSONObject().put("appId", id).put("expectedReleaseId", first).put("requestId", UUID.randomUUID().toString()).put("reload", false)
            .put("files", JSONArray().put(JSONObject().put("path", "index.html").put("content", "<!doctype html><html><title>MCP updated</title><body>native page</body></html>")))
        val applied = tool("hermit_apply_files", args)
        assertEquals(applied.getString("releaseId"), tool("hermit_apply_files", args).getString("releaseId"))
        val stale = JSONObject(args.toString()).put("requestId", UUID.randomUUID().toString())
        assertEquals("E_CONFLICT", tool("hermit_apply_files", stale, true).getString("code"))
        val file = tool("hermit_read_file", JSONObject().put("appId", id).put("path", "index.html"))
        assertTrue(file.getString("content").contains("native page"))
        tool("hermit_read_file", JSONObject().put("appId", id).put("path", "../secret"), true)
        assertTrue(tool("hermit_list_files", JSONObject().put("appId", id)).getJSONArray("files").length() > 0)
        val appBefore = app.registry.getInstance(id)!!
        assertEquals("opening", tool("hermit_open_app", JSONObject().put("appId", id)).getString("state"))
        assertEquals(id, tool("hermit_runtime_status").getString("appId"))
        assertEquals("reloading", tool("hermit_reload_app", JSONObject().put("appId", id)).getString("state"))
        assertEquals("not-visible", tool("hermit_reload_app", JSONObject().put("appId", secondApp.getString("appId"))).getString("state"))
        assertTrue(tool("hermit_list_releases", JSONObject().put("appId", id)).getJSONArray("releases").length() >= 2)
        tool("hermit_rollback", JSONObject().put("appId", id).put("releaseId", first).put("expectedReleaseId", applied.getString("releaseId")).put("requestId", UUID.randomUUID().toString()))
        val appAfter = app.registry.getInstance(id)!!
        assertEquals(first, appAfter.activeReleaseId)
        assertEquals(appBefore.activeDataGeneration, appAfter.activeDataGeneration)
        assertEquals(appBefore.webProfileName, appAfter.webProfileName)
    }

    @Test fun passwordRotationDuringUploadPreventsActivation() {
        val created = create(); val id = created.getString("appId"); val release = created.getString("activeReleaseId")
        val zip = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use {
            it.putNextEntry(ZipEntry("index.html")); it.write("<h1>must not commit</h1>".toByteArray()); it.closeEntry()
        } }.toByteArray()
        val sha = io.github.zhyuzh3d.hermit.deploy.AgentWorkspace.sha(zip)
        val address = URL(base)
        Socket(address.host, address.port).use { socket ->
            socket.soTimeout = 15000
            val header = "PUT /v1/apps/$id/release HTTP/1.1\r\nHost: ${address.host}:${address.port}\r\nAuthorization: Bearer $password\r\nContent-Type: application/zip\r\nContent-Length: ${zip.size}\r\nX-Hermit-Expected-Release: $release\r\nIdempotency-Key: ${UUID.randomUUID()}\r\nX-Hermit-Content-SHA256: $sha\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(header.toByteArray()); socket.getOutputStream().write(zip, 0, 1); socket.getOutputStream().flush()
            val incoming = java.io.File(app.filesDir, "instances/$id/incoming")
            var receiving = false
            for (i in 0 until 50) { if (incoming.listFiles()?.isNotEmpty() == true) { receiving = true; break }; Thread.sleep(20) }
            assertTrue("Upload must be receiving before rotation; server active=" + server.status().optBoolean("active") + "; reason=" + server.lastStopReason + "; response=" + if (receiving) "ready" else socket.getInputStream().bufferedReader().readLine(), receiving)
            server.resetPassword(); password = server.passwordForUi()
            socket.getOutputStream().write(zip, 1, zip.size - 1); socket.getOutputStream().flush()
            val firstLine = socket.getInputStream().bufferedReader().readLine()
            assertFalse(firstLine, firstLine.contains("200 OK"))
        }
        assertEquals(release, app.registry.getInstance(id)!!.activeReleaseId)
    }

    @Test fun enteringBackgroundClosesServerButPreservesPassword() {
        scenario.moveToState(Lifecycle.State.CREATED)
        for (i in 0 until 30) { if (!server.status().getBoolean("active")) break; Thread.sleep(100) }
        assertFalse(server.status().getBoolean("active"))
        assertTrue(password == server.passwordForUi())
    }

    @Test fun externalClientInterop() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("externalMcp") == "true")
        server.stop("External interop")
        base = server.start(port = 8766).getString("address")
        val connection = java.io.File(app.filesDir, "agent-interop.json")
        val done = java.io.File(app.filesDir, "agent-interop.done")
        val rotate = java.io.File(app.filesDir, "agent-interop.rotate")
        fun publish() { connection.writeText(JSONObject().put("address", base).put("password", server.passwordForUi()).toString()) }
        try {
            done.delete(); rotate.delete(); publish()
            for (i in 0 until 1200) {
                if (done.exists()) return
                assertTrue("External fixture stopped: " + server.lastStopReason, server.status().optBoolean("active"))
                if (rotate.exists()) { server.resetPassword(); publish(); rotate.delete() }
                Thread.sleep(100)
            }
            fail("External MCP client did not complete in 120 seconds")
        } finally { connection.delete(); done.delete(); rotate.delete() }
    }
}
