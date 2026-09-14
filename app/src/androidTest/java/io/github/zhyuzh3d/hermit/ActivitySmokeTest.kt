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

    @Test fun publicBridgeReportsRealSystemCapabilityDetails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HermitApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip("Capabilities", 1)), "Capability fixture")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, installed.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val result = evaluateAsync(scenario, "Promise.all([hermit.runtime.info(),hermit.runtime.capabilities(),hermit.speech.availability(),hermit.tts.availability(),hermit.sensors.availability(),hermit.wifi.status(),hermit.bluetooth.status(),hermit.infrared.status(),hermit.battery.status(),hermit.network.status(),hermit.camera.torchStatus()]).then(x=>({minor:x[0].apiMinor,names:x[1].capabilities.map(c=>c.name),speech:typeof x[2].streamingAvailable==='boolean'&&!('services'in x[2])&&!('providerManagedByUser'in x[2]),tts:typeof x[3].operational==='boolean'&&!('services'in x[3])&&!('engines'in x[3])&&!('providerManagedByUser'in x[3]),sensors:Array.isArray(x[4].sensors),wifi:typeof x[5].supported==='boolean',bluetooth:typeof x[6].supported==='boolean',infrared:typeof x[7].supported==='boolean',battery:typeof x[8].charging==='boolean',network:Array.isArray(x[9].transports),torch:typeof x[10].supported==='boolean'}))")
                assertTrue(result?.contains("\"minor\":10") == true)
                for (name in listOf("sensors", "wifi", "bluetooth", "infrared", "battery", "system")) {
                    assertTrue("Missing $name in $result", result?.contains("\"$name\"") == true)
                }
                for (field in listOf("speech", "tts", "sensors", "wifi", "bluetooth", "infrared", "battery", "network", "torch")) {
                    assertTrue("Capability probe failed: $field in $result", result?.contains("\"$field\":true") == true)
                }
            }
        } finally {
            app.registry.deleteInstance(installed.appId)
            app.installer.deleteAppFiles(installed.appId)
            app.registry.finishDelete(installed.appId)
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

    @Test fun onlinePageUsesSameOriginOfflineIconsWithoutRequestingThemFromServer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HermitApplication
        val iconRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(request: IHTTPSession): Response {
                if (request.uri.startsWith("/__hermit/")) iconRequests.incrementAndGet()
                return newFixedLengthResponse("<!doctype html><meta charset=utf-8><title>Online icon fixture</title><i class='fa-solid fa-heart'></i>").apply {
                    addHeader("Content-Security-Policy", "default-src 'self'; style-src 'self'; font-src 'self'")
                }
            }
        }
        server.start()
        val instance = io.github.zhyuzh3d.hermit.model.WebAppInstance.newOnlineLive("Online icon fixture", "http://127.0.0.1:${server.listeningPort}/")
        app.registry.insertInstance(instance)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, instance.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val result = evaluateAsync(scenario, "hermit.icons.load().then(()=>document.fonts.load('900 16px \\\"Font Awesome 7 Free\\\"')).then(fonts=>fonts.length>0)")
                assertEquals("true", result)
                assertEquals(0, iconRequests.get())
            }
        } finally {
            server.stop()
            app.registry.deleteInstance(instance.appId)
            app.registry.finishDelete(instance.appId)
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

    @Test fun storeIconsSearchAndModalBackWorkWithoutFrontendFrameworks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            assertTrue(waitUntilReady(scenario))
            val font = evaluateAsync(scenario, "hermit.icons.load().then(()=>document.fonts.load('900 16px \\\"Font Awesome 7 Free\\\"')).then(fonts=>({loaded:fonts.length>0,family:getComputedStyle(document.querySelector('#addFolder .fa-folder-open')).fontFamily}))")
            assertTrue("Font result: $font", font?.contains("\"loaded\":true") == true)
            assertTrue(font?.contains("Font Awesome 7 Free") == true)
            assertFalse(evaluate(scenario, "getComputedStyle(document.querySelector('.fa-plus'),'::before').content") in setOf("none", "normal", "\"\""))
            evaluate(scenario, "document.querySelector('#addUrl').click(); 'opened'")
            assertEquals("true", evaluate(scenario, "String(document.querySelector('#shell').inert && !document.querySelector('#addPanel').classList.contains('hidden'))"))
            evaluate(scenario, "String(window.hermitStoreBack())")
            repeat(10) { if (evaluate(scenario, "String(document.querySelector('#addPanel').classList.contains('hidden'))") != "true") android.os.SystemClock.sleep(100) }
            assertEquals("false", evaluate(scenario, "String(document.querySelector('#shell').inert)"))
            evaluate(scenario, "document.querySelector('[data-view=icons]').click(); 'icons'")
            repeat(20) { if (evaluate(scenario, "String(!!window.hermitIconCatalog)") != "true") android.os.SystemClock.sleep(100) }
            assertEquals("2883", evaluate(scenario, "String(window.hermitIconCatalog.icons.length)"))
            evaluate(scenario, "const q=document.querySelector('#searchIcons'); q.value='相机'; q.dispatchEvent(new Event('input')); 'searched'")
            assertEquals("true", evaluate(scenario, "String(document.querySelectorAll('.catalog-icon').length>0 && [...document.querySelectorAll('.catalog-icon')].some(x=>x.textContent.includes('camera')))"))
            evaluate(scenario, "document.querySelector('[data-view=settings]').click();document.querySelector('[data-theme-choice=dark]').click(); 'dark'")
            assertEquals("dark", evaluate(scenario, "document.documentElement.dataset.theme"))
            evaluate(scenario, "document.querySelector('[data-theme-choice=system]').click(); 'reset'")
        }
    }

    @Test fun nativeHtmlLocalPageGetsAllFontStylesWithoutLinkOrBuild() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HermitApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip("Icons", 1)), "Icon fixture")
        try {
            assertEquals("Icon fixture", app.registry.getInstance(installed.appId)?.name)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, installed.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val result = evaluateAsync(scenario, "hermit.icons.load().then(()=>Promise.all([document.fonts.load('900 16px \\\"Font Awesome 7 Free\\\"'),document.fonts.load('400 16px \\\"Font Awesome 7 Free\\\"'),document.fonts.load('400 16px \\\"Font Awesome 7 Brands\\\"')])).then(xs=>xs.map(x=>x.length>0))")
                assertEquals("[true,true,true]", result)
                val styles = evaluate(scenario, "const icon=hermit.icons.create('heart',{style:'regular',label:'喜欢'});document.body.append(icon);JSON.stringify({family:getComputedStyle(icon).fontFamily,label:icon.getAttribute('aria-label')})")
                assertTrue(styles?.contains("Font Awesome 7 Free") == true)
                assertTrue(styles?.contains("喜欢") == true)
            }
            // Cancel a deletion in the Store and prove the instance/data identity survives.
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                repeat(20) { if (evaluate(scenario, "String(document.querySelectorAll('.manage').length)") == "0") android.os.SystemClock.sleep(100) }
                evaluate(scenario, "[...document.querySelectorAll('.app-card')].find(x=>x.textContent.includes('Icon fixture')).querySelector('.manage').click(); 'manage'")
                evaluate(scenario, "document.querySelector('#removeApp').click(); 'confirm'")
                assertEquals("false", evaluate(scenario, "String(document.querySelector('#confirmPanel').classList.contains('hidden'))"))
                evaluate(scenario, "document.querySelector('#cancelConfirm').click(); 'cancelled'")
                assertTrue(app.registry.getInstance(installed.appId) != null)
                assertEquals("true", evaluate(scenario, "String(document.querySelector('#confirmPanel').classList.contains('hidden'))"))
            }
        } finally {
            app.registry.deleteInstance(installed.appId)
            app.installer.deleteAppFiles(installed.appId)
            app.registry.finishDelete(installed.appId)
        }
    }

    @Test fun developmentTabShowsAndRotatesOnlyTheCurrentPassword() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HermitApplication
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            assertTrue(waitUntilReady(scenario))
            evaluate(scenario, "document.querySelector('[data-view=development]').click();'opened'")
            for (i in 0 until 30) {
                if (evaluate(scenario, "String(/^[0-9]{6}$/.test(document.querySelector('#agentPassword').value))") == "true") break
                android.os.SystemClock.sleep(100)
            }
            assertEquals("true", evaluate(scenario, "String(/^[0-9]{6}$/.test(document.querySelector('#agentPassword').value))"))
            val previous = app.agentServer.passwordForUi()
            val replacement = if (previous == "654321") "123456" else "654321"
            evaluate(scenario, "const p=document.querySelector('#agentPassword');p.value='$replacement';p.dispatchEvent(new Event('input'));document.querySelector('#saveAgentPassword').click();'rotate'")
            for (i in 0 until 20) { if (previous != app.agentServer.passwordForUi()) break; android.os.SystemClock.sleep(100) }
            assertEquals(replacement, app.agentServer.passwordForUi())
            assertEquals("true", evaluateAsync(scenario, "hermit.host.agent.status().then(s=>s.password===document.querySelector('#agentPassword').value)"))
        }
    }

    @Test fun favoritesAndFiveTabNavigationStayAlignedAndConsistent() {
        val app = ApplicationProvider.getApplicationContext<HermitApplication>()
        val instance = io.github.zhyuzh3d.hermit.model.WebAppInstance.newOnlineLive("Favorite fixture", "https://example.com")
        app.registry.insertInstance(instance)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                repeat(20) {
                    if (evaluate(scenario, "String(!!document.querySelector(\"[data-app-id='${instance.appId}']\"))") == "true") return@repeat
                    android.os.SystemClock.sleep(100)
                }
                assertEquals("true", evaluate(scenario, "String(document.querySelector(\"[data-app-id='${instance.appId}']\").classList.contains('hidden'))"))
                evaluate(scenario, "document.querySelector('.bottom-nav [data-view=all]').click();'all'")
                assertEquals("false", evaluate(scenario, "String(document.querySelector(\"[data-app-id='${instance.appId}']\").classList.contains('hidden'))"))
                evaluate(scenario, "document.querySelector(\"[data-app-id='${instance.appId}'] .favorite\").click();'favorite'")
                repeat(20) { if (app.registry.getInstance(instance.appId)?.favorite != true) android.os.SystemClock.sleep(100) }
                assertTrue(app.registry.getInstance(instance.appId)?.favorite == true)
                evaluate(scenario, "document.querySelector('.bottom-nav [data-view=favorites]').click();'favorites'")
                assertEquals("false", evaluate(scenario, "String(document.querySelector(\"[data-app-id='${instance.appId}']\").classList.contains('hidden'))"))
                val geometry = evaluate(scenario, "JSON.stringify((()=>{const n=[...document.querySelectorAll('.bottom-nav button')];return {count:n.length,widths:n.map(x=>Math.round(x.getBoundingClientRect().width)),offsets:n.map(x=>{const a=x.getBoundingClientRect(),b=x.querySelector('.nav-icon').getBoundingClientRect();return Math.round((a.left+a.width/2)-(b.left+b.width/2))})}})())")
                assertTrue(geometry?.contains("\"count\":5") == true)
                assertTrue(geometry?.contains("\"offsets\":[0,0,0,0,0]") == true)
                assertEquals("\"10knet·zhyuzh3d\"", evaluateAsync(scenario, "hermit.host.about.info({}).then(x=>x.author)"))
                evaluate(scenario, "window.hermitShellUnavailable('offline test');'fallback'")
                assertEquals("offline test", evaluate(scenario, "document.querySelector('#noticeText').textContent"))
            }
        } finally {
            app.registry.deleteInstance(instance.appId)
            app.registry.finishDelete(instance.appId)
        }
    }

    @Test fun switchingDeveloperModesAndPasswordResetCloseLegacyListener() {
        val app = ApplicationProvider.getApplicationContext<HermitApplication>()
        val installed = runBlocking { app.installer.installZip(ByteArrayInputStream(appZip("Mode switch", 1)), "Mode switch") }
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val address = app.agentServer.addresses().firstOrNull()
                org.junit.Assume.assumeTrue(address != null)
                app.developmentServer.start(installed.appId, "adb")
                assertEquals("true", evaluateAsync(scenario, "hermit.host.agent.start({address:${JSONObject.quote(address)}}).then(x=>x.active)"))
                assertFalse(app.developmentServer.status().optBoolean("active"))
                assertTrue(app.agentServer.status().optBoolean("active"))
                evaluateAsync(scenario, "hermit.host.deploy.start({appId:${JSONObject.quote(installed.appId)},mode:'adb'}).then(()=>true)")
                assertFalse(app.agentServer.status().optBoolean("active"))
                assertTrue(app.developmentServer.status().optBoolean("active"))
                assertEquals("true", evaluateAsync(scenario, "hermit.host.agent.resetPassword().then(()=>true)"))
                assertFalse(app.developmentServer.status().optBoolean("active"))
            }
        } finally {
            app.agentServer.stop("Test finished"); app.developmentServer.stop("Test finished")
            app.registry.deleteInstance(installed.appId); app.installer.deleteAppFiles(installed.appId); app.registry.finishDelete(installed.appId)
        }
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
