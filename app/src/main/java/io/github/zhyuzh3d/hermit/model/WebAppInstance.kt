package io.github.zhyuzh3d.hermit.model

import android.net.Uri
import org.json.JSONObject
import java.util.UUID

enum class DeliveryMode { ONLINE, LOCAL }

data class WebAppInstance(
    val appId: String,
    val name: String,
    val mode: DeliveryMode,
    val startUrl: String,
    val primaryOrigin: String,
    val webProfileName: String,
    val trustRevision: Long,
    val activeReleaseId: String?,
    val activeDataGeneration: String,
    val sourceAdapter: String,
    val sourceSpec: String,
    val developerEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("appId", appId)
        .put("name", name)
        .put("mode", mode.name.lowercase())
        .put("startUrl", startUrl)
        .put("origin", primaryOrigin)
        .put("trustRevision", trustRevision)
        .put("activeReleaseId", activeReleaseId)
        .put("sourceAdapter", sourceAdapter)
        .put("developerEnabled", developerEnabled)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun newOnline(name: String, url: String): WebAppInstance {
            val appId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val uri = Uri.parse(url)
            val port = if (uri.port != -1 && !((uri.scheme.equals("https", true) && uri.port == 443) || (uri.scheme.equals("http", true) && uri.port == 80))) ":${uri.port}" else ""
            val origin = "${uri.scheme!!.lowercase()}://${uri.host!!.lowercase()}$port"
            return WebAppInstance(
                appId = appId,
                name = name,
                mode = DeliveryMode.ONLINE,
                startUrl = url,
                primaryOrigin = origin,
                webProfileName = "app-${appId.replace("-", "")}",
                trustRevision = 1,
                activeReleaseId = null,
                activeDataGeneration = UUID.randomUUID().toString(),
                sourceAdapter = "online",
                sourceSpec = JSONObject().put("url", url).toString(),
                developerEnabled = false,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
