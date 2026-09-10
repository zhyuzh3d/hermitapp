package io.github.zhyuzh3d.hermit.install

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.zhyuzh3d.hermit.model.CodeRelease
import io.github.zhyuzh3d.hermit.model.DeliveryMode
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HermitException
import io.github.zhyuzh3d.hermit.model.WebAppInstance
import io.github.zhyuzh3d.hermit.registry.AppRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class InstallResult(
    val operationId: String,
    val appId: String,
    val releaseId: String,
    val treeHash: String,
)

class InstallCoordinator(
    private val context: Context,
    private val registry: AppRegistry,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val releaseLeases = ConcurrentHashMap<String, AtomicInteger>()
    private val appsRoot get() = File(context.filesDir, "instances")

    suspend fun installZip(
        input: InputStream,
        suggestedName: String?,
        existingAppId: String? = null,
        provenance: String = "import",
        idempotencyKey: String? = null,
        expectedReleaseId: String? = null,
        declaredSha256: String? = null,
        sourceRevision: String? = null,
    ): InstallResult = withContext(Dispatchers.IO) {
        val appId = existingAppId ?: UUID.randomUUID().toString()
        locks.computeIfAbsent(appId) { Mutex() }.withLock {
            val instance = existingAppId?.let {
                registry.getInstance(it) ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            }
            if (instance != null && instance.mode != DeliveryMode.LOCAL) {
                throw HermitException(ErrorCodes.CONFLICT, "在线应用不能直接接收本地版本")
            }
            val incomingDir = File(appsRoot, "$appId/incoming").apply { mkdirs() }
            val zipFile = File(incomingDir, "${UUID.randomUUID()}.zip")
            val digest = MessageDigest.getInstance("SHA-256")
            var transferred = 0L
            try {
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        transferred += read
                        if (transferred > MAX_ZIP_BYTES) throw HermitException(ErrorCodes.QUOTA, "ZIP 超过 64 MiB")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } catch (error: Throwable) {
                zipFile.delete()
                cleanupProvisionalRoot(appId, existingAppId)
                throw if (error is HermitException) error else HermitException(ErrorCodes.STORAGE, error.message ?: "无法接收 ZIP", true)
            }
            val zipHash = digest.digest().hex()
            if (declaredSha256 != null && !zipHash.equals(declaredSha256, ignoreCase = true)) {
                zipFile.delete()
                cleanupProvisionalRoot(appId, existingAppId)
                throw HermitException(ErrorCodes.INVALID_ARGUMENT, "ZIP 摘要不匹配")
            }

            val operationId = try {
                registry.createOperation(appId, "install-release", idempotencyKey, zipHash, expectedReleaseId)
            } catch (error: Throwable) {
                zipFile.delete()
                cleanupProvisionalRoot(appId, existingAppId)
                throw HermitException(ErrorCodes.CONFLICT, error.message ?: "幂等键已用于其他内容")
            }
            val existing = registry.operationJson(operationId)
            if (existing?.optString("state") == "succeeded") {
                zipFile.delete()
                val release = registry.getRelease(existing.getString("resultReleaseId"))
                    ?: throw HermitException(ErrorCodes.STORAGE, "幂等操作对应的版本不存在")
                return@withLock InstallResult(
                    operationId,
                    appId,
                    release.releaseId,
                    release.treeHash,
                )
            }

            val staging = File(appsRoot, "$appId/staging/$operationId")
            var finalDir: File? = null
            var registryCommitted = false
            try {
                val currentInstance = existingAppId?.let {
                    registry.getInstance(it) ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
                }
                if (expectedReleaseId != null && currentInstance?.activeReleaseId != expectedReleaseId) {
                    throw HermitException(ErrorCodes.CONFLICT, "活动版本已经变化")
                }
                registry.updateOperation(operationId, "transferring")
                if (staging.exists()) staging.deleteRecursively()
                val webRoot = File(staging, "web").apply { mkdirs() }
                registry.updateOperation(operationId, "validating")
                extractValidated(zipFile, webRoot)
                val metadata = readManifest(webRoot)
                val entry = metadata?.optString("entry", "index.html")?.ifBlank { "index.html" } ?: "index.html"
                validateRelativePath(entry)
                check(File(webRoot, entry).isFile) { "入口文件不存在：$entry" }
                val treeHash = treeHash(webRoot)
                if (instance != null) {
                    val duplicate = registry.findReleaseByTreeHash(appId, treeHash)
                    if (duplicate != null) {
                        staging.deleteRecursively()
                        if (duplicate.releaseId != currentInstance?.activeReleaseId) {
                            registry.activateRelease(appId, duplicate.releaseId, currentInstance?.activeReleaseId)
                        }
                        registry.updateOperation(operationId, "succeeded", duplicate.releaseId)
                        pruneReleases(appId, duplicate.releaseId)
                        return@withLock InstallResult(operationId, appId, duplicate.releaseId, duplicate.treeHash)
                    }
                }
                val releaseId = UUID.randomUUID().toString()
                val destination = File(appsRoot, "$appId/releases/$releaseId")
                finalDir = destination
                destination.parentFile?.mkdirs()
                check(staging.renameTo(destination)) { "无法提交代码目录" }

                val now = System.currentTimeMillis()
                val release = CodeRelease(
                    releaseId = releaseId,
                    appId = appId,
                    treeHash = treeHash,
                    provenance = provenance,
                    versionCode = metadata?.optJSONObject("version")?.optLong("code")?.takeIf { it >= 0 },
                    versionName = metadata?.optJSONObject("version")?.optString("name")?.takeIf { it.isNotBlank() },
                    sourceRevision = sourceRevision,
                    entryPath = entry,
                    relativeRoot = "instances/$appId/releases/$releaseId/web",
                    createdAt = now,
                )
                registry.updateOperation(operationId, "committing")
                if (instance == null) {
                    val name = metadata?.optString("name")?.takeIf { it.isNotBlank() } ?: suggestedName?.takeIf { it.isNotBlank() } ?: "本地应用"
                    val origin = "https://$appId.apps.hermit.invalid"
                    val app = WebAppInstance(
                        appId = appId, name = name.take(80), mode = DeliveryMode.LOCAL,
                        startUrl = "$origin/$entry", primaryOrigin = origin,
                        webProfileName = "app-${appId.replace("-", "")}", trustRevision = 1,
                        activeReleaseId = null, activeDataGeneration = UUID.randomUUID().toString(),
                        sourceAdapter = provenance, sourceSpec = JSONObject().put("kind", provenance).toString(),
                        developerEnabled = false, createdAt = now, updatedAt = now,
                    )
                    registry.insertLocalWithRelease(app, release)
                    registryCommitted = true
                } else {
                    try {
                        registry.commitRelease(release, currentInstance?.activeReleaseId)
                        registryCommitted = true
                    } catch (e: Throwable) {
                        destination.deleteRecursively()
                        throw HermitException(ErrorCodes.CONFLICT, "活动版本在提交时发生变化")
                    }
                }
                registry.updateOperation(operationId, "succeeded", releaseId)
                pruneReleases(appId, releaseId)
                InstallResult(operationId, appId, releaseId, treeHash)
            } catch (e: Throwable) {
                staging.deleteRecursively()
                if (!registryCommitted) finalDir?.deleteRecursively()
                val mapped = e as? HermitException
                    ?: HermitException(ErrorCodes.INVALID_ARGUMENT, e.message ?: "本地包校验失败")
                registry.updateOperation(operationId, "failed", errorCode = mapped.code, errorMessage = mapped.message)
                cleanupProvisionalRoot(appId, existingAppId)
                throw mapped
            } finally {
                zipFile.delete()
            }
        }
    }

    suspend fun installUri(uri: Uri, suggestedName: String?): InstallResult {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw HermitException(ErrorCodes.STORAGE, "无法读取所选文件")
        stream.use { return installZip(it, suggestedName) }
    }

    suspend fun installTree(uri: Uri, suggestedName: String?): InstallResult = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: throw HermitException(ErrorCodes.STORAGE, "无法读取所选目录")
        val temporary = File(context.cacheDir, "tree-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                var files = 0
                var expanded = 0L
                fun append(directory: DocumentFile, prefix: String, depth: Int) {
                    if (depth > MAX_TREE_DEPTH) throw HermitException(ErrorCodes.QUOTA, "目录层级超过限制")
                    directory.listFiles().sortedBy { it.name?.lowercase() ?: "" }.forEach { child ->
                        val name = child.name?.takeIf { it.isNotBlank() }
                            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "目录包含无名称项目")
                        val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                        validateRelativePath(relative)
                        if (child.isDirectory) {
                            append(child, relative, depth + 1)
                        } else if (child.isFile) {
                            files++
                            if (files > MAX_FILES) throw HermitException(ErrorCodes.QUOTA, "文件数量超过限制")
                            zip.putNextEntry(ZipEntry(relative))
                            val input = context.contentResolver.openInputStream(child.uri)
                                ?: throw HermitException(ErrorCodes.STORAGE, "无法读取：$relative")
                            input.use {
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var single = 0L
                                while (true) {
                                    val read = it.read(buffer)
                                    if (read < 0) break
                                    single += read
                                    expanded += read
                                    if (single > MAX_SINGLE_FILE || expanded > MAX_EXPANDED_BYTES) {
                                        throw HermitException(ErrorCodes.QUOTA, "目录内容超过限制")
                                    }
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                            if (temporary.length() > MAX_ZIP_BYTES) throw HermitException(ErrorCodes.QUOTA, "目录快照压缩后超过 64 MiB")
                        }
                    }
                }
                append(tree, "", 0)
                if (files == 0) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "目录为空")
            }
            FileInputStream(temporary).use { installZip(it, suggestedName, provenance = "directory") }
        } finally {
            temporary.delete()
        }
    }

    fun acquireRelease(releaseId: String) {
        releaseLeases.computeIfAbsent(releaseId) { AtomicInteger() }.incrementAndGet()
    }

    fun releaseRelease(releaseId: String) {
        releaseLeases[releaseId]?.let { count ->
            if (count.decrementAndGet() <= 0) releaseLeases.remove(releaseId, count)
        }
    }

    fun releaseWebRoot(release: CodeRelease): File {
        val root = File(context.filesDir, release.relativeRoot)
        check(root.canonicalPath.startsWith(appsRoot.canonicalPath + File.separator))
        return root
    }

    fun deleteAppFiles(appId: String): Boolean {
        require(appId.matches(Regex("[0-9a-fA-F-]{36}")))
        val dir = File(appsRoot, appId)
        check(dir.canonicalPath.startsWith(appsRoot.canonicalPath + File.separator))
        return !dir.exists() || dir.deleteRecursively()
    }

    fun recoverStorage() {
        val instances = registry.listInstances().associateBy { it.appId }
        appsRoot.mkdirs()
        appsRoot.listFiles()?.filter { it.isDirectory }?.forEach { appRoot ->
            val instance = instances[appRoot.name]
            if (instance == null) {
                appRoot.deleteRecursively()
                return@forEach
            }
            File(appRoot, "incoming").deleteRecursively()
            File(appRoot, "staging").deleteRecursively()
            val knownReleases = registry.listReleases(instance.appId).map { it.releaseId }.toSet()
            File(appRoot, "releases").listFiles()?.filter { it.isDirectory && it.name !in knownReleases }
                ?.forEach { it.deleteRecursively() }
            File(appRoot, "data").listFiles()?.filter { it.isDirectory && it.name != instance.activeDataGeneration }
                ?.forEach { it.deleteRecursively() }
        }
        registry.pendingProfileCleanup().map { it.second }.distinct().forEach(registry::finishDelete)
    }

    private fun cleanupProvisionalRoot(appId: String, existingAppId: String?) {
        if (existingAppId == null && registry.getInstance(appId) == null) {
            File(appsRoot, appId).deleteRecursively()
        }
    }

    private fun extractValidated(zipFile: File, outputRoot: File) {
        val canonicalRoot = outputRoot.canonicalFile
        val names = HashSet<String>()
        var fileCount = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                fileCount++
                if (fileCount > MAX_FILES) throw HermitException(ErrorCodes.QUOTA, "文件数量超过限制")
                if (entry.name.startsWith('/') || entry.name.startsWith('\\') || entry.name.contains('\\')) {
                    throw HermitException(ErrorCodes.INVALID_ARGUMENT, "ZIP 包含绝对路径或反斜杠路径")
                }
                val normalized = entry.name.trimEnd('/')
                if (normalized.isBlank()) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "ZIP 包含空路径")
                validateRelativePath(normalized)
                if (!names.add(normalized.lowercase())) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "ZIP 包含重复路径")
                val target = File(canonicalRoot, normalized).canonicalFile
                if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw HermitException(ErrorCodes.INVALID_ARGUMENT, "ZIP 路径越界")
                }
                if (entry.size > 1L * 1024 * 1024 && entry.compressedSize > 0 && entry.size / entry.compressedSize > 200) {
                    throw HermitException(ErrorCodes.QUOTA, "ZIP 包含异常压缩比文件")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    var single = 0L
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            single += read
                            total += read
                            if (single > MAX_SINGLE_FILE || total > MAX_EXPANDED_BYTES) {
                                throw HermitException(ErrorCodes.QUOTA, "解压后内容超过限制")
                            }
                            out.write(buffer, 0, read)
                        }
                        out.fd.sync()
                    }
                }
                zip.closeEntry()
            }
        }
        if (fileCount == 0) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "ZIP 为空")
    }

    private fun readManifest(root: File): JSONObject? {
        val file = File(root, "hermit.json")
        if (!file.exists()) return null
        if (file.length() > MAX_MANIFEST_BYTES) throw HermitException(ErrorCodes.QUOTA, "hermit.json 过大")
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("schema", -1) != 1) throw HermitException(ErrorCodes.UNSUPPORTED, "不支持的 hermit.json 版本")
        val allowed = setOf("schema", "name", "entry", "routing", "version")
        if (json.keys().asSequence().any { it !in allowed }) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit.json 包含未知字段")
        }
        if (json.has("name")) {
            val name = json.opt("name")
            if (name !is String || name.isBlank() || name.length > 80) {
                throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit.json 的 name 无效")
            }
        }
        if (json.has("entry") && json.opt("entry") !is String) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit.json 的 entry 无效")
        }
        if (json.has("routing") && json.optString("routing") !in setOf("hash", "history")) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit.json 的 routing 无效")
        }
        json.optJSONObject("version")?.let { version ->
            if (version.keys().asSequence().any { it !in setOf("code", "name") }) {
                throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit.json 的 version 包含未知字段")
            }
            if (version.has("code") && (version.opt("code") !is Number || version.getLong("code") < 0)) {
                throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit.json 的 version.code 无效")
            }
            if (version.has("name") && (version.opt("name") !is String || version.getString("name").length > 80)) {
                throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit.json 的 version.name 无效")
            }
        }
        if (json.has("version") && json.optJSONObject("version") == null) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit.json 的 version 无效")
        }
        return json
    }

    private fun treeHash(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("hermit-tree-v1\u0000".toByteArray(Charsets.UTF_8))
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }.forEach { (path, file) ->
                val pathBytes = path.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(pathBytes.size).array())
                digest.update(pathBytes)
                digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.length()).array())
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        return digest.digest().hex()
    }

    private fun validateRelativePath(path: String) {
        if (path.isBlank() || path.length > 512 || path.startsWith("/") || path.contains("\u0000")) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "非法路径")
        }
        if (path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "非法路径：$path")
        }
        if (path.startsWith("__hermit/")) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "路径使用了保留命名空间")
    }

    private fun pruneReleases(appId: String, activeReleaseId: String) {
        val releases = registry.listReleases(appId)
        val keep = buildSet {
            add(activeReleaseId)
            releases.firstOrNull { it.releaseId != activeReleaseId }?.let { add(it.releaseId) }
            releases.filter { (releaseLeases[it.releaseId]?.get() ?: 0) > 0 }.forEach { add(it.releaseId) }
        }
        val removable = releases.filter { it.releaseId !in keep }
        val removed = removable.filter { release ->
            val dir = File(appsRoot, "$appId/releases/${release.releaseId}")
            !dir.exists() || dir.deleteRecursively()
        }.map { it.releaseId }
        registry.deleteReleaseRows(appId, removed)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_ZIP_BYTES = 64L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
        private const val MAX_SINGLE_FILE = 64L * 1024 * 1024
        private const val MAX_FILES = 10_000
        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private const val MAX_TREE_DEPTH = 32
    }
}
