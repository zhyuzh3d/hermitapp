package io.github.zhyuzh3d.hermit

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zhyuzh3d.hermit.data.FileStore
import io.github.zhyuzh3d.hermit.data.RecordsStore
import io.github.zhyuzh3d.hermit.backup.BackupCoordinator
import io.github.zhyuzh3d.hermit.install.InstallCoordinator
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HermitException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import io.github.zhyuzh3d.hermit.capability.NativeHttpClient
import io.github.zhyuzh3d.hermit.runtime.LocalContentGateway
import android.net.Uri
import android.webkit.WebResourceRequest
import io.github.zhyuzh3d.hermit.model.WebAppInstance

@RunWith(AndroidJUnit4::class)
class PersistenceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val createdApps = mutableListOf<String>()

    @After fun cleanup() {
        val app = context.applicationContext as HermitApplication
        createdApps.forEach { id ->
            java.io.File(context.filesDir, "instances/$id").deleteRecursively()
            app.registry.deleteInstance(id)?.let { deleted ->
                app.registry.finishDelete(id)
                app.registry.completeProfileCleanup(deleted.webProfileName)
            }
            app.registry.pendingProfileCleanup().filter { it.second == id }
                .forEach { app.registry.completeProfileCleanup(it.first) }
        }
    }

    @Test fun recordsProvideCasAtomicBatchAndStableExport() {
        val store = RecordsStore(context)
        val appId = UUID.randomUUID().toString()
        val generation = UUID.randomUUID().toString()
        createdApps += appId
        val first = store.put(appId, generation, "notes", "a", JSONObject().put("text", "one"), "absent")
        assertEquals("one", store.get(appId, generation, "notes", "a")!!.getJSONObject("value").getString("text"))
        val conflict = assertThrows(HermitException::class.java) {
            store.put(appId, generation, "notes", "a", "two", "absent")
        }
        assertEquals(ErrorCodes.CONFLICT, conflict.code)
        val operations = JSONArray()
            .put(JSONObject().put("op", "put").put("collection", "notes").put("key", "a").put("value", "two").put("expectedRevision", first.getString("revision")))
            .put(JSONObject().put("op", "put").put("collection", "notes").put("key", "b").put("value", "three").put("expectedRevision", "absent"))
        store.batch(appId, generation, operations)
        val exported = ByteArrayOutputStream()
        assertEquals(2, store.exportJsonLines(appId, generation, exported))
        val secondGeneration = UUID.randomUUID().toString()
        assertEquals(2, store.importJsonLines(appId, secondGeneration, ByteArrayInputStream(exported.toByteArray())))
        assertEquals("three", store.get(appId, secondGeneration, "notes", "b")!!.getString("value"))
    }

    @Test fun logicalFilesRoundTripWithoutExternalPathDependency() {
        val store = FileStore(context)
        val appId = UUID.randomUUID().toString()
        val generation = UUID.randomUUID().toString()
        createdApps += appId
        val saved = store.import(appId, generation, "hello".byteInputStream(), "hello.txt", "text/plain")
        assertEquals("hello", store.readText(appId, generation, saved.getString("logicalFileId")).getString("text"))
    }

    @Test fun onlineOriginChangeRotatesTrustAndWebProfileButPathChangeDoesNot() {
        val app = context.applicationContext as HermitApplication
        val instance = WebAppInstance.newOnline("Online fixture", "https://example.test/one")
        app.registry.insertInstance(instance)
        createdApps += instance.appId

        app.registry.updateInstance(instance.appId, instance.name, "https://example.test/two", "https://example.test", null)
        val sameOrigin = app.registry.getInstance(instance.appId)!!
        assertEquals(instance.trustRevision, sameOrigin.trustRevision)
        assertEquals(instance.webProfileName, sameOrigin.webProfileName)

        app.registry.updateInstance(instance.appId, instance.name, "https://other.test/", "https://other.test", null)
        val changed = app.registry.getInstance(instance.appId)!!
        assertEquals(instance.trustRevision + 1, changed.trustRevision)
        assertFalse(instance.webProfileName == changed.webProfileName)
        assertTrue(app.registry.pendingProfileCleanup().any { it.first == instance.webProfileName })

        app.installer.recoverStorage()
        assertNotNull(app.registry.getInstance(instance.appId))
    }

    @Test fun systemPermissionObservationsPersistLatestKnownState() {
        val registry = (context.applicationContext as HermitApplication).registry
        val permission = "io.github.zhyuzh3d.hermit.TEST_PERMISSION"
        registry.observeSystemPermission(permission, false)
        registry.observeSystemPermission(permission, true)
        val item = registry.systemPermissionsJson().let { items ->
            (0 until items.length()).map(items::getJSONObject).first { it.getString("permission") == permission }
        }
        assertTrue(item.getBoolean("granted"))
        assertTrue(item.getLong("observedAt") > 0)
    }

    @Test fun restartRecoveryKeepsOnlyCommittedStorageAndTerminatesOperations() {
        val app = context.applicationContext as HermitApplication
        val installed = runBlocking { app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "ok"))), "Recovery fixture") }
        createdApps += installed.appId
        val instance = app.registry.getInstance(installed.appId)!!
        val appRoot = java.io.File(context.filesDir, "instances/${installed.appId}")
        val incoming = java.io.File(appRoot, "incoming/stale.zip").apply { parentFile!!.mkdirs(); writeText("partial") }
        val orphanRelease = java.io.File(appRoot, "releases/${UUID.randomUUID()}/web").apply { mkdirs(); resolve("index.html").writeText("orphan") }
        val orphanData = java.io.File(appRoot, "data/${UUID.randomUUID()}").apply { mkdirs(); resolve("partial").writeText("orphan") }
        val operation = app.registry.createOperation(installed.appId, "install-release", "recovery-operation", "abc", installed.releaseId)
        app.registry.updateOperation(operation, "committing")
        app.registry.recoverInterruptedOperations()
        app.installer.recoverStorage()
        assertFalse(incoming.exists())
        assertFalse(orphanRelease.parentFile!!.exists())
        assertFalse(orphanData.exists())
        assertTrue(java.io.File(appRoot, "releases/${installed.releaseId}/web/index.html").isFile)
        assertEquals(instance.activeDataGeneration, app.registry.getInstance(installed.appId)!!.activeDataGeneration)
        assertEquals("failed", app.registry.operationJson(operation)!!.getString("state"))
    }

    @Test fun packageInstallCommitsValidZipAndRejectsTraversal() = runBlocking {
        val registry = (context.applicationContext as HermitApplication).registry
        val installer = InstallCoordinator(context, registry)
        val valid = zipOf(mapOf("index.html" to "<h1>ok</h1>", "hermit.json" to "{\"schema\":1,\"name\":\"Test\"}"))
        val result = installer.installZip(ByteArrayInputStream(valid), null)
        createdApps += result.appId
        assertNotNull(registry.getInstance(result.appId)?.activeReleaseId)
        val updatedBytes = zipOf(mapOf(
            "start.html" to "<h1>v2</h1>",
            "hermit.json" to "{\"schema\":1,\"entry\":\"start.html\"}",
        ))
        val updated = installer.installZip(ByteArrayInputStream(updatedBytes), null, result.appId,
            idempotencyKey = "install-idempotency-v2", expectedReleaseId = result.releaseId)
        assertTrue(registry.getInstance(result.appId)!!.startUrl.endsWith("/start.html"))
        val replayed = installer.installZip(ByteArrayInputStream(updatedBytes), null, result.appId,
            idempotencyKey = "install-idempotency-v2", expectedReleaseId = result.releaseId)
        assertEquals(updated.releaseId, replayed.releaseId)
        val reactivated = installer.installZip(ByteArrayInputStream(valid), null, result.appId,
            expectedReleaseId = updated.releaseId)
        assertEquals(result.releaseId, reactivated.releaseId)
        assertEquals(result.releaseId, registry.getInstance(result.appId)?.activeReleaseId)
        assertTrue(registry.getInstance(result.appId)!!.startUrl.endsWith("/index.html"))
        val invalid = zipOf(mapOf("../escape.txt" to "bad"))
        val error = assertThrows(HermitException::class.java) { runBlocking { installer.installZip(ByteArrayInputStream(invalid), null) } }
        assertEquals(ErrorCodes.INVALID_ARGUMENT, error.code)
        assertTrue(!java.io.File(context.filesDir.parentFile, "escape.txt").exists())
    }

    @Test fun treeHashEncodesFileBoundariesUnambiguously() = runBlocking {
        val app = context.applicationContext as HermitApplication
        val one = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf(
            "a" to "xb\u0000y",
            "index.html" to "ok",
        ))), "Tree hash fixture")
        createdApps += one.appId
        val two = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf(
            "a" to "x",
            "b" to "y",
            "index.html" to "ok",
        ))), null, one.appId, expectedReleaseId = one.releaseId)
        assertFalse(one.treeHash == two.treeHash)
        assertFalse(one.releaseId == two.releaseId)
    }

    @Test fun interruptedPackageTransferDoesNotLeaveIncomingArchive() {
        val app = context.applicationContext as HermitApplication
        val instances = File(context.filesDir, "instances")
        val before = instances.walkTopDown().filter { it.isFile && it.parentFile?.name == "incoming" }.map { it.path }.toSet()
        val rootsBefore = instances.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()
        val broken = object : InputStream() {
            private var count = 0
            override fun read(): Int {
                if (count++ < 32) return 'x'.code
                throw IOException("test interruption")
            }
        }
        val error = assertThrows(HermitException::class.java) {
            runBlocking { app.installer.installZip(broken, "Broken") }
        }
        assertEquals(ErrorCodes.STORAGE, error.code)
        val after = instances.walkTopDown().filter { it.isFile && it.parentFile?.name == "incoming" }.map { it.path }.toSet()
        assertEquals(before, after)
        assertEquals(rootsBefore, instances.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty())
    }

    @Test fun logicalBackupRestoresCodeRecordsAndFileIds() = runBlocking {
        val app = context.applicationContext as HermitApplication
        val records = RecordsStore(context)
        val fileStore = FileStore(context)
        val installed = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf(
            "index.html" to "<h1>backup</h1>",
            "hermit.json" to "{\"schema\":1,\"name\":\"Backup fixture\"}",
        ))), null)
        createdApps += installed.appId
        val instance = app.registry.getInstance(installed.appId)!!
        records.put(installed.appId, instance.activeDataGeneration, "notes", "one", JSONObject().put("text", "kept"), "absent")
        val originalFile = fileStore.import(installed.appId, instance.activeDataGeneration, "attachment".byteInputStream(), "a.txt", "text/plain")
        val share = java.io.File(context.filesDir, "shared/test-${UUID.randomUUID()}.zip").apply { parentFile!!.mkdirs(); createNewFile() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", share)
        val coordinator = BackupCoordinator(context, app.registry, app.installer, records, fileStore)
        coordinator.export(installed.appId, uri)
        val restoredResult = coordinator.restore(uri)
        val restoredId = restoredResult.getString("appId")
        createdApps += restoredId
        val restored = app.registry.getInstance(restoredId)!!
        assertEquals("kept", records.get(restoredId, restored.activeDataGeneration, "notes", "one")!!.getJSONObject("value").getString("text"))
        assertEquals("attachment", fileStore.readText(restoredId, restored.activeDataGeneration, originalFile.getString("logicalFileId")).getString("text"))

        val targetInstall = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "<h1>target code</h1>"))), "Restore target")
        createdApps += targetInstall.appId
        val targetBefore = app.registry.getInstance(targetInstall.appId)!!
        records.put(targetInstall.appId, targetBefore.activeDataGeneration, "notes", "old", "replace me", "absent")
        app.registry.putGrant(targetInstall.appId, targetBefore.trustRevision, "speech", "allow")
        coordinator.restoreData(uri, targetInstall.appId)
        val targetAfter = app.registry.getInstance(targetInstall.appId)!!
        assertEquals(targetInstall.appId, targetAfter.appId)
        assertEquals(targetInstall.releaseId, targetAfter.activeReleaseId)
        assertFalse(targetBefore.activeDataGeneration == targetAfter.activeDataGeneration)
        assertEquals(targetBefore.trustRevision + 1, targetAfter.trustRevision)
        assertEquals(null, app.registry.getGrant(targetAfter.appId, targetAfter.trustRevision, "speech"))
        assertEquals(null, records.get(targetAfter.appId, targetAfter.activeDataGeneration, "notes", "old"))
        assertEquals("kept", records.get(targetAfter.appId, targetAfter.activeDataGeneration, "notes", "one")!!.getJSONObject("value").getString("text"))
        assertEquals("attachment", fileStore.readText(targetAfter.appId, targetAfter.activeDataGeneration, originalFile.getString("logicalFileId")).getString("text"))
        share.delete()
        Unit
    }

    @Test fun loopbackDeployIsTargetBoundAuthenticatedAndTransactional() = runBlocking {
        val app = context.applicationContext as HermitApplication
        val first = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "v1"))), "Deploy fixture")
        createdApps += first.appId
        var reloadedAppId: String? = null
        app.developmentServer.setReloadHandler { appId -> reloadedAppId = appId; true }
        val session = app.developmentServer.start(first.appId)
        try {
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(session.getString("address") + "/v1/status").build()).execute().use {
                assertEquals(401, it.code)
            }
            val next = zipOf(mapOf("index.html" to "v2"))
            val sha = MessageDigest.getInstance("SHA-256").digest(next).joinToString("") { "%02x".format(it) }
            val request = Request.Builder().url(session.getString("address") + "/v1/apps/${first.appId}/release")
                .header("Authorization", "Bearer ${session.getString("token")}")
                .header("Idempotency-Key", "instrumented-deploy-v2")
                .header("X-Hermit-Expected-Release", first.releaseId)
                .header("X-Hermit-Content-SHA256", sha)
                .put(next.toRequestBody("application/zip".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                assertEquals(response.body.string(), 200, response.code)
            }
            assertFalse(first.releaseId == app.registry.getInstance(first.appId)!!.activeReleaseId)
            val reload = Request.Builder().url(session.getString("address") + "/v1/apps/${first.appId}/reload")
                .header("Authorization", "Bearer ${session.getString("token")}")
                .post(ByteArray(0).toRequestBody(null)).build()
            client.newCall(reload).execute().use { response -> assertEquals(response.body.string(), 202, response.code) }
            assertEquals(first.appId, reloadedAppId)
        } finally {
            app.developmentServer.setReloadHandler(null)
            app.developmentServer.stop("test complete")
        }
    }

    @Test fun lanDeployUsesEphemeralTlsAndSpkiPin() = runBlocking {
        val app = context.applicationContext as HermitApplication
        val installed = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "tls"))), "TLS fixture")
        createdApps += installed.appId
        val session = app.developmentServer.start(installed.appId, "lan")
        try {
            val observed = arrayOfNulls<X509Certificate>(1)
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    observed[0] = chain.firstOrNull()
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
            val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
            val request = Request.Builder().url(session.getString("address") + "/v1/status")
                .header("Authorization", "Bearer ${session.getString("token")}").build()
            client.newCall(request).execute().use {
                assertEquals(200, it.code)
                val certificate = requireNotNull(observed[0])
                val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
                val actualPin = "sha256//" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
                assertEquals(session.getString("spkiPin"), actualPin)
            }
        } finally { app.developmentServer.stop("test complete") }
    }

    @Test fun nativeHttpRejectsLoopbackBeforeAuthorization() {
        var authorized = false
        val error = assertThrows(HermitException::class.java) { runBlocking {
            NativeHttpClient(FileStore(context)).request(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                JSONObject().put("url", "http://127.0.0.1:9999/")) { _, _ -> authorized = true }
        } }
        assertEquals(ErrorCodes.ORIGIN_DENIED, error.code)
        assertFalse(authorized)
    }

    @Test fun nativeHttpSeparatesPublicAndPrivateOriginGrants() {
        val publicTarget = NativeHttpClient.AuthorizationTarget("https://example.test", "public")
        val privateTarget = NativeHttpClient.AuthorizationTarget("https://example.test", "private")
        assertEquals("public:https://example.test", publicTarget.grantScope)
        assertEquals("private:https://example.test", privateTarget.grantScope)
        assertFalse(publicTarget.grantScope == privateTarget.grantScope)
    }

    @Test fun nativeHttpRejectsIpv4MappedLoopback() {
        var authorized = false
        val error = assertThrows(HermitException::class.java) { runBlocking {
            NativeHttpClient(FileStore(context)).request(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                JSONObject().put("url", "http://[::ffff:127.0.0.1]:9999/")) { _, _ -> authorized = true }
        } }
        assertEquals(ErrorCodes.ORIGIN_DENIED, error.code)
        assertFalse(authorized)
    }

    @Test fun localGatewayProvidesMimeRangeAndServiceWorkerSuppression() {
        val root = java.io.File(context.cacheDir, "gateway-${UUID.randomUUID()}").apply { mkdirs() }
        java.io.File(root, "index.html").writeText("home")
        java.io.File(root, "asset.js").writeText("0123456789")
        try {
            val gateway = LocalContentGateway.forRelease(context, "fixture.apps.hermit.invalid", root, false)
            val full = gateway.intercept(request("https://fixture.apps.hermit.invalid/asset.js"))!!
            assertEquals(200, full.statusCode)
            assertEquals("text/javascript", full.mimeType)
            assertEquals("https://sw-disabled.hermit.invalid/", full.responseHeaders["Service-Worker-Allowed"])
            assertEquals("0123456789", full.data.reader().readText())
            val partial = gateway.intercept(request("https://fixture.apps.hermit.invalid/asset.js", mapOf("Range" to "bytes=2-5")))!!
            assertEquals(206, partial.statusCode)
            assertEquals("2345", partial.data.reader().readText())
            assertEquals(404, gateway.intercept(request("https://fixture.apps.hermit.invalid/missing.js"))!!.statusCode)
        } finally { root.deleteRecursively() }
    }

    private fun request(value: String, headers: Map<String, String> = emptyMap()) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(value)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = headers
    }

    private fun zipOf(files: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> files.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
        } }
        return bytes.toByteArray()
    }
}
