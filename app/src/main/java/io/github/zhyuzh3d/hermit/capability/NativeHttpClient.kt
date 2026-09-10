package io.github.zhyuzh3d.hermit.capability

import android.net.Uri
import android.util.Base64
import io.github.zhyuzh3d.hermit.data.FileStore
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HermitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class NativeHttpClient(private val files: FileStore) {
    data class AuthorizationTarget(val origin: String, val addressClass: String) {
        val grantScope: String get() = "$addressClass:$origin"
    }

    suspend fun authorizationTarget(rawOrigin: String): AuthorizationTarget {
        val uri = Uri.parse(normalize(rawOrigin))
        if (uri.query != null || uri.fragment != null || (!uri.path.isNullOrEmpty() && uri.path != "/")) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "网络授权范围必须是完整 Origin")
        }
        val addresses = withContext(Dispatchers.IO) { resolveAndValidate(uri) }
        return AuthorizationTarget(origin(uri), if (addresses.any(::isPrivate)) "private" else "public")
    }

    suspend fun request(
        appId: String,
        generation: String,
        params: JSONObject,
        authorize: suspend (origin: String, addressClass: String) -> Unit,
    ): JSONObject {
        var url = normalize(params.getString("url"))
        val method = params.optString("method", "GET").uppercase()
        if (method !in METHODS) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "不支持的 HTTP 方法")
        val bodyBytes = when {
            params.has("bodyBase64") -> runCatching { Base64.decode(params.getString("bodyBase64"), Base64.DEFAULT) }
                .getOrElse { throw HermitException(ErrorCodes.INVALID_ARGUMENT, "bodyBase64 无效") }
            params.has("bodyText") -> params.getString("bodyText").toByteArray(Charsets.UTF_8)
            else -> null
        }
        if (bodyBytes != null && bodyBytes.size > MAX_REQUEST_BODY) throw HermitException(ErrorCodes.QUOTA, "请求体超过 1 MiB")
        if (method in setOf("GET", "HEAD") && bodyBytes != null) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "$method 不能携带请求体")
        val originalHeaders = params.optJSONObject("headers") ?: JSONObject()
        var redirects = 0
        var stripCredentials = false
        while (true) {
            val uri = Uri.parse(url)
            val addresses = withContext(Dispatchers.IO) { resolveAndValidate(uri) }
            val addressClass = if (addresses.any(::isPrivate)) "private" else "public"
            val target = AuthorizationTarget(origin(uri), addressClass)
            authorize(target.origin, target.addressClass)
            val fixedDns = Dns { hostname ->
                if (!hostname.equals(uri.host, true)) throw java.net.UnknownHostException("Unexpected host")
                addresses
            }
            val client = OkHttpClient.Builder()
                .dns(fixedDns).proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(url).method(method,
                if (bodyBytes == null) null else bodyBytes.toRequestBody(params.optString("contentType", "application/octet-stream").toMediaTypeOrNull()))
            val names = originalHeaders.keys()
            while (names.hasNext()) {
                val name = names.next()
                if (name.lowercase() in FORBIDDEN_HEADERS) continue
                if (stripCredentials && name.lowercase() in CREDENTIAL_HEADERS) continue
                val value = originalHeaders.optString(name)
                if (value.length <= 8_192 && !value.contains('\n') && !value.contains('\r')) request.header(name, value)
            }
            val response = try {
                withContext(Dispatchers.IO) { client.newCall(request.build()).execute() }
            } catch (error: Throwable) {
                throw HermitException(ErrorCodes.NETWORK, error.message ?: "网络请求失败", true)
            }
            response.use { result ->
                val location = result.header("Location")
                if (result.code in 300..399 && location != null && method in setOf("GET", "HEAD")) {
                    if (redirects++ >= MAX_REDIRECTS) throw HermitException(ErrorCodes.NETWORK, "重定向次数过多")
                    val next = result.request.url.resolve(location)
                        ?: throw HermitException(ErrorCodes.NETWORK, "无效的重定向地址")
                    val nextUri = Uri.parse(next.toString())
                    if (uri.scheme.equals("https", true) && !nextUri.scheme.equals("https", true)) {
                        throw HermitException(ErrorCodes.NETWORK, "拒绝 HTTPS 降级重定向")
                    }
                    stripCredentials = stripCredentials || origin(uri) != origin(nextUri)
                    url = normalize(next.toString())
                    continue
                }
                val headers = JSONObject()
                result.headers.names().filter { it.lowercase() !in setOf("set-cookie", "www-authenticate", "proxy-authenticate") }
                    .forEach { headers.put(it, result.headers.values(it).joinToString(", ").take(16_384)) }
                val contentType = result.body.contentType()?.toString() ?: "application/octet-stream"
                if (method == "HEAD") return JSONObject().put("status", result.code).put("headers", headers)
                    .put("url", result.request.url.toString()).put("body", JSONObject.NULL)
                val declaredLength = result.body.contentLength()
                if (declaredLength > MAX_RESPONSE_BODY) throw HermitException(ErrorCodes.QUOTA, "响应超过 64 MiB")
                if (declaredLength < 0 || declaredLength > INLINE_RESPONSE_BYTES) {
                    val stored = withContext(Dispatchers.IO) {
                        result.body.byteStream().use { files.import(appId, generation, it, suggestedName(uri), contentType) }
                    }
                    return JSONObject().put("status", result.code).put("headers", headers)
                        .put("url", result.request.url.toString()).put("file", stored)
                }
                val bytes = withContext(Dispatchers.IO) { result.body.bytes() }
                if (bytes.size > INLINE_RESPONSE_BYTES) {
                    val stored = withContext(Dispatchers.IO) { files.import(appId, generation, bytes.inputStream(), suggestedName(uri), contentType) }
                    return JSONObject().put("status", result.code).put("headers", headers)
                        .put("url", result.request.url.toString()).put("file", stored)
                }
                val textual = contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")
                return JSONObject().put("status", result.code).put("headers", headers).put("url", result.request.url.toString())
                    .put(if (textual) "bodyText" else "bodyBase64", if (textual) bytes.toString(Charsets.UTF_8) else Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
    }

    private fun normalize(input: String): String {
        val value = input.trim()
        if (value.length > MAX_URL_LENGTH) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "Native HTTP 地址过长")
        if (value.any { it <= '\u001F' }) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "Native HTTP 地址包含控制字符")
        val uri = Uri.parse(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "Native HTTP 仅支持无用户信息的 HTTP(S) 地址")
        }
        if (uri.port == 8765 || uri.host!!.endsWith(".hermit.invalid", true)) {
            throw HermitException(ErrorCodes.ORIGIN_DENIED, "该目标是 Hermit 保留端点")
        }
        return uri.toString()
    }

    private fun resolveAndValidate(uri: Uri): List<InetAddress> {
        val addresses = InetAddress.getAllByName(uri.host).toList()
        if (addresses.isEmpty()) throw HermitException(ErrorCodes.NETWORK, "域名没有可用地址")
        addresses.forEach { rawAddress ->
            val address = effectiveAddress(rawAddress)
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress || address.isLinkLocalAddress || isMetadata(address)) {
                throw HermitException(ErrorCodes.ORIGIN_DENIED, "目标地址类别不允许")
            }
        }
        val classes = addresses.map(::isPrivate).distinct()
        if (classes.size != 1) throw HermitException(ErrorCodes.ORIGIN_DENIED, "目标同时解析到公网和私网地址")
        return addresses
    }

    private fun isPrivate(rawAddress: InetAddress): Boolean {
        val address = effectiveAddress(rawAddress)
        return address.isSiteLocalAddress || when (address) {
        is Inet4Address -> address.address.let { (it[0].toInt() and 0xff) == 100 && (it[1].toInt() and 0xc0) == 64 }
        is Inet6Address -> (address.address[0].toInt() and 0xfe) == 0xfc
        else -> false
        }
    }

    private fun isMetadata(address: InetAddress): Boolean = effectiveAddress(address).hostAddress?.substringBefore('%') in setOf(
        "169.254.169.254", "100.100.100.200", "fd00:ec2::254"
    )

    private fun effectiveAddress(address: InetAddress): InetAddress {
        if (address !is Inet6Address) return address
        val bytes = address.address
        val mapped = bytes.size == 16 && bytes.sliceArray(0..9).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        return if (mapped) InetAddress.getByAddress(bytes.copyOfRange(12, 16)) else address
    }

    private fun origin(uri: Uri): String {
        val default = (uri.scheme == "https" && uri.port in setOf(-1, 443)) || (uri.scheme == "http" && uri.port in setOf(-1, 80))
        return "${uri.scheme!!.lowercase()}://${uri.host!!.lowercase()}${if (default) "" else ":${uri.port}"}"
    }

    private fun suggestedName(uri: Uri) = uri.lastPathSegment?.takeIf { it.isNotBlank() }?.take(120) ?: "response.bin"

    companion object {
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "proxy-connection", "upgrade", "te", "trailer")
        private val CREDENTIAL_HEADERS = setOf("authorization", "cookie", "proxy-authorization")
        private const val MAX_REQUEST_BODY = 1024 * 1024
        private const val INLINE_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_RESPONSE_BODY = 64L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val MAX_URL_LENGTH = 4096
    }
}
