package io.github.zhyuzh3d.hermit.model

import android.net.Uri
import org.json.JSONObject
import java.util.UUID

enum class HappSource { ONLINE, LOCAL }
enum class HappRuntimeMode { LOCAL, LIVE }

data class WebAppInstance(
    val appId: String,
    val name: String,
    val source: HappSource,
    val runtimeMode: HappRuntimeMode,
    val startUrl: String,
    val liveUrl: String?,
    val primaryOrigin: String,
    val webProfileName: String,
    val trustRevision: Long,
    val activeReleaseId: String?,
    val activeDataGeneration: String,
    val sourceAdapter: String,
    val sourceSpec: String,
    val developerEnabled: Boolean,
    val favorite: Boolean,
    val iconDataUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("appId", appId)
        .put("name", name)
        .put("source", source.name.lowercase())
        .put("runtimeMode", runtimeMode.name.lowercase())
        // Compatibility for already published HermitUI versions. New code must
        // use source and runtimeMode instead of this overloaded field.
        .put("mode", if (runtimeMode == HappRuntimeMode.LIVE) "online" else "local")
        .put("startUrl", startUrl)
        .put("liveUrl", liveUrl ?: JSONObject.NULL)
        .put("origin", primaryOrigin)
        .put("trustRevision", trustRevision)
        .put("activeReleaseId", activeReleaseId)
        .put("localAvailable", activeReleaseId != null)
        .put("liveAvailable", source == HappSource.ONLINE && liveUrl != null)
        .put("sourceAdapter", sourceAdapter)
        .put("developerEnabled", developerEnabled)
        .put("favorite", favorite)
        .put("iconDataUrl", iconDataUrl)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun newOnlineLive(name: String, url: String, favorite: Boolean = false): WebAppInstance {
            val appId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val uri = Uri.parse(url)
            val port = if (uri.port != -1 && !((uri.scheme.equals("https", true) && uri.port == 443) || (uri.scheme.equals("http", true) && uri.port == 80))) ":${uri.port}" else ""
            val origin = "${uri.scheme!!.lowercase()}://${uri.host!!.lowercase()}$port"
            return WebAppInstance(
                appId = appId,
                name = name,
                source = HappSource.ONLINE,
                runtimeMode = HappRuntimeMode.LIVE,
                startUrl = url,
                liveUrl = url,
                primaryOrigin = origin,
                webProfileName = "app-${appId.replace("-", "")}",
                trustRevision = 1,
                activeReleaseId = null,
                activeDataGeneration = UUID.randomUUID().toString(),
                sourceAdapter = "online",
                sourceSpec = JSONObject().put("url", url).toString(),
                developerEnabled = false,
                favorite = favorite,
                iconDataUrl = null,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
