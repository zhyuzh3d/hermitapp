package io.github.zhyuzh3d.hermit.deploy

import io.github.zhyuzh3d.hermit.install.InstallCoordinator
import io.github.zhyuzh3d.hermit.model.HermitException
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.registry.AppRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

/** Reuses the release transaction; never writes into an installed version directory. */
class AgentWorkspace(private val cache: File, private val registry: AppRegistry, private val installer: InstallCoordinator) {
    fun local(appId: String) = registry.getInstance(appId)?.also {
        if (it.activeReleaseId == null) fail("This action requires locally installed code")
    } ?: fail("App not found")

    fun files(appId: String): JSONObject = withRelease(appId) { root, release ->
        JSONObject().put("appId", appId).put("releaseId", release).put("files", JSONArray(codeFiles(root).map {
            JSONObject().put("path", it.relativeTo(root).invariantSeparatorsPath).put("bytes", it.length())
        }))
    }

    fun read(appId: String, path: String): JSONObject = withRelease(appId) { root, release ->
        safePath(path)
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.canonicalPath + File.separator) || !file.isFile) fail("Code file not found")
        if (file.length() > 256 * 1024) fail("Use your source workspace for files larger than 256 KiB")
        val bytes = file.readBytes()
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val text = try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { fail("File is not UTF-8 text") }
        JSONObject().put("appId", appId).put("releaseId", release).put("path", path).put("content", text).put("sha256", sha(bytes))
    }

    fun create(name: String, guard: (() -> Unit) -> Unit = { it() }): JSONObject {
        val archive = File.createTempFile("agent-create-", ".zip", cache)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("index.html").apply { time = 0 })
                zip.write("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>新页面</title></head><body><h1>开始编写你的页面</h1></body></html>".toByteArray())
                zip.closeEntry()
            }
            val result = runBlocking(Dispatchers.IO) { archive.inputStream().use { installer.installZip(it, name, provenance = "agent", commitGuard = guard) } }
            return registry.getInstance(result.appId)!!.toJson()
        } finally { archive.delete() }
    }

    fun createDev(name: String, happId: String, dev: DevWorkspaceManager,
        guard: (() -> Unit) -> Unit = { it() }): JSONObject {
        val normalizedName = name.trim().takeIf { it.isNotBlank() }?.take(80) ?: fail("App name required")
        if (!happId.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")) || happId.length > 160) fail("Invalid happId")
        val archive = File.createTempFile("agent-create-dev-", ".zip", cache)
        try {
            val files = linkedMapOf(
                "hermit.json" to JSONObject().put("schema", 2).put("happId", happId).put("name", normalizedName)
                    .put("version", JSONObject().put("code", 1).put("name", "0.1.0"))
                    .put("entry", "index.html").put("routing", "hash").toString(2),
                "index.html" to "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${escapeHtml(normalizedName)}</title><link rel=\"stylesheet\" href=\"style.css\"></head><body><main><h1>${escapeHtml(normalizedName)}</h1><p>开始编写你的 HAPP 应用。</p></main><script src=\"app.js\"></script></body></html>",
                "style.css" to ":root{color-scheme:light dark;font-family:system-ui,sans-serif}body{margin:0;padding:32px;background:#08090d;color:#f7f8ff}main{max-width:720px;margin:auto}",
                "app.js" to "window.hermit?.call('app.ready', {}).catch(() => {});\n",
            )
            ZipOutputStream(archive.outputStream()).use { zip -> files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0 }); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            } }
            val result = runBlocking(Dispatchers.IO) {
                archive.inputStream().use { installer.installZip(it, normalizedName, provenance = "agent-seed", commitGuard = guard) }
            }
            val workspace = dev.enter(result.appId)
            return registry.getInstance(result.appId)!!.toJson().put("devWorkspace", workspace)
        } finally { archive.delete() }
    }

    fun apply(appId: String, params: JSONObject, guard: (() -> Unit) -> Unit = { it() }): JSONObject = withRelease(appId) { root, release ->
        if (release != params.getString("expectedReleaseId")) throw HermitException(ErrorCodes.CONFLICT, "Code changed; read the active release and merge before retrying")
        val changes = params.getJSONArray("files")
        val edits = linkedMapOf<String, JSONObject>()
        for (i in 0 until changes.length()) {
            val edit = changes.getJSONObject(i); val path = edit.getString("path"); safePath(path)
            if (edits.put(path, edit) != null) fail("Duplicate file path")
            if (edit.optBoolean("delete")) { if (edit.has("content")) fail("A deleted file cannot also have content") }
            else if (!edit.has("content")) fail("File content required unless delete=true")
        }
        val existing = codeFiles(root).associateBy { it.relativeTo(root).invariantSeparatorsPath }
        for ((path, edit) in edits) if (edit.optBoolean("delete") && path !in existing) fail("Cannot delete a missing code file")
        val paths = (existing.keys + edits.keys).filter { edits[it]?.optBoolean("delete") != true }.sorted()
        if (paths.size > 2048) fail("Too many files")
        var total = 0L
        val archive = File.createTempFile("agent-patch-", ".zip", cache)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (path in paths) {
                    zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                    val edit = edits[path]
                    if (edit != null) {
                        val bytes = edit.getString("content").toByteArray(); total += bytes.size
                        if (total > MAX_TREE) fail("Code snapshot exceeds 128 MiB")
                        zip.write(bytes)
                    } else {
                        val file = existing.getValue(path); total += file.length()
                        if (total > MAX_TREE) fail("Code snapshot exceeds 128 MiB")
                        file.inputStream().use { it.copyTo(zip) }
                    }
                    zip.closeEntry()
                }
            }
            val result = runBlocking(Dispatchers.IO) { archive.inputStream().use {
                installer.installZip(it, null, appId, "agent", params.getString("requestId"), release, hashFile(archive), commitGuard = guard)
            } }
            JSONObject().put("appId", appId).put("releaseId", result.releaseId).put("treeHash", result.treeHash).put("operationId", result.operationId)
        } finally { archive.delete() }
    }

    private fun <T> withRelease(appId: String, block: (File, String) -> T): T {
        val id = local(appId).activeReleaseId ?: fail("No local release")
        installer.acquireRelease(id)
        try {
            val release = registry.getRelease(id) ?: fail("Release unavailable")
            return block(installer.releaseWebRoot(release), id)
        } finally { installer.releaseRelease(id) }
    }

    private fun codeFiles(root: File): List<File> = root.walkTopDown().filter { it.isFile }.map {
        if (!it.canonicalPath.startsWith(root.canonicalPath + File.separator)) fail("Invalid code path")
        it
    }.take(2049).toList().also { if (it.size > 2048) fail("Too many files") }

    companion object {
        private const val MAX_TREE = 128L * 1024 * 1024
        fun safePath(path: String) {
            if (path.isBlank() || path.length > 240 || path.contains('\\') || path.contains('\u0000') || path.startsWith('/') ||
                path.split('/').any { it.isBlank() || it == "." || it == ".." || it == "__hermit" } || path.contains(':')) fail("Invalid relative code path")
        }
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun hashFile(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun fail(message: String): Nothing = throw HermitException(ErrorCodes.INVALID_ARGUMENT, message)
        private fun escapeHtml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
