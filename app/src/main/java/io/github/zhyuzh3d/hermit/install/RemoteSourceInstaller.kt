package io.github.zhyuzh3d.hermit.install

import android.content.Context
import android.net.Uri
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HappSource
import io.github.zhyuzh3d.hermit.model.HermitException
import io.github.zhyuzh3d.hermit.model.WebAppInstance
import io.github.zhyuzh3d.hermit.registry.AppRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RemoteSourceInstaller(
    private val context: Context,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
) {
    data class OnlineInstallResult(
        val appId: String,
        val releaseId: String?,
        val strategy: String,
        val kind: String,
    )

    private data class InstallManifest(
        val manifestUrl: String,
        val packageUrl: String,
        val sha256: String?,
    )

    /**
     * A URL makes this an online-source happ. If the origin publishes
     * /hermit-install.json, its package is materialized locally by default;
     * otherwise the URL is saved for live runtime.
     */
    suspend fun installOnline(url: String, name: String?,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE }): OnlineInstallResult = withContext(Dispatchers.IO) {
        val normalized = validateNetworkUrl(url)
        val parsed = normalized.toHttpUrl()
        if (parsed.encodedPath.endsWith(".zip", ignoreCase = true)) {
            val result = installHttps(normalized, name, identityChoice = identityChoice)
            return@withContext OnlineInstallResult(result.appId, result.releaseId, "local", "package")
        }
        if (parsed.encodedPath.substringAfterLast('/').equals("hermit-install.json", ignoreCase = true)) {
            val manifest = readInstallManifest(parsed, required = true)!!
            return@withContext installManifestPackage(manifest, name, null, "online-descriptor", "descriptor", identityChoice)
        }
        val manifest = discoverInstallManifest(normalized)
        if (manifest == null) {
            val fallbackName = name?.trim()?.takeIf { it.isNotBlank() }
                ?: normalized.toHttpUrl().host
            val app = WebAppInstance.newOnlineLive(fallbackName, normalized)
            registry.insertInstance(app)
            registry.updateSource(app.appId, "online", JSONObject().put("url", normalized).toString())
            return@withContext OnlineInstallResult(app.appId, null, "live", "live")
        }

        installManifestPackage(manifest, name, normalized, "online-manifest", "manifest", identityChoice)
    }

    private suspend fun installManifestPackage(
        manifest: InstallManifest,
        name: String?,
        liveUrl: String?,
        provenance: String,
        kind: String,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice,
    ): OnlineInstallResult {
        val archive = downloadSameOrigin(manifest.packageUrl, manifest.manifestUrl)
        try {
            val result = FileInputStream(archive).use {
                installer.installZip(
                    it,
                    name,
                    provenance = provenance,
                    declaredSha256 = manifest.sha256,
                    fallbackName = liveUrl?.toHttpUrl()?.host ?: manifest.manifestUrl.toHttpUrl().host,
                    source = HappSource.ONLINE,
                    liveUrl = liveUrl,
                    downloadUrl = manifest.packageUrl,
                    identityChoice = identityChoice,
                )
            }
            registry.updateSource(result.appId, provenance, JSONObject()
                .put("url", liveUrl ?: manifest.manifestUrl)
                .put("manifestUrl", manifest.manifestUrl)
                .put("packageUrl", manifest.packageUrl)
                .put("sha256", manifest.sha256 ?: JSONObject.NULL)
                .toString())
            return OnlineInstallResult(result.appId, result.releaseId, "local", kind)
        } finally {
            archive.delete()
        }
    }

    suspend fun installHttps(url: String, name: String?, existingAppId: String? = null, expected: String? = null,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE }): InstallResult =
        withContext(Dispatchers.IO) {
            val normalized = validateDownloadUrl(url)
            val archive = download(normalized)
            try {
                val result = FileInputStream(archive).use {
                    installer.installZip(it, name, existingAppId, "https-package", expectedReleaseId = expected,
                    source = HappSource.ONLINE, downloadUrl = normalized, identityChoice = identityChoice)
                }
            registry.updateSource(result.appId, "https-package", JSONObject().put("url", normalized).toString())
            registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, normalized, it.versionCode, it.versionName) }
            result
            } finally { archive.delete() }
        }

    suspend fun installGitHub(
        owner: String,
        repo: String,
        ref: String,
        path: String,
        name: String?,
        existingAppId: String? = null,
        expected: String? = null,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE },
    ): InstallResult = withContext(Dispatchers.IO) {
        validateSlug(owner, "owner"); validateSlug(repo, "repo")
        if (ref.isBlank() || ref.length > 200 || ref.contains("..")) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "GitHub ref 无效")
        val normalizedPath = path.trim('/').also {
            if (it.isNotEmpty()) validateRelative(it)
        }
        val resolvedCommit = resolveGitHubCommit(owner, repo, ref)
        val url = "https://api.github.com".toHttpUrl().newBuilder()
            .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo)
            .addPathSegment("zipball").addPathSegment(resolvedCommit).build().toString()
        val sourceArchive = download(url, mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"))
        val filtered = File(context.cacheDir, "github-${UUID.randomUUID()}.zip")
        try {
            filterGitHubArchive(sourceArchive, filtered, normalizedPath)
            val result = FileInputStream(filtered).use {
                installer.installZip(it, name, existingAppId, "github", expectedReleaseId = expected,
                    sourceRevision = resolvedCommit, fallbackName = repo, source = HappSource.ONLINE,
                    downloadUrl = "https://github.com/$owner/$repo.git", identityChoice = identityChoice)
            }
            val gitUrl = "https://github.com/$owner/$repo.git"
            registry.updateSource(result.appId, "github", JSONObject().put("owner", owner).put("repo", repo)
                .put("ref", ref).put("resolvedCommit", resolvedCommit).put("path", normalizedPath).toString())
            registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, gitUrl, it.versionCode, it.versionName) }
            val installed = registry.getInstance(result.appId)
            if (installed?.updateUrl == null) registry.updateUrls(result.appId, installed?.liveUrl, gitUrl)
            result
        } finally { sourceArchive.delete(); filtered.delete() }
    }

    suspend fun update(appId: String): InstallResult = withContext(Dispatchers.IO) {
        val app = registry.getInstance(appId) ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        app.updateUrl?.let { return@withContext installUpdateUrl(app, it) }
        val spec = JSONObject(app.sourceSpec)
        when (app.sourceAdapter) {
            "https-package" -> installHttps(spec.getString("url"), app.name, appId, app.activeReleaseId)
            "github" -> installGitHub(spec.getString("owner"), spec.getString("repo"), spec.getString("ref"),
                spec.optString("path"), app.name, appId, app.activeReleaseId)
            "online-manifest" -> installManifestUpdate(app)
            "online-descriptor" -> installDescriptorUpdate(app)
            else -> throw HermitException(ErrorCodes.UNSUPPORTED, "该来源不支持在线检查更新")
        }
    }

    suspend fun reinstall(appId: String): InstallResult = withContext(Dispatchers.IO) {
        val app = registry.getInstance(appId) ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        val url = app.downloadUrl ?: throw HermitException(ErrorCodes.UNSUPPORTED, "没有可用的原始下载地址")
        installDownloadUrl(app, url)
    }

    private suspend fun installDownloadUrl(app: WebAppInstance, rawUrl: String): InstallResult {
        val github = githubRepository(rawUrl)
        if (github != null) {
            val source = runCatching { JSONObject(app.sourceSpec) }.getOrDefault(JSONObject())
            return installGitHub(github.first, github.second, source.optString("ref", "main"), source.optString("path"),
                app.name, app.appId, app.activeReleaseId)
        }
        if (rawUrl.trim().endsWith(".git", true)) {
            throw HermitException(ErrorCodes.UNSUPPORTED, "当前版本只支持 GitHub 仓库地址；其他 Git 服务请提供 ZIP 下载地址")
        }
        return installHttps(rawUrl, app.name, app.appId, app.activeReleaseId)
    }

    private suspend fun installUpdateUrl(app: WebAppInstance, rawUrl: String): InstallResult {
        val updateUrl = validateNetworkUrl(rawUrl)
        if (updateUrl.endsWith(".git", true)) return installDownloadUrl(app, updateUrl)
        if (!Uri.parse(updateUrl).path.orEmpty().endsWith(".json", true)) {
            val archive = download(updateUrl)
            val result = try {
                FileInputStream(archive).use { installer.installZip(it, app.name, app.appId, "update-url", expectedReleaseId = app.activeReleaseId) }
            } finally { archive.delete() }
            registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, updateUrl, it.versionCode, it.versionName) }
            return result
        }
        val endpoint = updateUrl.toHttpUrl()
        val client = clientFor(endpoint.host)
        val text = client.newCall(Request.Builder().url(endpoint).header("Accept", "application/json").build()).execute().use { response ->
            if (!response.isSuccessful) throw HermitException(ErrorCodes.NETWORK, "更新信息请求失败：HTTP ${response.code}", response.code >= 500)
            if (response.body.contentLength() > MAX_MANIFEST_BYTES) throw HermitException(ErrorCodes.QUOTA, "更新信息过大")
            readAtMost(response.body.byteStream(), MAX_MANIFEST_BYTES + 1).also {
                if (it.size > MAX_MANIFEST_BYTES) throw HermitException(ErrorCodes.QUOTA, "更新信息过大")
            }.toString(Charsets.UTF_8)
        }
        val json = runCatching { JSONObject(text) }.getOrElse { throw HermitException(ErrorCodes.INVALID_ARGUMENT, "更新信息格式无效") }
        if (json.keys().asSequence().any { it !in setOf("schema", "version", "package", "sha256") } || json.optInt("schema", -1) != 1) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "更新信息字段无效")
        }
        val packageUrl = endpoint.resolve(json.getString("package"))?.toString()
            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "更新包地址无效")
        val sha256 = json.optString("sha256").takeIf { it.isNotBlank() }
        if (sha256 != null && !sha256.matches(SHA256_PATTERN)) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "更新包摘要无效")
        val archive = downloadSameOrigin(packageUrl, updateUrl)
        val result = try {
            FileInputStream(archive).use { installer.installZip(it, app.name, app.appId, "update-url",
                expectedReleaseId = app.activeReleaseId, declaredSha256 = sha256) }
        } finally { archive.delete() }
        registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, packageUrl, it.versionCode, it.versionName) }
        return result
    }

    private suspend fun installManifestUpdate(app: WebAppInstance): InstallResult {
        val liveUrl = app.liveUrl ?: throw HermitException(ErrorCodes.CONFLICT, "线上 happ 缺少页面地址")
        val manifest = discoverInstallManifest(liveUrl)
            ?: throw HermitException(ErrorCodes.NETWORK, "线上 happ 不再提供 hermit-install.json", true)
        val archive = downloadSameOrigin(manifest.packageUrl, manifest.manifestUrl)
        try {
            val result = FileInputStream(archive).use {
                installer.installZip(it, app.name, app.appId, "online-manifest",
                    expectedReleaseId = app.activeReleaseId, declaredSha256 = manifest.sha256)
            }
            registry.updateSource(app.appId, "online-manifest", JSONObject()
                .put("url", liveUrl)
                .put("manifestUrl", manifest.manifestUrl)
                .put("packageUrl", manifest.packageUrl)
                .put("sha256", manifest.sha256 ?: JSONObject.NULL)
                .toString())
            registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, manifest.packageUrl, it.versionCode, it.versionName) }
            return result
        } finally {
            archive.delete()
        }
    }

    private suspend fun installDescriptorUpdate(app: WebAppInstance): InstallResult {
        val source = runCatching { JSONObject(app.sourceSpec) }.getOrElse {
            throw HermitException(ErrorCodes.STORAGE, "安装来源记录已损坏")
        }
        val manifestUrl = source.optString("manifestUrl").takeIf { it.isNotBlank() }
            ?: throw HermitException(ErrorCodes.STORAGE, "安装配置地址缺失")
        val manifest = readInstallManifest(manifestUrl.toHttpUrl(), required = true)!!
        val archive = downloadSameOrigin(manifest.packageUrl, manifest.manifestUrl)
        try {
            val result = FileInputStream(archive).use {
                installer.installZip(it, app.name, app.appId, "online-descriptor",
                    expectedReleaseId = app.activeReleaseId, declaredSha256 = manifest.sha256)
            }
            registry.updateSource(result.appId, "online-descriptor", JSONObject()
                .put("url", manifest.manifestUrl)
                .put("manifestUrl", manifest.manifestUrl)
                .put("packageUrl", manifest.packageUrl)
                .put("sha256", manifest.sha256 ?: JSONObject.NULL)
                .toString())
            registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, manifest.packageUrl, it.versionCode, it.versionName) }
            return result
        } finally {
            archive.delete()
        }
    }

    private fun discoverInstallManifest(pageUrl: String): InstallManifest? {
        val page = pageUrl.toHttpUrl()
        val manifestUrl = page.newBuilder()
            .encodedPath("/hermit-install.json")
            .query(null)
            .fragment(null)
            .build()
        return readInstallManifest(manifestUrl, required = false)
    }

    private fun readInstallManifest(manifestUrl: okhttp3.HttpUrl, required: Boolean): InstallManifest? {
        val request = Request.Builder().url(manifestUrl).header("Accept", "application/json").build()
        val body = try {
            SAME_ORIGIN_CLIENT.newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 410) {
                    if (required) throw HermitException(ErrorCodes.NETWORK, "安装配置不存在：HTTP ${response.code}")
                    return null
                }
                if (!response.isSuccessful) {
                    if (required) throw HermitException(ErrorCodes.NETWORK, "安装配置读取失败：HTTP ${response.code}", response.code >= 500)
                    return null
                }
                val declared = response.body.contentLength()
                if (declared > MAX_MANIFEST_BYTES) throw HermitException(ErrorCodes.QUOTA, "hermit-install.json 过大")
                val output = java.io.ByteArrayOutputStream()
                val input = response.body.byteStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_MANIFEST_BYTES) throw HermitException(ErrorCodes.QUOTA, "hermit-install.json 过大")
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } catch (error: IOException) {
            if (required) throw HermitException(ErrorCodes.NETWORK, error.message ?: "安装配置读取失败", true)
            return null
        }
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit-install.json 格式无效") }
        if (json.optInt("schema", -1) != 1) throw HermitException(ErrorCodes.UNSUPPORTED, "不支持的 hermit-install.json 版本")
        if (json.keys().asSequence().any { it !in setOf("schema", "package", "sha256") }) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit-install.json 包含未知字段")
        }
        val relativePackage = json.optString("package")
        validatePackagePath(relativePackage)
        val packageUrl = manifestUrl.resolve(relativePackage)
            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "安装包地址无效")
        if (packageUrl.scheme != manifestUrl.scheme || packageUrl.host != manifestUrl.host || packageUrl.port != manifestUrl.port) {
            throw HermitException(ErrorCodes.ORIGIN_DENIED, "自动安装包必须与 happ 页面同源")
        }
        val sha256 = json.optString("sha256").takeIf { it.isNotBlank() }?.lowercase()
        if (sha256 != null && !sha256.matches(SHA256_PATTERN)) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "安装包摘要格式无效")
        }
        return InstallManifest(manifestUrl.toString(), packageUrl.toString(), sha256)
    }

    private fun downloadSameOrigin(url: String, manifestUrl: String): File {
        val targetUrl = url.toHttpUrl()
        val manifest = manifestUrl.toHttpUrl()
        if (targetUrl.scheme != manifest.scheme || targetUrl.host != manifest.host || targetUrl.port != manifest.port) {
            throw HermitException(ErrorCodes.ORIGIN_DENIED, "自动安装包必须与 happ 页面同源")
        }
        val target = File(context.cacheDir, "online-package-${UUID.randomUUID()}.zip")
        try {
            val request = Request.Builder().url(targetUrl).build()
            SAME_ORIGIN_CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HermitException(ErrorCodes.NETWORK, "安装包下载失败：HTTP ${response.code}", response.code >= 500)
                if (response.body.contentLength() > MAX_DOWNLOAD_BYTES) throw HermitException(ErrorCodes.QUOTA, "远程包超过 64 MiB")
                FileOutputStream(target).use { output ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) throw HermitException(ErrorCodes.QUOTA, "远程包超过 64 MiB")
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw if (error is HermitException) error else HermitException(ErrorCodes.NETWORK, error.message ?: "远程包下载失败", true)
        }
    }

    private fun validateNetworkUrl(value: String): String {
        val normalized = value.trim()
        if (normalized.length > MAX_URL_LENGTH || normalized.any { it <= '\u001F' }) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面地址无效")
        }
        val uri = Uri.parse(normalized)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.host!!.endsWith(".hermit.invalid", true)) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面地址必须是有效 HTTP(S) URL")
        }
        return uri.toString()
    }

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray = input.use {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (output.size() < limit) {
            val read = it.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private fun validatePackagePath(path: String) {
        if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.contains('\\') || path.contains('?') || path.contains('#') ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "安装包必须是同源相对路径")
        }
    }

    private fun download(initialUrl: String, headers: Map<String, String> = emptyMap()): File {
        var url = validateDownloadUrl(initialUrl)
        var redirects = 0
        val target = File(context.cacheDir, "download-${UUID.randomUUID()}.zip")
        try {
            while (true) {
                val uri = Uri.parse(url)
                val addresses = InetAddress.getAllByName(uri.host).toList()
                if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress }) {
                    throw HermitException(ErrorCodes.ORIGIN_DENIED, "远程包地址不能指向本机")
                }
                val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(true).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                    .dns { host -> if (host.equals(uri.host, true)) addresses else throw java.net.UnknownHostException(host) }.build()
                val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
                client.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        if (redirects++ >= 5) throw HermitException(ErrorCodes.NETWORK, "下载重定向次数过多")
                        val location = response.header("Location") ?: throw HermitException(ErrorCodes.NETWORK, "下载重定向缺少地址")
                        url = validateDownloadUrl(response.request.url.resolve(location)?.toString() ?: "")
                        continue
                    }
                    if (!response.isSuccessful) throw HermitException(ErrorCodes.NETWORK, "下载失败：HTTP ${response.code}", response.code >= 500)
                    if (response.body.contentLength() > MAX_DOWNLOAD_BYTES) throw HermitException(ErrorCodes.QUOTA, "远程包超过 64 MiB")
                    FileOutputStream(target).use { output ->
                        val input = response.body.byteStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_DOWNLOAD_BYTES) throw HermitException(ErrorCodes.QUOTA, "远程包超过 64 MiB")
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                    return target
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw if (error is HermitException) error else HermitException(ErrorCodes.NETWORK, error.message ?: "远程包下载失败", true)
        }
    }

    private fun resolveGitHubCommit(owner: String, repo: String, ref: String): String {
        val url = "https://api.github.com".toHttpUrl().newBuilder()
            .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo)
            .addPathSegment("commits").addPathSegment(ref).build()
        val addresses = InetAddress.getAllByName(url.host).toList()
        if (addresses.isEmpty() || addresses.any {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress
            }) throw HermitException(ErrorCodes.ORIGIN_DENIED, "GitHub API 地址解析异常")
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(true).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .dns { host -> if (host.equals(url.host, true)) addresses else throw java.net.UnknownHostException(host) }.build()
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = if (response.code == 403 || response.code == 429) "（可能达到匿名 API 限额）" else ""
                    throw HermitException(ErrorCodes.NETWORK, "无法解析 GitHub ref：HTTP ${response.code}$detail", response.code >= 500)
                }
                val length = response.body.contentLength()
                if (length > MAX_GITHUB_JSON_BYTES) throw HermitException(ErrorCodes.QUOTA, "GitHub 响应过大")
                val text = response.body.charStream().readText()
                if (text.toByteArray().size > MAX_GITHUB_JSON_BYTES) throw HermitException(ErrorCodes.QUOTA, "GitHub 响应过大")
                return JSONObject(text).getString("sha").takeIf { it.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) }
                    ?.lowercase() ?: throw HermitException(ErrorCodes.NETWORK, "GitHub 返回了无效 commit")
            }
        } catch (error: Throwable) {
            throw if (error is HermitException) error else HermitException(ErrorCodes.NETWORK, error.message ?: "GitHub ref 解析失败", true)
        }
    }

    private fun filterGitHubArchive(source: File, output: File, subtree: String) {
        var written = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val parts = entry.name.trimEnd('/').split('/')
                    if (parts.size < 2) continue
                    val relativeToRepo = parts.drop(1).joinToString("/")
                    val relative = if (subtree.isEmpty()) relativeToRepo else {
                        if (relativeToRepo == subtree || !relativeToRepo.startsWith("$subtree/")) continue
                        relativeToRepo.removePrefix("$subtree/")
                    }
                    if (relative.isBlank() || entry.isDirectory) continue
                    validateRelative(relative)
                    zip.putNextEntry(ZipEntry(relative))
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_EXPANDED_BYTES) throw HermitException(ErrorCodes.QUOTA, "GitHub 源码展开后过大")
                        zip.write(buffer, 0, read)
                    }
                    zip.closeEntry(); written++
                }
            }
        }
        if (written == 0) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "GitHub 路径为空或不存在")
    }

    private fun validateDownloadUrl(value: String): String {
        val normalized = value.trim()
        if (normalized.length > MAX_URL_LENGTH) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "远程包地址过长")
        if (normalized.any { it <= '\u001F' }) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "远程包地址包含控制字符")
        val uri = Uri.parse(normalized)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.host!!.endsWith(".hermit.invalid", true)) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "远程包必须使用有效 HTTP(S) 地址")
        }
        return uri.toString()
    }

    private fun clientFor(host: String): OkHttpClient {
        val addresses = InetAddress.getAllByName(host).toList()
        if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress }) {
            throw HermitException(ErrorCodes.ORIGIN_DENIED, "地址不能指向本机")
        }
        return OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .dns { requested -> if (requested.equals(host, true)) addresses else throw java.net.UnknownHostException(requested) }.build()
    }

    private fun validateSlug(value: String, field: String) {
        if (!value.matches(Regex("[A-Za-z0-9_.-]{1,100}")) || value.startsWith('.') || value.endsWith('.')) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "GitHub $field 无效")
        }
    }

    private fun githubRepository(value: String): Pair<String, String>? {
        val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return null
        if (!uri.host.equals("github.com", true)) return null
        val parts = uri.path.orEmpty().trim('/').removeSuffix(".git").split('/').filter { it.isNotBlank() }
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }

    private fun validateRelative(path: String) {
        if (path.length > 512 || path.startsWith('/') || path.contains('\\') || path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "GitHub 子目录无效")
        }
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
        private const val MAX_GITHUB_JSON_BYTES = 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_URL_LENGTH = 4096
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val SAME_ORIGIN_CLIENT = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
