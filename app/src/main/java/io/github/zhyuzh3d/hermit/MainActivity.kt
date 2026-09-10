package io.github.zhyuzh3d.hermit

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.setPadding
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.zhyuzh3d.hermit.bridge.BridgeController
import io.github.zhyuzh3d.hermit.bridge.BridgeHost
import io.github.zhyuzh3d.hermit.backup.BackupCoordinator
import io.github.zhyuzh3d.hermit.capability.TtsController
import io.github.zhyuzh3d.hermit.capability.PermissionBroker
import io.github.zhyuzh3d.hermit.capability.SpeechController
import io.github.zhyuzh3d.hermit.capability.LocationController
import io.github.zhyuzh3d.hermit.capability.NativeHttpClient
import io.github.zhyuzh3d.hermit.data.FileStore
import io.github.zhyuzh3d.hermit.data.RecordsStore
import io.github.zhyuzh3d.hermit.launcher.ShortcutHost
import io.github.zhyuzh3d.hermit.model.DeliveryMode
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HermitException
import io.github.zhyuzh3d.hermit.model.WebAppInstance
import io.github.zhyuzh3d.hermit.runtime.LocalContentGateway
import io.github.zhyuzh3d.hermit.runtime.RuntimeRole
import io.github.zhyuzh3d.hermit.runtime.RuntimeSession
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class MainActivity : ComponentActivity(), BridgeHost {
    private val hermitApp get() = application as HermitApplication
    private val records by lazy { RecordsStore(this) }
    private val files by lazy { FileStore(this) }
    private val shortcuts by lazy { ShortcutHost(this) }
    private val tts by lazy { TtsController(this) }
    private val speech by lazy { SpeechController(this) }
    private val permissionBroker by lazy { PermissionBroker(this, hermitApp.registry, ::requestPermissions) }
    private val location by lazy { LocationController(this) }
    private val nativeHttp by lazy { NativeHttpClient(files) }
    private val backup by lazy { BackupCoordinator(this, hermitApp.registry, hermitApp.installer, records, files) }
    private lateinit var root: android.widget.FrameLayout
    private var webView: WebView? = null
    private var session: RuntimeSession? = null
    private var bridge: BridgeController? = null
    @Volatile private var visibleAppId: String? = null
    private var launchedFromLibrary = false
    private var pendingZip: CancellableContinuation<Uri?>? = null
    private var pendingTree: CancellableContinuation<Uri?>? = null
    private var pendingFile: CancellableContinuation<Uri?>? = null
    private var pendingFileExport: CancellableContinuation<Uri?>? = null
    private var pendingPermissions: CancellableContinuation<Map<String, Boolean>>? = null
    private var pendingCamera: CancellableContinuation<Boolean>? = null
    private var pendingCameraFile: File? = null
    private var pendingBackupExport: CancellableContinuation<Uri?>? = null
    private var pendingBackupImport: CancellableContinuation<Uri?>? = null
    private val rebuilding = AtomicBoolean(false)
    private var lastHapticAt = 0L

    private val zipPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingZip?.let { continuation ->
            pendingZip = null
            if (continuation.isActive) continuation.resume(uri)
        }
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingFile?.let { continuation ->
            pendingFile = null
            if (continuation.isActive) continuation.resume(uri)
        }
    }
    private val fileExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        pendingFileExport?.let { continuation ->
            pendingFileExport = null
            if (continuation.isActive) continuation.resume(uri)
        }
    }
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pendingTree?.let { continuation ->
            pendingTree = null
            if (continuation.isActive) continuation.resume(uri)
        }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pendingPermissions?.let { continuation ->
            pendingPermissions = null
            if (continuation.isActive) continuation.resume(result)
        }
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingCamera?.let { continuation ->
            pendingCamera = null
            if (continuation.isActive) continuation.resume(success)
        }
    }
    private val backupExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        pendingBackupExport?.let { continuation ->
            pendingBackupExport = null
            if (continuation.isActive) continuation.resume(uri)
        }
    }
    private val backupImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingBackupImport?.let { continuation ->
            pendingBackupImport = null
            if (continuation.isActive) continuation.resume(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = android.widget.FrameLayout(this)
        setContentView(root)
        hermitApp.developmentServer.setReloadHandler { appId ->
            if (visibleAppId != appId) false else {
                root.post { if (visibleAppId == appId) showTarget(appId, launchedFromLibrary) }
                true
            }
        }
        processPendingProfileCleanup()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEBVIEW_DEBUGGING)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = webView
                if (current != null && current.canGoBack()) current.goBack()
                else if (launchedFromLibrary && session?.role == RuntimeRole.WEB_APP) showTarget(null, false)
                else finish()
            }
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val sharedUri = if (intent.action == Intent.ACTION_SEND) sharedStream(intent) else null
        if (sharedUri != null) {
            intent.action = null
            intent.removeExtra(Intent.EXTRA_STREAM)
            importSharedZip(sharedUri)
            return
        }
        val shared = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if (!shared.isNullOrBlank()) {
            intent.action = null
            intent.removeExtra(Intent.EXTRA_TEXT)
            showTarget(null, false)
            root.postDelayed({ promptSharedUrl(shared) }, 350)
            return
        }
        showTarget(intent.getStringExtra(EXTRA_APP_ID), false)
    }

    @Suppress("DEPRECATION")
    private fun sharedStream(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun importSharedZip(uri: Uri) {
        showTarget(null, false)
        lifecycleScope.launch {
            try {
                val result = hermitApp.installer.installUri(uri, null)
                Toast.makeText(this@MainActivity, "ZIP 副本已导入", Toast.LENGTH_LONG).show()
                showTarget(result.appId, true)
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, error.message ?: "ZIP 导入失败", Toast.LENGTH_LONG).show()
                showTarget(null, false)
            }
        }
    }

    private fun showTarget(appId: String?, fromLibrary: Boolean) {
        launchedFromLibrary = fromLibrary
        val instance = appId?.let(hermitApp.registry::getInstance)
        if (appId != null && instance == null) {
            showNativeError("页面应用不存在", "这个桌面入口已经失效。", true)
            return
        }
        if (!runtimeFeaturesAvailable()) {
            showNativeError(getString(R.string.incompatible_title), getString(R.string.incompatible_message), appId != null)
            return
        }
        if (instance != null && instance.mode == DeliveryMode.ONLINE && isLanUrl(instance.startUrl)) {
            lifecycleScope.launch {
                if (ensureLanPermission()) createWebRuntime(instance) else {
                    showNativeError("需要局域网权限", "Android 已阻止访问此局域网页面。你可以重试或在系统设置中授权。", true)
                }
            }
        } else {
            createWebRuntime(instance)
        }
    }

    @SuppressLint("RequiresFeature", "MissingOnRenderProcessGone")
    private fun createWebRuntime(instance: WebAppInstance?) {
        destroyRuntime()
        val role = if (instance == null) RuntimeRole.STORE else RuntimeRole.WEB_APP
        val origin = instance?.primaryOrigin ?: STORE_ORIGIN
        val profileName = instance?.webProfileName ?: STORE_PROFILE
        val release = instance?.activeReleaseId?.let(hermitApp.registry::getRelease)
        if (instance?.mode == DeliveryMode.LOCAL && release == null) {
            showNativeError("本地代码不可用", "活动版本不存在或已损坏。请从应用库重新导入。", true)
            return
        }
        val currentSession = RuntimeSession(role, instance, release, origin, profileName)
        release?.releaseId?.let(hermitApp.installer::acquireRelease)
        val gateway = if (instance == null) {
            LocalContentGateway.forStore(this)
        } else if (release != null) {
            val webRoot = hermitApp.installer.releaseWebRoot(release)
            val history = runCatching { JSONObject(java.io.File(webRoot, "hermit.json").readText()).optString("routing") == "history" }.getOrDefault(false)
            LocalContentGateway.forRelease(this, Uri.parse(origin).host!!, webRoot, history)
        } else null

        val view = WebView(this)
        try {
            ProfileStore.getInstance().getOrCreateProfile(profileName)
            WebViewCompat.setProfile(view, profileName)
        } catch (error: Throwable) {
            release?.releaseId?.let(hermitApp.installer::releaseRelease)
            view.destroy()
            showNativeError("无法建立独立页面空间", error.message ?: "WebView Profile 初始化失败", instance != null)
            return
        }
        configure(view)
        val sdk = assets.open("bridge/hermit-v1.js").bufferedReader().use { it.readText() }
        val controller = BridgeController(view, currentSession, this, sdk)
        controller.install()
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                controller.navigationStarted()
            }
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                controller.navigationCommitted()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (controller.recoverAfterUncommittedNavigation()) {
                    view.evaluateJavascript("window.dispatchEvent(new Event('__hermitrecover'))", null)
                }
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                gateway?.intercept(request) ?: super.shouldInterceptRequest(view, request)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val target = request.url
                if (request.isForMainFrame && target.scheme in setOf("http", "https")) {
                    if (originOf(target) == origin) return false
                    openExternal(target)
                    return true
                }
                if (request.isForMainFrame && target.scheme in setOf("mailto", "tel")) {
                    openExternal(target)
                    return true
                }
                return target.scheme !in setOf("http", "https")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (rebuilding.compareAndSet(false, true)) {
                    val target = currentSession.instance?.appId
                    root.post {
                        rebuilding.set(false)
                        showTarget(target, launchedFromLibrary)
                    }
                }
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
        webView = view
        session = currentSession
        visibleAppId = instance?.appId
        bridge = controller
        root.removeAllViews()
        root.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        view.loadUrl(instance?.startUrl ?: STORE_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private fun destroyRuntime() {
        val releaseId = session?.release?.releaseId
        session?.sessionId?.let { permissionBroker.clearSession(it) }
        bridge?.close()
        bridge = null
        session = null
        visibleAppId = null
        webView?.let { old ->
            old.stopLoading()
            old.webChromeClient = null
            old.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = true
            }
            root.removeView(old)
            old.destroy()
        }
        webView = null
        releaseId?.let(hermitApp.installer::releaseRelease)
        location.cancelAll()
        tts.stop()
        speech.cancel()
    }

    override suspend fun dispatch(session: RuntimeSession, method: String, params: JSONObject): Any? {
        if (!session.alive) throw HermitException(ErrorCodes.SESSION_EXPIRED, "运行会话已经结束")
        val startedAt = android.os.SystemClock.elapsedRealtime()
        return try {
            val result = if (method.startsWith("host.")) {
                if (session.role != RuntimeRole.STORE) throw HermitException(ErrorCodes.ORIGIN_DENIED, "管理接口只属于应用库")
                dispatchHost(method, params)
            } else {
                dispatchPublic(session, method, params)
            }
            hermitApp.registry.recordDiagnostic(session.instance?.appId, session.sessionId, method, "allowed", null,
                android.os.SystemClock.elapsedRealtime() - startedAt)
            result
        } catch (error: Throwable) {
            val code = (error as? HermitException)?.code ?: ErrorCodes.INTERNAL
            val decision = if (code in setOf(ErrorCodes.ORIGIN_DENIED, ErrorCodes.CAPABILITY_DENIED, ErrorCodes.OS_PERMISSION_DENIED)) "denied" else "failed"
            hermitApp.registry.recordDiagnostic(session.instance?.appId, session.sessionId, method, decision, code,
                android.os.SystemClock.elapsedRealtime() - startedAt)
            throw error
        }
    }

    private suspend fun dispatchHost(method: String, params: JSONObject): Any? {
        return when (method) {
        "host.apps.list" -> JSONObject().put("apps", JSONArray(hermitApp.registry.listInstances().map { it.toJson() }))
        "host.apps.installOnline" -> {
            val url = normalizeUrl(params.optString("url"))
            if (Uri.parse(url).scheme.equals("http", true) && !confirmInsecureUrl(url)) {
                return JSONObject().put("cancelled", true)
            }
            val name = params.optString("name").takeIf { it.isNotBlank() }?.take(80)
                ?: Uri.parse(url).host ?: "在线应用"
            val app = WebAppInstance.newOnline(name, url)
            withContext(Dispatchers.IO) { hermitApp.registry.insertInstance(app) }
            app.toJson().put("cancelled", false)
        }
        "host.apps.importZip" -> {
            val uri = pickZip() ?: return JSONObject().put("cancelled", true)
            val result = hermitApp.installer.installUri(uri, params.optString("name").takeIf { it.isNotBlank() })
            JSONObject().put("cancelled", false).put("appId", result.appId).put("releaseId", result.releaseId)
        }
        "host.apps.importDirectory" -> {
            val uri = pickTree() ?: return JSONObject().put("cancelled", true)
            val result = hermitApp.installer.installTree(uri, params.optString("name").takeIf { it.isNotBlank() })
            JSONObject().put("cancelled", false).put("appId", result.appId).put("releaseId", result.releaseId)
        }
        "host.apps.installPackageUrl" -> {
            val result = hermitApp.remoteInstaller.installHttps(params.getString("url"), params.optString("name").takeIf { it.isNotBlank() })
            JSONObject().put("appId", result.appId).put("releaseId", result.releaseId)
        }
        "host.apps.installGitHub" -> {
            val result = hermitApp.remoteInstaller.installGitHub(
                params.getString("owner"), params.getString("repo"), params.optString("ref", "main"),
                params.optString("path"), params.optString("name").takeIf { it.isNotBlank() }
            )
            JSONObject().put("appId", result.appId).put("releaseId", result.releaseId)
        }
        "host.apps.updateFromSource" -> {
            val result = hermitApp.remoteInstaller.update(params.getString("appId"))
            JSONObject().put("appId", result.appId).put("releaseId", result.releaseId).put("treeHash", result.treeHash)
        }
        "host.apps.update" -> {
            val app = hermitApp.registry.getInstance(params.getString("appId"))
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            val name = params.optString("name", app.name).trim().takeIf { it.isNotBlank() }
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "名称不能为空")
            if (app.mode == DeliveryMode.ONLINE) {
                val url = normalizeUrl(params.optString("url", app.startUrl))
                if (url != app.startUrl && Uri.parse(url).scheme.equals("http", true) && !confirmInsecureUrl(url)) {
                    return JSONObject().put("cancelled", true)
                }
                val origin = originOf(Uri.parse(url))
                hermitApp.registry.updateInstance(app.appId, name, url, origin, JSONObject().put("url", url).toString())
            } else {
                hermitApp.registry.updateInstance(app.appId, name, null, null, null)
            }
            val updated = hermitApp.registry.getInstance(app.appId)!!
            processPendingProfileCleanup()
            shortcuts.update(updated)
            updated.toJson()
        }
        "host.apps.releases" -> {
            val appId = params.getString("appId")
            val app = hermitApp.registry.getInstance(appId)
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            JSONObject().put("activeReleaseId", app.activeReleaseId).put("releases", JSONArray(
                hermitApp.registry.listReleases(appId).map { release -> JSONObject()
                    .put("releaseId", release.releaseId).put("treeHash", release.treeHash)
                    .put("versionCode", release.versionCode ?: JSONObject.NULL)
                    .put("versionName", release.versionName ?: JSONObject.NULL)
                    .put("sourceRevision", release.sourceRevision ?: JSONObject.NULL)
                    .put("provenance", release.provenance).put("createdAt", release.createdAt)
                }
            ))
        }
        "host.apps.rollback" -> {
            val appId = params.getString("appId")
            val app = hermitApp.registry.getInstance(appId)
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            if (app.mode != DeliveryMode.LOCAL) throw HermitException(ErrorCodes.CONFLICT, "在线应用没有本地版本")
            val releaseId = params.getString("releaseId")
            withContext(Dispatchers.IO) { hermitApp.registry.activateRelease(appId, releaseId, app.activeReleaseId) }
            JSONObject().put("activeReleaseId", releaseId)
        }
        "host.permissions.list" -> {
            val app = hermitApp.registry.getInstance(params.getString("appId"))
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            observeDeclaredSystemPermissions()
            hermitApp.registry.grantsJson(app.appId, app.trustRevision)
                .put("systemPermissions", hermitApp.registry.systemPermissionsJson())
        }
        "host.permissions.revoke" -> {
            val app = hermitApp.registry.getInstance(params.getString("appId"))
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            hermitApp.registry.clearGrant(app.appId, app.trustRevision, params.getString("capability"), params.optString("scope"))
            JSONObject().put("revoked", true)
        }
        "host.backup.export" -> {
            val app = hermitApp.registry.getInstance(params.getString("appId"))
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            val uri = createBackupDocument("${safeDocumentName(app.name)}.hermit-backup.zip")
                ?: return JSONObject().put("cancelled", true)
            val deployStatus = hermitApp.developmentServer.status()
            if (deployStatus.optBoolean("active") && deployStatus.optString("appId") == app.appId) {
                hermitApp.developmentServer.stop("Backup exported")
            }
            backup.export(app.appId, uri).put("cancelled", false)
        }
        "host.backup.restore" -> {
            val uri = pickBackup() ?: return JSONObject().put("cancelled", true)
            backup.restore(uri).put("cancelled", false)
        }
        "host.backup.restoreData" -> {
            val appId = params.getString("appId")
            val uri = pickBackup() ?: return JSONObject().put("cancelled", true)
            val deployStatus = hermitApp.developmentServer.status()
            if (deployStatus.optBoolean("active") && deployStatus.optString("appId") == appId) {
                hermitApp.developmentServer.stop("Data restored")
            }
            backup.restoreData(uri, appId).put("cancelled", false)
        }
        "host.apps.launch" -> {
            val appId = params.optString("appId")
            if (hermitApp.registry.getInstance(appId) == null) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            root.postDelayed({ showTarget(appId, true) }, 80)
            JSONObject().put("launching", true)
        }
        "host.apps.pin" -> {
            val app = hermitApp.registry.getInstance(params.optString("appId"))
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            JSONObject().put("requested", shortcuts.requestPin(app))
        }
        "host.apps.remove" -> {
            val appId = params.optString("appId")
            val removed = withContext(Dispatchers.IO) { hermitApp.registry.deleteInstance(appId) }
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            shortcuts.disable(appId)
            withContext(Dispatchers.IO) {
                hermitApp.installer.deleteAppFiles(appId)
                hermitApp.registry.finishDelete(appId)
            }
            JSONObject().put("removed", true).put("appId", removed.appId)
        }
        "host.deploy.start" -> {
            val mode = params.optString("mode", "adb")
            if (mode == "lan" && !ensureLanPermission()) throw HermitException(ErrorCodes.OS_PERMISSION_DENIED, "Android 未授予局域网权限")
            hermitApp.developmentServer.start(params.optString("appId"), mode)
        }
        "host.deploy.stop" -> hermitApp.developmentServer.stop("Stopped by user")
        "host.deploy.status" -> hermitApp.developmentServer.status()
        "host.operations.get" -> hermitApp.registry.operationJson(params.optString("operationId"))
            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "操作不存在")
        "host.diagnostics.info" -> {
            val webViewPackage = WebViewCompat.getCurrentWebViewPackage(this)
            val bytes = withContext(Dispatchers.IO) {
                filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            val allocatableBytes = runCatching {
                getSystemService(StorageManager::class.java).getAllocatableBytes(StorageManager.UUID_DEFAULT)
            }.getOrDefault(0L)
            JSONObject().put("generatedAt", System.currentTimeMillis())
                .put("hermit", JSONObject().put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE).put("applicationId", BuildConfig.APPLICATION_ID))
                .put("android", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE))
                .put("webView", JSONObject().put("package", webViewPackage?.packageName ?: JSONObject.NULL)
                    .put("version", webViewPackage?.versionName ?: JSONObject.NULL)
                    .put("multiProfile", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                    .put("webMessageListener", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                    .put("documentStartScript", WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)))
                .put("storage", JSONObject().put("hermitManagedBytes", bytes).put("allocatableBytes", allocatableBytes))
                .put("instanceCount", hermitApp.registry.listInstances().size)
                .put("systemPermissions", hermitApp.registry.systemPermissionsJson())
                .put("diagnosticEventCount", hermitApp.registry.diagnosticEventCount())
                .put("developerSession", hermitApp.developmentServer.status())
        }
        "host.licenses.info" -> JSONObject().put(
            "text",
            assets.open("third-party-notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
            else -> throw HermitException(ErrorCodes.UNSUPPORTED, "未知管理方法：$method")
        }
    }

    private suspend fun dispatchPublic(session: RuntimeSession, method: String, params: JSONObject): Any? {
        val app = session.instance
        return when (method) {
            "runtime.info" -> JSONObject().put("apiMajor", 1).put("apiMinor", 0)
                .put("sessionId", session.sessionId).put("appId", session.appId)
                .put("role", session.role.name.lowercase()).put("webViewPackage", WebViewCompat.getCurrentWebViewPackage(this)?.versionName)
            "runtime.capabilities" -> capabilityDescriptors(session)
            "app.info" -> app?.toJson() ?: JSONObject().put("appId", RuntimeSession.STORE_APP_ID).put("name", "Hermit 应用库")
            "app.ready" -> JSONObject().put("recorded", true).put("at", System.currentTimeMillis())
            "app.checkUpdate" -> JSONObject().put("sourceAdapter", app?.sourceAdapter ?: "host")
                .put("canCheck", app?.sourceAdapter in setOf("https-package", "github"))
            "app.reload" -> {
                root.postDelayed({ showTarget(app?.appId, launchedFromLibrary) }, 80)
                JSONObject().put("reloading", true)
            }
            "data.get" -> requireApp(app).let { withContext(Dispatchers.IO) {
                records.get(it.appId, session.dataGeneration!!, params.getString("collection"), params.getString("key")) ?: JSONObject.NULL
            } }
            "data.put" -> requireApp(app).let { withContext(Dispatchers.IO) {
                records.put(it.appId, session.dataGeneration!!, params.getString("collection"), params.getString("key"), params.opt("value"), params.optString("expectedRevision").takeIf { _ -> params.has("expectedRevision") })
            } }
            "data.delete" -> requireApp(app).let { withContext(Dispatchers.IO) {
                records.delete(it.appId, session.dataGeneration!!, params.getString("collection"), params.getString("key"), params.optString("expectedRevision").takeIf { _ -> params.has("expectedRevision") })
            } }
            "data.scan" -> requireApp(app).let { withContext(Dispatchers.IO) {
                records.scan(it.appId, session.dataGeneration!!, params.getString("collection"), params.optString("prefix"), params.optString("afterKey").takeIf { _ -> params.has("afterKey") }, params.optInt("limit", 50))
            } }
            "data.batch" -> requireApp(app).let { withContext(Dispatchers.IO) {
                records.batch(it.appId, session.dataGeneration!!, params.getJSONArray("operations"))
            } }
            "tts.voices" -> tts.voices()
            "tts.speak" -> tts.speak(params) { event, data -> if (this.session === session) bridge?.emit(event, data) }
            "tts.stop" -> tts.stop()
            "tts.export" -> requireApp(app).let {
                val output = File(cacheDir, "tts-${UUID.randomUUID()}.wav")
                try {
                    tts.synthesize(params, output)
                    withContext(Dispatchers.IO) { output.inputStream().use { stream ->
                        files.import(it.appId, session.dataGeneration!!, stream, params.optString("name", "speech.wav"), "audio/wav")
                    } }
                } finally { output.delete() }
            }
            "files.import" -> requireApp(app).let {
                val uri = pickFile() ?: return JSONObject().put("cancelled", true)
                val name = queryDisplayName(uri) ?: "file"
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val input = contentResolver.openInputStream(uri) ?: throw HermitException(ErrorCodes.STORAGE, "无法读取文件")
                input.use { stream -> withContext(Dispatchers.IO) { files.import(it.appId, session.dataGeneration!!, stream, name, mime) } }
            }
            "files.writeText" -> requireApp(app).let { withContext(Dispatchers.IO) {
                files.writeText(it.appId, session.dataGeneration!!, params.optString("name", "note.txt"), params.getString("text"))
            } }
            "files.readText" -> requireApp(app).let { withContext(Dispatchers.IO) {
                files.readText(it.appId, session.dataGeneration!!, params.getString("logicalFileId"))
            } }
            "files.list" -> requireApp(app).let { withContext(Dispatchers.IO) { files.list(it.appId, session.dataGeneration!!) } }
            "files.export" -> requireApp(app).let {
                val logicalId = params.getString("logicalFileId")
                val metadata = withContext(Dispatchers.IO) { files.metadata(it.appId, session.dataGeneration!!, logicalId) }
                    ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "文件不存在")
                val uri = createFileDocument(metadata.name) ?: return JSONObject().put("cancelled", true)
                if (!session.alive || this.session !== session) throw HermitException(ErrorCodes.SESSION_EXPIRED, "导出期间页面会话已结束")
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: throw HermitException(ErrorCodes.STORAGE, "无法创建导出文件")
                    files.open(it.appId, session.dataGeneration!!, logicalId).use { input ->
                        output.use { target -> input.copyTo(target) }
                    }
                }
                JSONObject().put("exported", true).put("cancelled", false)
            }
            "files.delete" -> requireApp(app).let { withContext(Dispatchers.IO) {
                files.delete(it.appId, session.dataGeneration!!, params.getString("logicalFileId"))
            } }
            "files.share", "share.file" -> requireApp(app).let {
                val (uri, metadata) = withContext(Dispatchers.IO) {
                    files.prepareShare(it.appId, session.dataGeneration!!, params.getString("logicalFileId"))
                }
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = metadata.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, params.optString("title", "分享文件")))
                JSONObject().put("chooserOpened", true)
            }
            "speech.availability" -> speech.availability()
            "speech.start" -> {
                permissionBroker.require(session, "speech", "录制声音并交给系统语音识别服务", PermissionBroker.SPEECH_PERMISSIONS)
                speech.start(params) { event, data -> if (this.session === session) bridge?.emit(event, data) }
            }
            "speech.stop" -> speech.stop(params.optString("subscriptionId").takeIf { it.isNotBlank() })
            "speech.cancel" -> speech.cancel()
            "location.getCurrent" -> {
                val precise = params.optBoolean("precise", false)
                permissionBroker.require(session, if (precise) "location.precise" else "location.approximate",
                    if (precise) "读取设备的精确位置" else "读取设备的大致位置",
                    if (precise) PermissionBroker.FINE_LOCATION else PermissionBroker.COARSE_LOCATION)
                location.getCurrent(params)
            }
            "location.watch" -> {
                val precise = params.optBoolean("precise", false)
                permissionBroker.require(session, if (precise) "location.precise" else "location.approximate",
                    if (precise) "持续读取前台精确位置" else "持续读取前台大致位置",
                    if (precise) PermissionBroker.FINE_LOCATION else PermissionBroker.COARSE_LOCATION)
                location.watch(params) { event, data -> if (this.session === session) bridge?.emit(event, data) }
            }
            "location.clearWatch" -> location.clearWatch(params.getString("subscriptionId"))
            "camera.capturePhoto" -> requireApp(app).let {
                permissionBroker.require(session, "camera.capture", "打开系统相机拍摄一张照片")
                capturePhoto(it, session)
            }
            "clipboard.write" -> {
                val text = params.getString("text")
                if (text.length > 100_000) throw HermitException(ErrorCodes.QUOTA, "剪贴板文本过长")
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(params.optString("label", "Hermit"), text))
                JSONObject().put("written", true)
            }
            "clipboard.read" -> {
                permissionBroker.require(session, "clipboard.read", "读取当前剪贴板内容")
                val clip = getSystemService(android.content.ClipboardManager::class.java).primaryClip
                JSONObject().put("text", clip?.getItemAt(0)?.coerceToText(this)?.toString() ?: JSONObject.NULL)
            }
            "share.text" -> {
                val text = params.getString("text")
                if (text.length > 100_000) throw HermitException(ErrorCodes.QUOTA, "分享文本过长")
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, params.optString("title", "分享")))
                JSONObject().put("chooserOpened", true)
            }
            "haptics.vibrate" -> {
                enforceHapticRate()
                val duration = params.optLong("durationMs", 30).coerceIn(1, 500)
                val vibrator = getSystemService(android.os.Vibrator::class.java)
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                JSONObject().put("started", true)
            }
            "haptics.impact" -> {
                enforceHapticRate()
                val duration = when (params.optString("style", "medium")) { "light" -> 18L; "heavy" -> 55L; else -> 32L }
                getSystemService(android.os.Vibrator::class.java)
                    .vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                JSONObject().put("started", true)
            }
            "network.status" -> JSONObject().put("connected",
                (getSystemService(android.net.ConnectivityManager::class.java).activeNetwork != null))
            "network.request" -> requireApp(app).let {
                nativeHttp.request(it.appId, session.dataGeneration!!, params) { origin, addressClass ->
                    val grantScope = NativeHttpClient.AuthorizationTarget(origin, addressClass).grantScope
                    permissionBroker.require(session, "network", "通过 Hermit 访问 $origin（$addressClass 网络）", scope = grantScope)
                }
            }
            "permissions.status" -> {
                val capability = params.getString("capability")
                validateGrantCapability(capability)
                val scope = capabilityScope(capability, params.optString("scope"))
                val target = if (capability == "network") nativeHttp.authorizationTarget(scope) else null
                JSONObject(permissionBroker.status(session, capability, capabilityPermissions(capability), target?.grantScope ?: scope))
                    .put("scope", scope)
                    .put("addressClass", target?.addressClass ?: JSONObject.NULL)
            }
            "permissions.request" -> {
                val capability = params.getString("capability")
                validateGrantCapability(capability)
                val scope = capabilityScope(capability, params.optString("scope"))
                val target = if (capability == "network") nativeHttp.authorizationTarget(scope) else null
                val rationale = if (target != null) "通过 Hermit 原生网络连接访问 $scope（${target.addressClass} 网络）" else capabilityRationale(capability)
                permissionBroker.require(session, capability, rationale, capabilityPermissions(capability), target?.grantScope ?: scope)
                JSONObject().put("capability", capability).put("scope", scope)
                    .put("addressClass", target?.addressClass ?: JSONObject.NULL).put("usable", true)
            }
            else -> throw HermitException(ErrorCodes.UNSUPPORTED, "当前版本尚不支持：$method")
        }
    }

    private fun requireApp(app: WebAppInstance?): WebAppInstance =
        app ?: throw HermitException(ErrorCodes.ORIGIN_DENIED, "应用库不能调用页面数据接口")

    private suspend fun pickZip(): Uri? = suspendCancellableCoroutine { continuation ->
        if (pendingZip != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有文件选择操作")))
            return@suspendCancellableCoroutine
        }
        pendingZip = continuation
        continuation.invokeOnCancellation { if (pendingZip === continuation) pendingZip = null }
        zipPicker.launch("application/zip")
    }

    private suspend fun pickFile(): Uri? = suspendCancellableCoroutine { continuation ->
        if (pendingFile != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有文件选择操作")))
            return@suspendCancellableCoroutine
        }
        pendingFile = continuation
        continuation.invokeOnCancellation { if (pendingFile === continuation) pendingFile = null }
        filePicker.launch("*/*")
    }

    private suspend fun createFileDocument(name: String): Uri? = suspendCancellableCoroutine { continuation ->
        if (pendingFileExport != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有文件导出操作")))
            return@suspendCancellableCoroutine
        }
        pendingFileExport = continuation
        continuation.invokeOnCancellation { if (pendingFileExport === continuation) pendingFileExport = null }
        fileExportLauncher.launch(safeDocumentName(name))
    }

    private suspend fun pickTree(): Uri? = suspendCancellableCoroutine { continuation ->
        if (pendingTree != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有目录选择操作")))
            return@suspendCancellableCoroutine
        }
        pendingTree = continuation
        continuation.invokeOnCancellation { if (pendingTree === continuation) pendingTree = null }
        treePicker.launch(null)
    }

    private suspend fun capturePhoto(app: WebAppInstance, runtime: RuntimeSession): JSONObject {
        if (pendingCamera != null) throw HermitException(ErrorCodes.CONFLICT, "已有拍照操作")
        val directory = File(cacheDir, "shared").apply { mkdirs() }
        val target = File(directory, "capture-${UUID.randomUUID()}.jpg")
        pendingCameraFile = target
        val uri = FileProvider.getUriForFile(this, "$packageName.files", target)
        val captured = suspendCancellableCoroutine<Boolean> { continuation ->
            pendingCamera = continuation
            continuation.invokeOnCancellation { if (pendingCamera === continuation) pendingCamera = null }
            cameraLauncher.launch(uri)
        }
        pendingCameraFile = null
        if (!captured || !target.isFile || target.length() == 0L) {
            target.delete()
            return JSONObject().put("cancelled", true)
        }
        if (!runtime.alive || session !== runtime) {
            target.delete()
            throw HermitException(ErrorCodes.SESSION_EXPIRED, "拍照期间页面会话已结束")
        }
        return try {
            withContext(Dispatchers.IO) { target.inputStream().use { files.import(app.appId, runtime.dataGeneration!!, it, "photo.jpg", "image/jpeg") } }
                .put("cancelled", false)
        } finally { target.delete() }
    }

    private suspend fun createBackupDocument(name: String): Uri? = suspendCancellableCoroutine { continuation ->
        if (pendingBackupExport != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有备份导出操作")))
            return@suspendCancellableCoroutine
        }
        pendingBackupExport = continuation
        continuation.invokeOnCancellation { if (pendingBackupExport === continuation) pendingBackupExport = null }
        backupExportLauncher.launch(name)
    }

    private suspend fun pickBackup(): Uri? = suspendCancellableCoroutine { continuation ->
        if (pendingBackupImport != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有备份恢复操作")))
            return@suspendCancellableCoroutine
        }
        pendingBackupImport = continuation
        continuation.invokeOnCancellation { if (pendingBackupImport === continuation) pendingBackupImport = null }
        backupImportLauncher.launch("application/zip")
    }

    private fun safeDocumentName(value: String): String = value.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]+"), "-").take(60).ifBlank { "hermit-app" }

    private fun capabilityDescriptors(runtime: RuntimeSession): JSONObject {
        val names = listOf("runtime", "app", "data", "files", "tts", "speech", "location", "camera", "share", "clipboard", "haptics", "network")
        return JSONObject().put("capabilities", JSONArray(names.map { name ->
            val supported = when (name) {
                "speech" -> android.speech.SpeechRecognizer.isRecognitionAvailable(this)
                "location" -> getSystemService(android.location.LocationManager::class.java).allProviders.isNotEmpty()
                "camera" -> packageManager.resolveActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY) != null
                else -> true
            }
            JSONObject().put("name", name).put("implemented", true).put("supported", supported)
                .put("usable", supported && (runtime.role == RuntimeRole.WEB_APP || name in setOf("runtime", "app")))
                .put("lifecycle", if (name in setOf("speech", "location", "tts")) "foreground-session" else "request")
        }))
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun capabilityPermissions(capability: String): List<String> = when (capability) {
        "speech" -> PermissionBroker.SPEECH_PERMISSIONS
        "location.approximate" -> PermissionBroker.COARSE_LOCATION
        "location.precise" -> PermissionBroker.FINE_LOCATION
        else -> emptyList()
    }

    private fun capabilityRationale(capability: String): String = when (capability) {
        "speech" -> "录制声音并交给系统语音识别服务"
        "location.approximate" -> "读取设备的大致位置"
        "location.precise" -> "读取设备的精确位置"
        "clipboard.read" -> "读取当前剪贴板内容"
        "network" -> "通过 Hermit 原生网络连接访问已确认的目标"
        else -> "允许页面使用 $capability"
    }

    private fun validateGrantCapability(capability: String) {
        if (capability !in GRANT_CAPABILITIES) {
            throw HermitException(ErrorCodes.UNSUPPORTED, "该能力不使用页面授权：$capability")
        }
    }

    private fun enforceHapticRate() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHapticAt < 150L) throw HermitException(ErrorCodes.QUOTA, "震动请求过于频繁", true)
        lastHapticAt = now
    }

    private fun capabilityScope(capability: String, rawScope: String): String {
        if (capability != "network") {
            if (rawScope.isNotBlank()) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "该能力不接受授权范围")
            return ""
        }
        if (rawScope.length > MAX_URL_LENGTH) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "网络授权范围过长")
        val uri = Uri.parse(rawScope.trim())
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || (!uri.path.isNullOrEmpty() && uri.path != "/")) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "网络授权范围必须是完整 Origin")
        }
        return originOf(uri)
    }

    private suspend fun requestPermissions(names: Array<String>): Map<String, Boolean> =
        suspendCancellableCoroutine { continuation ->
            if (pendingPermissions != null) {
                continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有系统授权请求")))
                return@suspendCancellableCoroutine
            }
            pendingPermissions = continuation
            continuation.invokeOnCancellation { if (pendingPermissions === continuation) pendingPermissions = null }
            permissionLauncher.launch(names)
        }

    private suspend fun ensureLanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
        val existing = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        hermitApp.registry.observeSystemPermission(permission, existing)
        if (existing) return true
        val granted = requestPermissions(arrayOf(permission))[permission] == true
        hermitApp.registry.observeSystemPermission(permission, granted)
        return granted
    }

    private fun observeDeclaredSystemPermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        permissions.forEach { permission ->
            hermitApp.registry.observeSystemPermission(
                permission,
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    private fun runtimeFeaturesAvailable(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private fun normalizeUrl(input: String): String {
        var value = input.trim()
        if (value.isBlank()) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "请输入地址")
        if (value.length > MAX_URL_LENGTH) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "地址过长")
        if (value.any { it <= '\u001F' }) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "地址包含控制字符")
        if (!value.contains("://")) value = if (looksLan(value)) "http://$value" else "https://$value"
        val uri = Uri.parse(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "只支持完整的 HTTP 或 HTTPS 地址")
        }
        if (uri.host!!.endsWith(".hermit.invalid", true)) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "该域名保留给 Hermit 内部使用")
        return uri.toString()
    }

    private fun isLanUrl(url: String): Boolean = looksLan(Uri.parse(url).host ?: "")
    private fun looksLan(host: String): Boolean {
        val value = host.substringBefore(':').trim('[', ']')
        if (value.equals("localhost", true) || value.endsWith(".local", true)) return true
        return try {
            if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return false
            val address = InetAddress.getByName(value)
            address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
        } catch (_: Throwable) { false }
    }

    private fun originOf(uri: Uri): String {
        val port = if (uri.port != -1 && !((uri.scheme.equals("https", true) && uri.port == 443) || (uri.scheme.equals("http", true) && uri.port == 80))) ":${uri.port}" else ""
        return "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}$port"
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "没有可处理此链接的应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun showNativeError(title: String, message: String, showLibrary: Boolean) {
        destroyRuntime()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((32 * resources.displayMetrics.density).toInt())
        }
        layout.addView(TextView(this).apply { text = title; textSize = 24f; setTextColor(Color.rgb(35, 78, 63)); gravity = Gravity.CENTER })
        layout.addView(TextView(this).apply {
            text = message; textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (20 * resources.displayMetrics.density).toInt())
        })
        layout.addView(Button(this).apply {
            text = getString(R.string.retry)
            setOnClickListener { showTarget(intent.getStringExtra(EXTRA_APP_ID), launchedFromLibrary) }
        })
        if (showLibrary) layout.addView(Button(this).apply {
            text = "返回应用库"
            setOnClickListener { showTarget(null, false) }
        })
        layout.addView(Button(this).apply {
            text = getString(R.string.open_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        })
        if (!runtimeFeaturesAvailable()) addNativeRecovery(layout, title, message, showLibrary)
        root.removeAllViews()
        root.addView(ScrollView(this).apply { addView(layout, android.widget.FrameLayout.LayoutParams(-1, -2)) },
            android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    private fun addNativeRecovery(layout: LinearLayout, title: String, message: String, showLibrary: Boolean) {
        layout.addView(TextView(this).apply {
            text = getString(R.string.native_recovery_title)
            textSize = 20f
            setTextColor(Color.rgb(35, 78, 63))
            setPadding(0, (28 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
        })
        layout.addView(TextView(this).apply {
            text = getString(R.string.native_recovery_message)
            gravity = Gravity.CENTER
        })
        hermitApp.registry.listInstances().forEach { app ->
            layout.addView(Button(this).apply {
                text = getString(R.string.export_backup_for, app.name)
                setOnClickListener {
                    isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val uri = createBackupDocument("${safeDocumentName(app.name)}.hermit-backup.zip")
                            if (uri != null) {
                                backup.export(app.appId, uri)
                                Toast.makeText(this@MainActivity, "“${app.name}”备份已导出", Toast.LENGTH_LONG).show()
                            }
                        } catch (error: Throwable) {
                            Toast.makeText(this@MainActivity, error.message ?: "备份导出失败", Toast.LENGTH_LONG).show()
                        } finally { isEnabled = true }
                    }
                }
            })
        }
        layout.addView(Button(this).apply {
            text = getString(R.string.restore_new_instance)
            setOnClickListener {
                isEnabled = false
                lifecycleScope.launch {
                    try {
                        val uri = pickBackup()
                        if (uri != null) {
                            val restored = backup.restore(uri)
                            Toast.makeText(this@MainActivity, "已恢复“${restored.getString("name")}”", Toast.LENGTH_LONG).show()
                            showNativeError(title, message, showLibrary)
                        }
                    } catch (error: Throwable) {
                        Toast.makeText(this@MainActivity, error.message ?: "备份恢复失败", Toast.LENGTH_LONG).show()
                    } finally { isEnabled = true }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = getString(R.string.open_webview_settings)
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_WEBVIEW_SETTINGS)) }
                    .onFailure { Toast.makeText(this@MainActivity, "此设备没有可打开的 WebView 设置页", Toast.LENGTH_LONG).show() }
            }
        })
    }

    @SuppressLint("RequiresFeature")
    private fun processPendingProfileCleanup() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        val store = ProfileStore.getInstance()
        hermitApp.registry.pendingProfileCleanup().forEach { (profileName, _) ->
            runCatching {
                if (profileName !in store.getAllProfileNames() || store.deleteProfile(profileName)) {
                    hermitApp.registry.completeProfileCleanup(profileName)
                }
            }
        }
    }

    private fun promptSharedUrl(text: String) {
        val candidate = runCatching { normalizeUrl(text) }.getOrNull() ?: return
        val warning = if (Uri.parse(candidate).scheme.equals("http", true)) {
            "\n\n警告：这是未加密的 HTTP 地址，网页内容和凭据可能被同一网络中的其他人读取或篡改。"
        } else ""
        AlertDialog.Builder(this).setTitle("添加在线应用？").setMessage(candidate + warning)
            .setNegativeButton("取消", null).setPositiveButton("添加") { _, _ ->
                val app = WebAppInstance.newOnline(Uri.parse(candidate).host ?: "在线应用", candidate)
                hermitApp.registry.insertInstance(app)
                showTarget(null, false)
            }.show()
    }

    private suspend fun confirmInsecureUrl(url: String): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this)
            .setTitle("允许未加密的 HTTP 页面？")
            .setMessage("$url\n\n网页内容和凭据可能被同一网络中的其他人读取或篡改。仅在你信任当前网络和服务时继续。")
            .setNegativeButton("取消") { _, _ -> if (continuation.isActive) continuation.resume(false) }
            .setPositiveButton("仍然添加") { _, _ -> if (continuation.isActive) continuation.resume(true) }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
            .create()
        continuation.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun inspectRuntimeForTest(callback: (String?) -> Unit) {
        val view = webView
        if (view == null) callback(null)
        else view.evaluateJavascript("JSON.stringify({title:document.title,hermit:!!window.hermit,ready:!!(window.hermit&&window.hermit.isReady),url:location.href})", callback)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun evaluateForTest(script: String, callback: (String?) -> Unit) {
        webView?.evaluateJavascript(script, callback) ?: callback(null)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun importSharedZipForTest(uri: Uri) {
        importSharedZip(uri)
    }

    override fun onDestroy() {
        hermitApp.developmentServer.setReloadHandler(null)
        destroyRuntime()
        tts.shutdown()
        speech.shutdown()
        pendingZip?.cancel()
        pendingTree?.cancel()
        pendingFile?.cancel()
        pendingFileExport?.cancel()
        pendingPermissions?.cancel()
        pendingCamera?.cancel()
        pendingCameraFile?.delete()
        pendingBackupExport?.cancel()
        pendingBackupImport?.cancel()
        super.onDestroy()
    }

    override fun onStop() {
        location.cancelAll()
        tts.stop()
        speech.cancel()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        observeDeclaredSystemPermissions()
    }

    companion object {
        const val EXTRA_APP_ID = "io.github.zhyuzh3d.hermit.APP_ID"
        private val GRANT_CAPABILITIES = setOf(
            "speech", "location.approximate", "location.precise", "camera.capture", "clipboard.read", "network"
        )
        private const val STORE_ORIGIN = "https://store.hermit.invalid"
        private const val STORE_URL = "$STORE_ORIGIN/index.html"
        private const val STORE_PROFILE = "hermit-store"
        private const val MAX_URL_LENGTH = 4096
    }
}
