package io.github.zhyuzh3d.hermit

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.core.content.FileProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONTokener
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@LargeTest
class ActivitySmokeTest {
    @Test fun nativeHostStartsWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }

    @Test fun storeLoadsInjectedBridgeAndCompletesHandshake() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var state: JSONObject? = null
            repeat(12) {
                val latch = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.inspectRuntimeForTest { encoded ->
                        if (encoded != null && encoded != "null") {
                            val jsonText = JSONTokener(encoded).nextValue() as? String
                            if (jsonText != null) state = JSONObject(jsonText)
                        }
                        latch.countDown()
                    }
                }
                latch.await(2, TimeUnit.SECONDS)
                if (state?.optBoolean("ready") == true) return@repeat
                android.os.SystemClock.sleep(250)
            }
            assertEquals("Hermit 应用库", state?.optString("title"))
            assertTrue(state?.optBoolean("hermit") == true)
            assertTrue(state?.optBoolean("ready") == true)
            val licenses = evaluateAsync(scenario, "hermit.host.licenses.info({}).then(x=>({hasNano:x.text.includes('NanoHTTPD')}))")
            assertEquals("{\"hasNano\":true}", licenses)
        }
    }

    @Test fun sharedZipIsCopiedIntoANewLocalInstance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HermitApplication
        val before = app.registry.listInstances().map { it.appId }.toSet()
        val source = File(context.cacheDir, "shared/shared-${UUID.randomUUID()}.zip").apply {
            parentFile!!.mkdirs()
            writeBytes(appZip("Shared ZIP", 1))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
        var createdId: String? = null
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.importSharedZipForTest(uri) }
                repeat(30) {
                    createdId = app.registry.listInstances().firstOrNull { candidate -> candidate.appId !in before }?.appId
                    if (createdId != null) return@repeat
                    android.os.SystemClock.sleep(150)
                }
                assertTrue(createdId != null)
                assertTrue(app.registry.getInstance(createdId!!)?.activeReleaseId != null)
            }
        } finally {
            createdId?.let { id ->
                app.registry.deleteInstance(id)
                app.installer.deleteAppFiles(id)
                app.registry.finishDelete(id)
            }
            source.delete()
        }
    }

    @Test fun localAppBridgePersistsDataAcrossCodeUpdate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HermitApplication
        val first = app.installer.installZip(ByteArrayInputStream(appZip("V1", 1)), "Bridge fixture")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, first.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val value = evaluateAsync(scenario, "hermit.data.put({collection:'test',key:'answer',value:{n:42},expectedRevision:'absent'})")
                assertTrue(value?.contains("revision") == true)
            }
            val second = app.installer.installZip(ByteArrayInputStream(appZip("V2", 2)), null, first.appId,
                expectedReleaseId = first.releaseId)
            assertFalse(first.releaseId == second.releaseId)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, first.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val value = evaluateAsync(scenario, "hermit.data.get({collection:'test',key:'answer'}).then(x=>x.value)")
                assertEquals("{\"n\":42}", value)
            }
        } finally {
            val removed = app.registry.deleteInstance(first.appId)
            app.installer.deleteAppFiles(first.appId)
            app.registry.finishDelete(first.appId)
            removed?.let { app.registry.completeProfileCleanup(it.webProfileName) }
        }
    }

    private fun waitUntilReady(scenario: ActivityScenario<MainActivity>): Boolean {
        repeat(20) {
            val value = evaluate(scenario, "String(!!(window.hermit&&window.hermit.isReady))")
            if (value == "true") return true
            android.os.SystemClock.sleep(200)
        }
        return false
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String? {
        val latch = CountDownLatch(1)
        var decoded: String? = null
        scenario.onActivity { activity -> activity.evaluateForTest(script) { encoded ->
            decoded = if (encoded == null || encoded == "null") null else JSONTokener(encoded).nextValue()?.toString()
            latch.countDown()
        } }
        latch.await(3, TimeUnit.SECONDS)
        return decoded
    }

    private fun evaluateAsync(scenario: ActivityScenario<MainActivity>, promiseExpression: String): String? {
        evaluate(scenario, "window.__hermitTestResult=null; ($promiseExpression).then(x=>window.__hermitTestResult=JSON.stringify(x)).catch(e=>window.__hermitTestResult='ERR:'+e.code); 'started'")
        repeat(20) {
            val value = evaluate(scenario, "window.__hermitTestResult")
            if (value != null) return value
            android.os.SystemClock.sleep(150)
        }
        return null
    }

    private fun appZip(label: String, version: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entries = mapOf(
                "index.html" to "<!doctype html><meta charset=utf-8><title>$label</title><h1>$label</h1>",
                "hermit.json" to "{\"schema\":1,\"name\":\"Bridge fixture\",\"version\":{\"code\":$version,\"name\":\"$version\"}}",
            )
            entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        return bytes.toByteArray()
    }
}
