package io.github.zhyuzh3d.hermit.install

import android.content.Context
import android.net.Uri
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HermitException
import io.github.zhyuzh3d.hermit.registry.AppRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Proxy
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
    suspend fun installHttps(url: String, name: String?, existingAppId: String? = null, expected: String? = null): InstallResult =
        withContext(Dispatchers.IO) {
            val normalized = validateHttps(url)
            val archive = download(normalized)
            try {
                val result = FileInputStream(archive).use {
                    installer.installZip(it, name, existingAppId, "https-package", expectedReleaseId = expected)
                }
                registry.updateSource(result.appId, "https-package", JSONObject().put("url", normalized).toString())
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
                installer.installZip(it, name ?: repo, existingAppId, "github", expectedReleaseId = expected,
                    sourceRevision = resolvedCommit)
            }
            registry.updateSource(result.appId, "github", JSONObject().put("owner", owner).put("repo", repo)
                .put("ref", ref).put("resolvedCommit", resolvedCommit).put("path", normalizedPath).toString())
            result
        } finally { sourceArchive.delete(); filtered.delete() }
    }

    suspend fun update(appId: String): InstallResult {
        val app = registry.getInstance(appId) ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        val spec = JSONObject(app.sourceSpec)
        return when (app.sourceAdapter) {
            "https-package" -> installHttps(spec.getString("url"), app.name, appId, app.activeReleaseId)
            "github" -> installGitHub(spec.getString("owner"), spec.getString("repo"), spec.getString("ref"),
                spec.optString("path"), app.name, appId, app.activeReleaseId)
            else -> throw HermitException(ErrorCodes.UNSUPPORTED, "该来源不支持在线检查更新")
        }
    }

    private fun download(initialUrl: String, headers: Map<String, String> = emptyMap()): File {
        var url = validateHttps(initialUrl)
        var redirects = 0
        val target = File(context.cacheDir, "download-${UUID.randomUUID()}.zip")
        try {
            while (true) {
                val uri = Uri.parse(url)
                val addresses = InetAddress.getAllByName(uri.host).toList()
                if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }) {
                    throw HermitException(ErrorCodes.ORIGIN_DENIED, "远程包地址必须解析到公网")
                }
                val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(true).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                    .dns { host -> if (host.equals(uri.host, true)) addresses else throw java.net.UnknownHostException(host) }.build()
                val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
                client.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        if (redirects++ >= 5) throw HermitException(ErrorCodes.NETWORK, "下载重定向次数过多")
                        val location = response.header("Location") ?: throw HermitException(ErrorCodes.NETWORK, "下载重定向缺少地址")
                        url = validateHttps(response.request.url.resolve(location)?.toString() ?: "")
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

    private fun validateHttps(value: String): String {
        val normalized = value.trim()
        if (normalized.length > MAX_URL_LENGTH) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "远程包地址过长")
        if (normalized.any { it <= '\u001F' }) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "远程包地址包含控制字符")
        val uri = Uri.parse(normalized)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.host!!.endsWith(".hermit.invalid", true)) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "远程包必须使用有效 HTTPS 地址")
        }
        return uri.toString()
    }

    private fun validateSlug(value: String, field: String) {
        if (!value.matches(Regex("[A-Za-z0-9_.-]{1,100}")) || value.startsWith('.') || value.endsWith('.')) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "GitHub $field 无效")
        }
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
        private const val MAX_URL_LENGTH = 4096
    }
}
