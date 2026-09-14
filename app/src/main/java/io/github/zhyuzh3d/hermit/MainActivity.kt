package io.github.zhyuzh3d.hermit

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.setPadding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.zhyuzh3d.hermit.bridge.BridgeController
import io.github.zhyuzh3d.hermit.bridge.BridgeHost
import io.github.zhyuzh3d.hermit.bridge.LegacyBridgeController
import io.github.zhyuzh3d.hermit.bridge.PageBridge
import io.github.zhyuzh3d.hermit.backup.BackupCoordinator
import io.github.zhyuzh3d.hermit.capability.TtsController
import io.github.zhyuzh3d.hermit.capability.AudioController
import io.github.zhyuzh3d.hermit.capability.PermissionBroker
import io.github.zhyuzh3d.hermit.capability.SpeechController
import io.github.zhyuzh3d.hermit.capability.LocationController
import io.github.zhyuzh3d.hermit.capability.NativeHttpClient
import io.github.zhyuzh3d.hermit.capability.SensorController
import io.github.zhyuzh3d.hermit.capability.WifiController
import io.github.zhyuzh3d.hermit.capability.BluetoothController
import io.github.zhyuzh3d.hermit.capability.InfraredController
import io.github.zhyuzh3d.hermit.capability.DeviceController
import io.github.zhyuzh3d.hermit.data.FileStore
import io.github.zhyuzh3d.hermit.data.RecordsStore
import io.github.zhyuzh3d.hermit.launcher.ShortcutHost
import io.github.zhyuzh3d.hermit.install.IdentityInstallChoice
import io.github.zhyuzh3d.hermit.install.IconProcessor
import io.github.zhyuzh3d.hermit.install.PackageManifest
import io.github.zhyuzh3d.hermit.install.PackageManifestReader
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HappRuntimeMode
import io.github.zhyuzh3d.hermit.model.HappSource
import io.github.zhyuzh3d.hermit.model.LaunchChannel
import io.github.zhyuzh3d.hermit.model.HermitException
import io.github.zhyuzh3d.hermit.model.WebAppInstance
import io.github.zhyuzh3d.hermit.runtime.LocalContentGateway
import io.github.zhyuzh3d.hermit.runtime.OfficialShellManager
import io.github.zhyuzh3d.hermit.runtime.SharedAssetGateway
import io.github.zhyuzh3d.hermit.runtime.RuntimeRole
import io.github.zhyuzh3d.hermit.runtime.RuntimeSession
import io.github.zhyuzh3d.hermit.runtime.RuntimeNetworkPolicy
import io.github.zhyuzh3d.hermit.notification.NotificationSpec
import io.github.zhyuzh3d.hermit.notification.Recurrence
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.InetAddress
import java.io.File
import java.io.ByteArrayOutputStream
import android.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity(), BridgeHost {
    private val hermitApp get() = application as HermitApplication
    private val records by lazy { RecordsStore(this) }
    private val files by lazy { FileStore(this) }
    private val shortcuts by lazy { ShortcutHost(this) }
    private val tts by lazy { TtsController(this) }
    private val audio by lazy { AudioController(this) }
    private val speech by lazy { SpeechController(this) }
    private val permissionBroker by lazy { PermissionBroker(this, hermitApp.registry, ::requestPermissions) }
    private val location by lazy { LocationController(this) }
    private val sensors by lazy { SensorController(this) }
    private val wifi by lazy { WifiController(this) }
    private val bluetooth by lazy { BluetoothController(this) }
    private val infrared by lazy { InfraredController(this) }
    private val device by lazy { DeviceController(this) }
    private val nativeHttp by lazy { NativeHttpClient(files) }
    private val backup by lazy { BackupCoordinator(this, hermitApp.registry, hermitApp.installer, records, files) }
    private lateinit var root: android.widget.FrameLayout
    private var webView: WebView? = null
    private var session: RuntimeSession? = null
    private var bridge: PageBridge? = null
    @Volatile private var visibleAppId: String? = null
    private var launchedFromLibrary = false
    private var pendingStoreScript: String? = null
    private data class PendingAgentReload(
        val role: RuntimeRole,
        val appId: String?,
        val restoreStateJson: String?,
        val postReloadScript: String?,
    )
    private var pendingAgentReload: PendingAgentReload? = null
    private data class PromptChoice(val value: String, val label: String, val emphasis: String = "normal")
    private data class PendingShellPrompt(
        val token: String,
        val choices: Set<String>,
        val continuation: CancellableContinuation<String>,
    )
    private var pendingShellPrompt: PendingShellPrompt? = null
    private var pendingOpenedNotification: JSONObject? = null
    private var forceLocalStoreOnce = false
    private var storeRunningMode = OfficialShellManager.Mode.LOCAL.value
    private var pendingZip: CancellableContinuation<Uri?>? = null
    private var pendingTree: CancellableContinuation<Uri?>? = null
    private var pendingFile: CancellableContinuation<Uri?>? = null
    private var pendingImage: CancellableContinuation<Uri?>? = null
    private var pendingFileExport: CancellableContinuation<Uri?>? = null
    private var pendingPermissions: CancellableContinuation<Map<String, Boolean>>? = null
    private var pendingCamera: CancellableContinuation<Boolean>? = null
    private var pendingCameraFile: File? = null
    private var pendingBackupExport: CancellableContinuation<Uri?>? = null
    private var pendingBackupImport: CancellableContinuation<Uri?>? = null
    private var pendingQrScan: CancellableContinuation<JSONObject>? = null
    private var pendingIcon: CancellableContinuation<Uri?>? = null
    private var pendingExactAlarm: CancellableContinuation<Boolean>? = null
    private var pendingSpeechActivity: CancellableContinuation<JSONObject>? = null
    private val pendingDirectoryImports = LinkedHashMap<String, Uri>()
    private val rebuilding = AtomicBoolean(false)
    private var lastHapticAt = 0L
    private var keyboardOverlaysContent = false
    private var hasResumed = false

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
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pendingImage?.let { continuation ->
            pendingImage = null
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
    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingIcon?.let { continuation ->
            pendingIcon = null
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
    private val qrScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val continuation = pendingQrScan ?: return@registerForActivityResult
        pendingQrScan = null
        if (!continuation.isActive) return@registerForActivityResult
        val error = result.data?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
        if (!error.isNullOrBlank()) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.UNSUPPORTED, error, true)))
            return@registerForActivityResult
        }
        val raw = result.data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
        if (result.resultCode != RESULT_OK || raw.isNullOrBlank()) {
            continuation.resume(JSONObject().put("cancelled", true))
            return@registerForActivityResult
        }
        try {
            continuation.resume(JSONObject().put("cancelled", false).put("url", normalizeUrl(raw)))
        } catch (_: Throwable) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.INVALID_ARGUMENT, "二维码中没有可添加的网页链接")))
        }
    }
    private val exactAlarmLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingExactAlarm?.let { continuation ->
            pendingExactAlarm = null
            if (continuation.isActive) continuation.resume(hermitApp.notifications.scheduler.exactAlarmAvailable())
        }
    }
    private val speechActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingSpeechActivity?.let { continuation ->
            pendingSpeechActivity = null
            if (continuation.isActive) continuation.resume(
                if (result.resultCode == RESULT_OK) speech.activityResult(result.data)
                else JSONObject().put("cancelled", true).put("mode", "one-shot")
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = android.widget.FrameLayout(this)
        setContentView(root)
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.hermit_background))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            var types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            if (!keyboardOverlaysContent) types = types or WindowInsetsCompat.Type.ime()
            val safe = insets.getInsets(types)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        hermitApp.developmentServer.setReloadHandler { appId ->
            if (visibleAppId != appId) false else {
                root.post { if (visibleAppId == appId) showTarget(appId, launchedFromLibrary) }
                true
            }
        }
        processPendingProfileCleanup()
        hermitApp.agentServer.setUiHandler { action, args ->
            val appId = args.optString("appId").takeIf { it.isNotBlank() }
            when (action) {
                "open" -> openAgentTarget(appId, args.optString("route").takeIf { !args.isNull("route") && it.isNotBlank() })
                "page-state" -> captureAgentPageState(args)
                "reload" -> reloadAppFromAgent(args)
                "reload-shell" -> reloadShellFromAgent(args)
                "refresh" -> refreshDevRuntime(args)
                "switch" -> {
                    if (visibleAppId == appId && session?.role == RuntimeRole.WEB_APP) {
                        showTarget(appId, launchedFromLibrary)
                        JSONObject().put("state", "reloading").put("appId", appId)
                    } else JSONObject().put("state", "not-visible").put("appId", appId)
                }
                else -> JSONObject().put("appId", visibleAppId ?: JSONObject.NULL).put("role", session?.role?.name ?: "NONE")
            }
        }
        hermitApp.agentServer.setStateHandler { active -> root.post { root.keepScreenOn = active } }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEBVIEW_DEBUGGING)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = webView
                if (current != null && session?.role == RuntimeRole.STORE) {
                    current.evaluateJavascript("Boolean(window.hermitStoreBack && window.hermitStoreBack())") { handled ->
                        if (webView === current && handled != "true") {
                            if (current.canGoBack()) current.goBack() else finish()
                        }
                    }
                }
                else if (session?.role == RuntimeRole.SUPPORT) showTarget(null, false)
                else if (current != null && current.canGoBack()) current.goBack()
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
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        if (!notificationId.isNullOrBlank()) {
            pendingOpenedNotification = JSONObject().put("id", notificationId)
                .put("data", runCatching { JSONObject(intent.getStringExtra(EXTRA_NOTIFICATION_DATA) ?: "{}") }.getOrDefault(JSONObject()))
            intent.removeExtra(EXTRA_NOTIFICATION_ID)
            intent.removeExtra(EXTRA_NOTIFICATION_DATA)
        }
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
            promptSharedUrl(shared)
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
                val result = hermitApp.installer.installUri(uri, null, ::chooseIdentityInstall)
                Toast.makeText(this@MainActivity, "ZIP 副本已导入", Toast.LENGTH_LONG).show()
                showTarget(result.appId, true)
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, error.message ?: "ZIP 导入失败", Toast.LENGTH_LONG).show()
                showTarget(null, false)
            }
        }
    }

    private fun openAgentTarget(appId: String?, route: String?): JSONObject {
        val id = appId ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "缺少 appId")
        val app = hermitApp.registry.getInstance(id)
            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        val target = resolveAppRoute(app, route)
        val current = session
        if (visibleAppId == id && current?.role == RuntimeRole.WEB_APP && current.instance?.launchChannel == app.launchChannel) {
            webView?.loadUrl(target)
            return JSONObject().put("state", "opening").put("appId", id).put("url", target).put("reusedWebView", true)
        }
        showTarget(id, true, route)
        return JSONObject().put("state", "opening").put("appId", id).put("url", target).put("reusedWebView", false)
    }

    private fun refreshDevRuntime(args: JSONObject): JSONObject {
        val appId = args.getString("appId")
        val current = session
        val view = webView
        if (visibleAppId != appId || current?.role != RuntimeRole.WEB_APP || view == null || current.instance?.launchChannel != LaunchChannel.DEV) {
            return JSONObject().put("state", "not-visible").put("appId", appId)
        }
        val revision = args.getLong("revision")
        current.devRevision = revision
        val route = args.optString("route").takeIf { !args.isNull("route") && it.isNotBlank() }
        if (route != null) {
            val target = resolveAppRoute(hermitApp.registry.getInstance(appId)!!, route)
            view.loadUrl(target)
            return JSONObject().put("state", "opening").put("appId", appId).put("url", target).put("reusedWebView", true)
        }
        val paths = args.optJSONArray("changedPaths") ?: JSONArray()
        if ((0 until paths.length()).any { paths.getString(it) == "hermit.json" }) {
            showTarget(appId, launchedFromLibrary)
            return JSONObject().put("state", "runtime-recreated").put("appId", appId).put("revision", revision)
        }
        val cssOnly = paths.length() > 0 && (0 until paths.length()).all { paths.getString(it).substringAfterLast('.', "").equals("css", true) }
        if (args.optString("refreshMode", "auto") == "auto" && cssOnly) {
            view.evaluateJavascript(
                "window.__hermitDevRefreshCss && window.__hermitDevRefreshCss(" + paths.toString() + "," + revision + ")",
                null,
            )
            return JSONObject().put("state", "css-hot-swap").put("appId", appId).put("revision", revision)
        }
        view.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        view.reload()
        return JSONObject().put("state", "reloading").put("appId", appId).put("revision", revision)
    }

    private suspend fun captureAgentPageState(args: JSONObject): JSONObject {
        val view = webView ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "当前没有可读取的页面")
        val current = session ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "当前没有页面会话")
        if (current.role !in setOf(RuntimeRole.STORE, RuntimeRole.WEB_APP)) {
            throw HermitException(ErrorCodes.ORIGIN_DENIED, "当前页面不开放开发状态读取")
        }
        val requestedAppId = args.optString("appId").takeIf { it.isNotBlank() }
        if (current.role == RuntimeRole.WEB_APP) {
            if (requestedAppId == null || requestedAppId != current.instance?.appId) {
                throw HermitException(ErrorCodes.INVALID_ARGUMENT, "必须指定当前前台 happ 的 appId")
            }
            if (current.instance?.launchChannel != LaunchChannel.DEV) {
                throw HermitException(ErrorCodes.DEV_MODE_REQUIRED, "页面状态只允许读取当前运行的 happ 开发副本")
            }
        } else if (requestedAppId != null) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "读取 HermitUI 状态时不能指定 appId")
        }
        val requestedKeys = args.optJSONArray("localStorageKeys") ?: JSONArray()
        val shellKeys = setOf("hermit.theme", "hermit.shell.view-state.v1")
        val storageKeys = JSONArray()
        for (index in 0 until requestedKeys.length()) {
            val key = requestedKeys.getString(index)
            if (current.role != RuntimeRole.STORE || key in shellKeys) storageKeys.put(key)
        }
        val captureCustomState = true
        val script = """
            (() => {
              const keys = $storageKeys, values = {}, truncatedKeys = [];
              for (const key of keys) {
                try {
                  const value = localStorage.getItem(key);
                  if (typeof value === "string" && value.length > $MAX_STORAGE_VALUE_CHARS) {
                    values[key] = value.slice(0, $MAX_STORAGE_VALUE_CHARS); truncatedKeys.push(key);
                  } else values[key] = value;
                } catch (_) { values[key] = null; }
              }
              let appState = null, appStateError = null;
              if ($captureCustomState) {
                try {
                  const hook = window.hermitDevState;
                  if (hook && typeof hook.capture === "function") appState = hook.capture();
                } catch (error) { appStateError = String(error && error.message || error); }
              }
              const result = {
                location: { href:location.href, origin:location.origin, pathname:location.pathname, search:location.search, hash:location.hash },
                document: { title:document.title, readyState:document.readyState, visibilityState:document.visibilityState, contentType:document.contentType, referrer:document.referrer },
                viewport: { width:innerWidth, height:innerHeight, devicePixelRatio:devicePixelRatio || 1 },
                scroll: { x:Math.max(0, Math.round(scrollX || 0)), y:Math.max(0, Math.round(scrollY || 0)) },
                historyLength:history.length,
                userAgent:navigator.userAgent,
                localStorage:values,
                truncatedLocalStorageKeys:truncatedKeys,
                appState,
                appStateError
              };
              let text = JSON.stringify(result);
              if (text.length > $MAX_PAGE_STATE_CHARS) {
                result.appState = null;
                result.appStateError = "页面自定义状态超过大小限制";
                text = JSON.stringify(result);
              }
              return text;
            })()
        """.trimIndent()
        val page = evaluatePageJson(view, script)
        return JSONObject()
            .put("role", current.role.name)
            .put("appId", current.instance?.appId ?: JSONObject.NULL)
            .put("launchChannel", current.instance?.launchChannel?.name?.lowercase() ?: JSONObject.NULL)
            .put("runtimeMode", current.instance?.runtimeMode?.name?.lowercase() ?: storeRunningMode)
            .put("page", page)
            .put("native", JSONObject()
                .put("url", view.url ?: JSONObject.NULL)
                .put("originalUrl", view.originalUrl ?: JSONObject.NULL)
                .put("canGoBack", view.canGoBack())
                .put("canGoForward", view.canGoForward()))
    }

    private fun reloadAppFromAgent(args: JSONObject): JSONObject {
        val appId = args.getString("appId")
        val current = session
        val view = webView
        if (visibleAppId != appId || current?.role != RuntimeRole.WEB_APP || view == null) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "只能刷新当前前台运行的 happ 开发副本")
        }
        if (current.instance?.launchChannel != LaunchChannel.DEV) {
            throw HermitException(ErrorCodes.DEV_MODE_REQUIRED, "刷新只允许调度当前运行的 happ 开发副本")
        }
        current.devRevision = args.optLong("revision").takeIf { args.has("revision") && !args.isNull("revision") }
        val strategy = reloadStrategy(args)
        val restoreStateJson = restoreStateJson(args)
        val postReloadScript = args.optString("postReloadScript").takeIf { args.has("postReloadScript") && it.isNotBlank() }
        if (postReloadScript != null) {
            if (postReloadScript.length > MAX_POST_RELOAD_SCRIPT_CHARS) {
                throw HermitException(ErrorCodes.QUOTA, "刷新后脚本超过大小限制")
            }
        }
        pendingAgentReload = PendingAgentReload(RuntimeRole.WEB_APP, appId, restoreStateJson, postReloadScript)
        val recreated = strategy == RELOAD_RECREATE
        if (recreated) showTarget(appId, launchedFromLibrary)
        else {
            view.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            view.reload()
        }
        return JSONObject().put("state", if (recreated) "runtime-recreated" else "reloading")
            .put("appId", appId).put("strategy", strategy).put("reusedWebView", !recreated)
            .put("restoreStateAccepted", restoreStateJson != null)
            .put("postReloadScriptAccepted", postReloadScript != null)
    }

    private suspend fun reloadShellFromAgent(args: JSONObject): JSONObject {
        val requestedMode = args.optString("runtimeMode", "current")
        val configuredMode = when (requestedMode) {
            "current" -> hermitApp.officialShell.mode()
            OfficialShellManager.Mode.ONLINE.value -> hermitApp.officialShell.activateOnline()
            OfficialShellManager.Mode.LOCAL.value -> hermitApp.officialShell.setMode(OfficialShellManager.Mode.LOCAL.value)
            else -> throw HermitException(ErrorCodes.INVALID_ARGUMENT, "未知界面模式")
        }
        val view = webView
        if (visibleAppId != null || session?.role != RuntimeRole.STORE || view == null) {
            return JSONObject().put("state", "not-visible").put("configuredMode", configuredMode.value)
        }
        val strategy = reloadStrategy(args)
        val restoreStateJson = restoreStateJson(args)
        val recreated = strategy == RELOAD_RECREATE || storeRunningMode != configuredMode.value
        pendingAgentReload = PendingAgentReload(RuntimeRole.STORE, null, restoreStateJson, null)
        if (recreated) showTarget(null, false)
        else {
            view.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            view.reload()
        }
        return JSONObject().put("state", if (recreated) "runtime-recreated" else "reloading")
            .put("configuredMode", configuredMode.value).put("strategy", strategy).put("reusedWebView", !recreated)
            .put("restoreStateAccepted", restoreStateJson != null)
    }

    private fun reloadStrategy(args: JSONObject): String {
        val strategy = args.optString("strategy", RELOAD_IN_PLACE)
        if (strategy !in setOf(RELOAD_IN_PLACE, RELOAD_RECREATE)) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "刷新策略必须是 reload 或 recreate")
        }
        return strategy
    }

    private fun restoreStateJson(args: JSONObject): String? {
        val text = args.optString("restoreStateJson").takeIf { args.has("restoreStateJson") && it.isNotBlank() } ?: return null
        if (text.length > MAX_PAGE_STATE_CHARS) throw HermitException(ErrorCodes.QUOTA, "页面恢复状态超过大小限制")
        val valid = runCatching {
            val tokener = JSONTokener(text)
            tokener.nextValue()
            tokener.nextClean() == '\u0000'
        }.getOrDefault(false)
        if (!valid) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "restoreStateJson 必须是完整 JSON")
        return text
    }

    private suspend fun evaluatePageJson(view: WebView, script: String): JSONObject = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { encoded ->
            val result = if (webView !== view) Result.failure(HermitException(ErrorCodes.CONFLICT, "页面会话已变化，请重新读取状态")) else runCatching {
                val text = JSONTokener(encoded ?: "null").nextValue() as? String
                    ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面状态返回格式无效")
                if (text.length > MAX_PAGE_STATE_CHARS) throw HermitException(ErrorCodes.QUOTA, "页面状态超过大小限制")
                JSONObject(text)
            }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    }

    private fun applyPendingAgentReload(view: WebView, current: RuntimeSession) {
        val pending = pendingAgentReload ?: return
        if (webView !== view || pending.role != current.role || pending.appId != current.instance?.appId) return
        pendingAgentReload = null
        val state = pending.restoreStateJson ?: "null"
        val postScript = pending.postReloadScript?.let(JSONObject::quote)
        val script = """
            (async () => {
              const state = $state;
              try {
                if (state !== null) {
                  const hook = window.hermitDevState;
                  if (hook && typeof hook.restore === "function") await hook.restore(state);
                  else window.dispatchEvent(new CustomEvent("hermitdevrestore", { detail:state }));
                }
                ${if (postScript == null) "" else "(0, eval)($postScript);"}
              } catch (error) { console.error("Hermit post-refresh action failed", error); }
            })()
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun resolveAppRoute(app: WebAppInstance, route: String?): String {
        if (route.isNullOrBlank()) return app.runtimeUrl
        val raw = route.trim()
        if (raw.startsWith("//") || raw.contains(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")) || raw.contains('\u0000') || raw.contains('\n') || raw.contains('\r')) {
            throw HermitException(ErrorCodes.ORIGIN_DENIED, "只能打开 happ 内的相对页面")
        }
        val resolved = app.runtimeUrl.toHttpUrl().resolve(raw)
            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面路由无效")
        if (resolved.scheme !in setOf("http", "https") || resolved.username.isNotEmpty() || resolved.password.isNotEmpty() ||
            WebAppInstance.originOf(resolved.toString()) != app.runtimeOrigin) {
            throw HermitException(ErrorCodes.ORIGIN_DENIED, "页面路由不能跨 Origin")
        }
        return resolved.toString()
    }

    private fun showTarget(appId: String?, fromLibrary: Boolean, route: String? = null) {
        launchedFromLibrary = fromLibrary
        val instance = appId?.let(hermitApp.registry::getInstance)
        if (appId != null && instance == null) {
            showNativeError("页面应用不存在", "这个桌面入口已经失效。", true)
            return
        }
        if (WebViewCompat.getCurrentWebViewPackage(this) == null) {
            showNativeError(getString(R.string.incompatible_title), runtimeCompatibilityMessage(), appId != null)
            return
        }
        if (instance != null && isLanUrl(instance.runtimeUrl)) {
            lifecycleScope.launch {
                if (ensureLanPermission()) createWebRuntime(instance, route) else {
                    showNativeError("需要局域网权限", "Android 已阻止访问此局域网页面。你可以重试或在系统设置中授权。", true)
                }
            }
        } else {
            createWebRuntime(instance, route)
        }
    }

    @SuppressLint("RequiresFeature", "MissingOnRenderProcessGone")
    private fun createWebRuntime(instance: WebAppInstance?, route: String? = null) {
        destroyRuntime()
        val installedRelease = instance?.activeReleaseId?.let(hermitApp.registry::getRelease)
        val packageManifest = installedRelease?.let { release ->
            runCatching { PackageManifestReader.read(hermitApp.installer.releaseWebRoot(release)) }.getOrNull()
        }
        applyDisplayPolicy(packageManifest, forcePortrait = instance == null)
        val forcedLocalStore = instance == null && forceLocalStoreOnce
        if (instance == null) forceLocalStoreOnce = false
        val onlineStore = instance == null && !forcedLocalStore && hermitApp.officialShell.mode() == OfficialShellManager.Mode.ONLINE
        val role = if (instance == null) RuntimeRole.STORE else RuntimeRole.WEB_APP
        val origin = instance?.runtimeOrigin ?: if (onlineStore) OfficialShellManager.OFFICIAL_ORIGIN else STORE_ORIGIN
        if (instance == null) storeRunningMode = if (onlineStore) "online" else if (forcedLocalStore) "local-fallback" else "local"
        // Hermit intentionally uses Android WebView's shared default profile. Standard
        // same-origin rules then allow pages on the same origin to share site data.
        // Hermit's native records, files and grants remain isolated by appId.
        val profileName = "hermit-shared"
        val release = instance?.takeIf { it.launchChannel == LaunchChannel.DEV || it.runtimeMode == HappRuntimeMode.LOCAL }
            ?.activeReleaseId?.let(hermitApp.registry::getRelease)
        if (instance?.runtimeMode == HappRuntimeMode.LOCAL && release == null) {
            showNativeError("本地代码不可用", "活动版本不存在或已损坏。请从应用库重新导入。", true)
            return
        }
        val devWorkspace = instance?.takeIf { it.launchChannel == LaunchChannel.DEV }
            ?.let { hermitApp.registry.getDevWorkspace(it.appId) }
        if (instance?.launchChannel == LaunchChannel.DEV && devWorkspace == null) {
            showNativeError("开发副本不可用", "开发工作副本不存在或已损坏，请返回应用库重建。", true)
            return
        }
        val devConfig = instance?.takeIf { it.launchChannel == LaunchChannel.DEV }
            ?.let { runCatching { hermitApp.devWorkspaces.runtimeConfig(it.appId) }.getOrElse { error ->
                showNativeError("开发副本不可用", error.message ?: "开发工作副本无法读取。", true)
                return
            } }
        val currentSession = RuntimeSession(role, instance, release, origin, profileName, devWorkspace?.revision)
        release?.releaseId?.let(hermitApp.installer::acquireRelease)
        val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val webMessage = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val nativeDocumentBootstrap = documentStart && webMessage
        val legacyBootstrap = assets.open("bridge/legacy-bootstrap.js").bufferedReader().use { it.readText() }
        val sdk = listOf("bridge/icons.js", "bridge/hermit-v1.js").joinToString("\n") { path ->
            assets.open(path).bufferedReader().use { it.readText() }
        }
        val localRuntimeGuard = if (release == null) "" else """
            (()=>{if(!('serviceWorker' in navigator))return;try{navigator.serviceWorker.getRegistrations().then(v=>v.forEach(r=>r.unregister()));Object.defineProperty(ServiceWorkerContainer.prototype,'register',{value:()=>Promise.reject(new DOMException('Service Worker is disabled for local happ releases','NotSupportedError'))});}catch(_){}})();
        """.trimIndent()
        val crossOriginGuard = if (instance?.liveUrl == null || instance.allowCrossOriginNetwork) "" else """
            (()=>{const own=${JSONObject.quote(origin)};const check=v=>{const u=new URL(String(v),location.href);if(u.origin!==own)throw new DOMException('Hermit blocked an active cross-Origin request','SecurityError');return v;};
            if(window.fetch){const f=window.fetch;window.fetch=(v,o)=>{try{check(v instanceof Request?v.url:v);return f.call(window,v,o)}catch(e){return Promise.reject(e)}}}
            if(window.XMLHttpRequest){const o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u,...r){check(u);return o.call(this,m,u,...r)}}
            for(const n of ['WebSocket','EventSource','Worker','SharedWorker']){const C=window[n];if(!C)continue;const G=function(u,...r){check(u);return Reflect.construct(C,[u,...r],new.target||C)};G.prototype=C.prototype;Object.setPrototypeOf(G,C);window[n]=G}
            addEventListener('submit',e=>{try{check(e.target.action)}catch(x){e.preventDefault();e.stopImmediatePropagation();throw x}},true);
            })();
        """.trimIndent()
        val devRuntime = if (devWorkspace == null) "" else """
            (()=>{
              const revision=()=>Number(document.querySelector('meta[name="hermit-dev-revision"]')?.content||0);
              const report=()=>requestAnimationFrame(()=>requestAnimationFrame(()=>window.hermit?.call('runtime.devReady',{revision:revision(),url:location.href}).catch(()=>{})));
              addEventListener('DOMContentLoaded',report,{once:true});
              window.__hermitDevRefreshCss=async(paths,next)=>{
                const wanted=new Set(paths.map(path=>String(path).replace(/^\\//,'')));
                const links=[...document.querySelectorAll('link[rel~="stylesheet"][href]')].filter(link=>{
                  try{const path=decodeURIComponent(new URL(link.href,location.href).pathname);return [...wanted].some(item=>path==='/' + item||path.endsWith('/' + item))}catch(_){return false}
                });
                if(!links.length){location.reload();return}
                const loaded=await Promise.all(links.map(link=>new Promise(resolve=>{
                  const copy=link.cloneNode();const url=new URL(link.href,location.href);url.searchParams.set('__hermit_dev',String(next));copy.href=url.href;
                  copy.onload=()=>{link.remove();resolve(true)};copy.onerror=()=>{copy.remove();resolve(false)};link.after(copy);
                })));
                if(loaded.some(value=>!value)){location.reload();return}
                const meta=document.querySelector('meta[name="hermit-dev-revision"]');if(meta)meta.content=String(next);
                report();
              };
            })();
        """.trimIndent()
        val sessionSdk = "$localRuntimeGuard\n$crossOriginGuard\n$devRuntime\n$sdk"
        val compatibilitySource = "$legacyBootstrap\n$sessionSdk"
        val gateway = if (instance == null && !onlineStore) {
            if (hermitApp.officialShell.hasDownloadedShell()) {
                LocalContentGateway.forRelease(this, STORE_URL, hermitApp.officialShell.downloadedRoot, "index.html",
                    historyFallback = false, injectRuntime = !nativeDocumentBootstrap, allowNetworkFallback = false)
            } else {
                LocalContentGateway.forStore(this, injectRuntime = !nativeDocumentBootstrap)
            }
        } else if (release != null && devWorkspace != null && devConfig != null) {
            LocalContentGateway.forDev(this, instance!!.runtimeUrl, devConfig.entryPath,
                devConfig.routing == "history", injectRuntime = !nativeDocumentBootstrap,
                allowNetworkFallback = instance.liveUrl != null,
                revisionProvider = { hermitApp.registry.getDevWorkspace(instance.appId)?.revision },
                resolver = { path -> runCatching { hermitApp.devWorkspaces.resolve(instance.appId, path) }.getOrNull() })
        } else if (release != null) {
            val webRoot = hermitApp.installer.releaseWebRoot(release)
            LocalContentGateway.forRelease(this, instance!!.runtimeUrl, webRoot, release.entryPath,
                release.routing == "history", injectRuntime = !nativeDocumentBootstrap,
                allowNetworkFallback = instance.liveUrl != null)
        } else null

        val view = WebView(this)
        configure(view)
        if (onlineStore || devWorkspace != null) view.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        val shellLoadingView = if (onlineStore) TextView(this).apply {
            text = "正在加载官网界面…"
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.hermit_background))
        } else null
        if (shellLoadingView != null) view.visibility = View.INVISIBLE
        val sharedAssets = SharedAssetGateway(this, origin, compatibilitySource.takeIf { !nativeDocumentBootstrap })
        val networkPolicy = instance?.takeIf { it.liveUrl != null }
            ?.let { RuntimeNetworkPolicy(it.runtimeUrl, it.allowCrossOriginNetwork) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    sharedAssets.intercept(request) ?: gateway?.intercept(request) ?: networkPolicy?.intercept(request)
            })
        }
        val controller: PageBridge = if (webMessage) {
            BridgeController(view, currentSession, this, sessionSdk)
        } else {
            LegacyBridgeController(view, currentSession, this)
        }
        controller.install()
        var onlineFailureHandled = false
        fun fallBackToLocalShell(message: String) {
            if (!onlineStore || onlineFailureHandled || webView !== view) return
            onlineFailureHandled = true
            forceLocalStoreOnce = true
            pendingStoreScript = "window.hermitShellUnavailable && window.hermitShellUnavailable(${JSONObject.quote(message)})"
            root.post { showTarget(null, false) }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                controller.navigationStarted()
            }
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (release == null || gateway?.wasLocalMainFrame(url) == true) controller.navigationCommitted()
                if (view != null && webView === view) {
                    view.visibility = View.VISIBLE
                    shellLoadingView?.let(root::removeView)
                }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if ((release == null || gateway?.wasLocalMainFrame(url) == true) && controller.recoverAfterUncommittedNavigation()) {
                    view.evaluateJavascript("window.dispatchEvent(new Event('__hermitrecover'))", null)
                }
                if (!nativeDocumentBootstrap) {
                    view.evaluateJavascript("typeof window.hermit") { type ->
                        if (webView === view && type != "\"object\"") view.evaluateJavascript(compatibilitySource, null)
                    }
                }
                if (currentSession.role == RuntimeRole.STORE) pendingStoreScript?.let { script ->
                    pendingStoreScript = null
                    view.evaluateJavascript(script, null)
                }
                applyPendingAgentReload(view, currentSession)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (devWorkspace != null) hermitApp.agentServer.recordDevDiagnostic(instance!!.appId, currentSession.devRevision,
                    if (request.isForMainFrame) "navigation-error" else "resource-error", error.description.toString(), request.url.toString())
                if (request.isForMainFrame) {
                    if (instance?.runtimeMode == HappRuntimeMode.LIVE) root.post {
                        showNativeError("实时页面无法访问", "请返回 HermitApp，在此 happ 的设置中关闭线上实时运行。", true)
                    } else fallBackToLocalShell("官网实时界面暂时无法访问，当前使用本地版。")
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (devWorkspace != null) hermitApp.agentServer.recordDevDiagnostic(instance!!.appId, currentSession.devRevision,
                    if (request.isForMainFrame) "navigation-http" else "resource-http", "HTTP ${response.statusCode}", request.url.toString())
                if (request.isForMainFrame && response.statusCode >= 400) {
                    if (instance?.runtimeMode == HappRuntimeMode.LIVE) root.post {
                        showNativeError("实时页面无法访问", "服务器返回 HTTP ${response.statusCode}。请返回 HermitApp 关闭此 happ 的线上实时运行。", true)
                    } else fallBackToLocalShell("官网实时界面返回 HTTP ${response.statusCode}，当前使用本地版。")
                }
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                sharedAssets.intercept(request) ?: gateway?.intercept(request) ?: networkPolicy?.intercept(request)
                ?: super.shouldInterceptRequest(view, request)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val target = request.url
                if (request.isForMainFrame && target.scheme in setOf("http", "https")) {
                    if (originOf(target) == origin) return false
                    if (instance?.liveUrl != null && instance.allowCrossOriginNetwork) return false
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
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (devWorkspace != null && consoleMessage.messageLevel() in setOf(ConsoleMessage.MessageLevel.WARNING, ConsoleMessage.MessageLevel.ERROR)) {
                    hermitApp.agentServer.recordDevDiagnostic(instance!!.appId, currentSession.devRevision,
                        "console-${consoleMessage.messageLevel().name.lowercase()}", consoleMessage.message(), consoleMessage.sourceId())
                }
                return super.onConsoleMessage(consoleMessage)
            }
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
        shellLoadingView?.let { root.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        if (instance?.liveUrl != null && instance.allowCrossOriginNetwork) {
            root.addView(android.widget.TextView(this).apply {
                text = "已允许此 happ 主动访问其他 Origin"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(187, 91, 25))
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(12, 8, 12, 8)
            }, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        }
        if (devWorkspace != null) {
            root.addView(View(this).apply {
                setBackgroundColor(Color.rgb(41, 151, 255))
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(-1, (3 * resources.displayMetrics.density).toInt().coerceAtLeast(3), android.view.Gravity.TOP))
            view.contentDescription = "运行开发副本"
        }
        val targetUrl = instance?.let { resolveAppRoute(it, route) } ?: if (onlineStore) OfficialShellManager.ONLINE_URL else STORE_URL
        if (onlineStore) view.loadUrl(targetUrl, mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")) else view.loadUrl(targetUrl)
    }

    @SuppressLint("RequiresFeature", "MissingOnRenderProcessGone")
    private fun showSupportBrowser() {
        destroyRuntime()
        applyDisplayPolicy(null, forcePortrait = true)
        val currentSession = RuntimeSession(RuntimeRole.SUPPORT, null, null, SUPPORT_ORIGIN, "hermit-shared")
        val view = WebView(this)
        configure(view)
        view.webViewClient = object : WebViewClient() {
            private var failed = false
            private fun fail(message: String) {
                if (failed || webView !== view) return
                failed = true; root.post { showSupportUnavailable(message) }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val target = request.url
                if (request.isForMainFrame && originOf(target) == SUPPORT_ORIGIN) return false
                if (request.isForMainFrame && target.scheme in setOf("http", "https", "mailto", "tel")) openExternal(target)
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("支持服务暂时不可用，请稍后再试。")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) fail("支持服务暂时不可用（HTTP ${response.statusCode}）。")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("页面运行服务暂时不可用，请稍后再试。"); return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
        }
        webView = view; session = currentSession; bridge = null; visibleAppId = null; launchedFromLibrary = true
        root.removeAllViews(); root.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1)); view.loadUrl(SUPPORT_URL)
    }

    private fun applyDisplayPolicy(manifest: PackageManifest?, forcePortrait: Boolean = false) {
        keyboardOverlaysContent = manifest?.keyboardMode == "overlay"
        window.setSoftInputMode(
            if (keyboardOverlaysContent) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        requestedOrientation = when {
            forcePortrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            manifest?.displayOrientation == "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            manifest?.displayOrientation == "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showSupportUnavailable(message: String) {
        pendingStoreScript = "window.hermitSupportUnavailable && window.hermitSupportUnavailable(${JSONObject.quote(message)})"
        showTarget(null, false)
    }

    private suspend fun supportReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(7, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build()
            fun accepted(request: Request): Boolean = client.newCall(request).execute().use { response ->
                response.isSuccessful && response.request.url.isHttps
            }
            accepted(Request.Builder().url(SUPPORT_URL).head().build()) ||
                accepted(Request.Builder().url(SUPPORT_URL).header("Range", "bytes=0-0").get().build())
        }.getOrDefault(false)
    }

    private suspend fun scanQrLink(): JSONObject = suspendCancellableCoroutine { continuation ->
        if (pendingQrScan != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有扫码操作")))
            return@suspendCancellableCoroutine
        }
        pendingQrScan = continuation
        continuation.invokeOnCancellation { if (pendingQrScan === continuation) pendingQrScan = null }
        qrScanLauncher.launch(Intent(this, QrScannerActivity::class.java))
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
        session?.sessionId?.let {
            permissionBroker.clearSession(it)
            audio.cancelSession(it)
            nativeHttp.closeAll(it)
        }
        bridge?.close()
        pendingShellPrompt?.let { pending ->
            pendingShellPrompt = null
            if (pending.continuation.isActive) pending.continuation.cancel()
        }
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
        sensors.cancelAll()
        wifi.cancelAll()
        bluetooth.cancelAll()
        device.cancelAll()
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
        "host.dialog.resolve" -> {
            val pending = pendingShellPrompt
                ?: throw HermitException(ErrorCodes.SESSION_EXPIRED, "确认操作已经结束")
            val token = params.optString("token")
            val choice = params.optString("choice")
            if (token != pending.token || choice !in pending.choices) {
                throw HermitException(ErrorCodes.INVALID_ARGUMENT, "确认结果无效")
            }
            pendingShellPrompt = null
            if (pending.continuation.isActive) pending.continuation.resume(choice)
            JSONObject().put("resolved", true)
        }
        "host.apps.list" -> withContext(Dispatchers.IO) {
            val apps = hermitApp.registry.listInstances()
            val pinStates = shortcuts.pinStates(apps.map { it.appId })
            JSONObject().put("apps", JSONArray(apps.map { app ->
                val release = app.activeReleaseId?.let(hermitApp.registry::getRelease)
                app.toJson().put("activeVersion", if (release == null) JSONObject.NULL else JSONObject()
                    .put("code", release.versionCode ?: JSONObject.NULL)
                    .put("name", release.versionName ?: JSONObject.NULL))
                    .put("devWorkspace", hermitApp.devWorkspaces.status(app.appId))
                    .put("desktopShortcutState", pinStates[app.appId]?.value ?: ShortcutHost.PinState.UNKNOWN.value)
            }))
        }
        "host.apps.favorite" -> withContext(Dispatchers.IO) {
            hermitApp.registry.setFavorite(params.getString("appId"), params.getBoolean("favorite")).toJson()
        }
        "host.apps.setNotificationEnabled" -> withContext(Dispatchers.IO) {
            val updated = hermitApp.registry.setNotificationEnabled(params.getString("appId"), params.getBoolean("enabled"))
            if (!updated.notificationEnabled) hermitApp.notifications.dispatcher.cancelAll(updated.appId)
            hermitApp.notifications.scheduler.rebuild()
            updated.toJson()
        }
        "host.apps.setCrossOriginNetwork" -> withContext(Dispatchers.IO) {
            hermitApp.registry.setCrossOriginNetworkEnabled(params.getString("appId"), params.getBoolean("enabled")).toJson()
        }
        "host.apps.updateUrls" -> {
            val app = hermitApp.registry.getInstance(params.getString("appId"))
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            val live = params.optString("liveUrl").takeIf { params.has("liveUrl") && it.isNotBlank() }?.let(::normalizeUrl)
            val update = params.optString("updateUrl").takeIf { params.has("updateUrl") && it.isNotBlank() }?.let(::normalizeUrl)
            if (live != null && live != app.liveUrl && Uri.parse(live).scheme.equals("http", true)
                && !params.optBoolean("insecureConfirmed") && !confirmInsecureUrl(live)) {
                return JSONObject().put("cancelled", true)
            }
            if (live != null && isLanUrl(live) && !ensureLanPermission()) {
                throw HermitException(ErrorCodes.OS_PERMISSION_DENIED, "Android 未授予局域网权限")
            }
            val oldOrigin = app.liveUrl?.let { originOf(Uri.parse(it)) }
            val updated = withContext(Dispatchers.IO) { hermitApp.registry.updateUrls(app.appId, live, update) }
            if (oldOrigin != updated.liveUrl?.let { originOf(Uri.parse(it)) }) hermitApp.notifications.repository.clearEndpoint(app.appId)
            updated.toJson().put("cancelled", false)
        }
        "host.apps.scanQr" -> scanQrLink()
        "host.apps.pickIcon" -> {
            val uri = pickIcon() ?: return JSONObject().put("cancelled", true)
            JSONObject().put("cancelled", false).put("dataUrl", iconSourceDataUrl(uri))
        }
        "host.apps.pickDirectory" -> {
            val uri = pickTree() ?: return JSONObject().put("cancelled", true)
            val token = UUID.randomUUID().toString()
            pendingDirectoryImports.clear()
            pendingDirectoryImports[token] = uri
            inspectDirectory(uri).put("cancelled", false).put("token", token)
        }
        "host.apps.confirmDirectory" -> {
            val token = params.getString("token")
            val uri = pendingDirectoryImports.remove(token)
                ?: throw HermitException(ErrorCodes.SESSION_EXPIRED, "目录选择已失效，请重新选择")
            val result = hermitApp.installer.installTree(uri, params.optString("name").takeIf { it.isNotBlank() }, ::chooseIdentityInstall)
            val updated = withContext(Dispatchers.IO) {
                val installed = hermitApp.registry.getInstance(result.appId)!!
                val icon = params.optString("iconDataUrl").takeIf { it.isNotBlank() } ?: installed.iconDataUrl
                val presented = hermitApp.registry.updatePresentation(result.appId, params.optString("name", "本地应用"), icon)
                hermitApp.registry.updateReleaseVersion(result.releaseId, params.optString("version").takeIf { it.isNotBlank() })
                if (params.optBoolean("favorite")) hermitApp.registry.setFavorite(result.appId, true) else presented
            }
            shortcuts.update(updated)
            JSONObject().put("cancelled", false).put("appId", result.appId).put("releaseId", result.releaseId)
        }
        "host.apps.installOnline" -> {
            val url = normalizeUrl(params.optString("url"))
            if (Uri.parse(url).scheme.equals("http", true)
                && !params.optBoolean("insecureConfirmed") && !confirmInsecureUrl(url)) {
                return JSONObject().put("cancelled", true)
            }
            if (isLanUrl(url) && !ensureLanPermission()) {
                throw HermitException(ErrorCodes.OS_PERMISSION_DENIED, "Android 未授予局域网权限")
            }
            val name = params.optString("name").takeIf { it.isNotBlank() }?.take(80)
            val installed = hermitApp.remoteInstaller.installOnline(url, name, ::chooseIdentityInstall)
            val updated = withContext(Dispatchers.IO) {
                val installedInstance = hermitApp.registry.getInstance(installed.appId)!!
                val presented = hermitApp.registry.updatePresentation(
                    installed.appId,
                    name ?: installedInstance.name,
                    params.optString("iconDataUrl").takeIf { it.isNotBlank() } ?: installedInstance.iconDataUrl,
                )
                if (params.optBoolean("favorite")) hermitApp.registry.setFavorite(installed.appId, true) else presented
            }
            shortcuts.update(updated)
            updated.toJson().put("cancelled", false).put("installStrategy", installed.strategy).put("installKind", installed.kind)
        }
        "host.apps.importZip" -> {
            val uri = pickZip() ?: return JSONObject().put("cancelled", true)
            val result = hermitApp.installer.installUri(uri, params.optString("name").takeIf { it.isNotBlank() }, ::chooseIdentityInstall)
            if (params.optBoolean("favorite")) withContext(Dispatchers.IO) { hermitApp.registry.setFavorite(result.appId, true) }
            JSONObject().put("cancelled", false).put("appId", result.appId).put("releaseId", result.releaseId)
        }
        "host.apps.importDirectory" -> {
            val uri = pickTree() ?: return JSONObject().put("cancelled", true)
            val result = hermitApp.installer.installTree(uri, params.optString("name").takeIf { it.isNotBlank() }, ::chooseIdentityInstall)
            if (params.optBoolean("favorite")) withContext(Dispatchers.IO) { hermitApp.registry.setFavorite(result.appId, true) }
            JSONObject().put("cancelled", false).put("appId", result.appId).put("releaseId", result.releaseId)
        }
        "host.apps.installPackageUrl" -> {
            val result = hermitApp.remoteInstaller.installHttps(params.getString("url"), params.optString("name").takeIf { it.isNotBlank() },
                identityChoice = ::chooseIdentityInstall)
            if (params.optBoolean("favorite")) withContext(Dispatchers.IO) { hermitApp.registry.setFavorite(result.appId, true) }
            JSONObject().put("appId", result.appId).put("releaseId", result.releaseId)
        }
        "host.apps.installGitHub" -> {
            val result = hermitApp.remoteInstaller.installGitHub(
                params.getString("owner"), params.getString("repo"), params.optString("ref", "main"),
                params.optString("path"), params.optString("name").takeIf { it.isNotBlank() }, identityChoice = ::chooseIdentityInstall
            )
            if (params.optBoolean("favorite")) withContext(Dispatchers.IO) { hermitApp.registry.setFavorite(result.appId, true) }
            JSONObject().put("appId", result.appId).put("releaseId", result.releaseId)
        }
        "host.apps.updateFromSource" -> {
            val result = hermitApp.remoteInstaller.update(params.getString("appId"))
            JSONObject().put("appId", result.appId).put("releaseId", result.releaseId).put("treeHash", result.treeHash)
        }
        "host.apps.reinstall" -> {
            val result = hermitApp.remoteInstaller.reinstall(params.getString("appId"))
            JSONObject().put("appId", result.appId).put("releaseId", result.releaseId).put("treeHash", result.treeHash)
        }
        "host.apps.update" -> {
            val app = hermitApp.registry.getInstance(params.getString("appId"))
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            val name = params.optString("name", app.name).trim().takeIf { it.isNotBlank() }
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "名称不能为空")
            val url = params.optString("url").takeIf { params.has("url") && it.isNotBlank() }?.let(::normalizeUrl) ?: app.liveUrl
            if (url != null && url != app.liveUrl && Uri.parse(url).scheme.equals("http", true) && !confirmInsecureUrl(url)) {
                return JSONObject().put("cancelled", true)
            }
            hermitApp.registry.updateInstance(app.appId, name, url, null)
            if (url?.let { originOf(Uri.parse(it)) } != app.liveUrl?.let { originOf(Uri.parse(it)) }) {
                hermitApp.notifications.repository.clearEndpoint(app.appId)
            }
            val updated = hermitApp.registry.getInstance(app.appId)!!
            processPendingProfileCleanup()
            shortcuts.update(updated)
            updated.toJson()
        }
        "host.apps.updatePresentation" -> {
            val app = hermitApp.registry.getInstance(params.getString("appId"))
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            val name = params.optString("name", app.name).trim().takeIf { it.isNotBlank() }
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "名称不能为空")
            val iconDataUrl = if (params.has("iconDataUrl")) validateIconDataUrl(params.optString("iconDataUrl")) else app.iconDataUrl
            val updated = withContext(Dispatchers.IO) { hermitApp.registry.updatePresentation(app.appId, name, iconDataUrl) }
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
        "host.apps.enterDev" -> {
            val appId = params.getString("appId")
            withContext(Dispatchers.IO) { hermitApp.devWorkspaces.enter(appId) }
        }
        "host.apps.leaveDev" -> {
            val appId = params.getString("appId")
            withContext(Dispatchers.IO) { hermitApp.devWorkspaces.leave(appId) }
        }
        "host.apps.resetDev" -> {
            val appId = params.getString("appId")
            withContext(Dispatchers.IO) { hermitApp.devWorkspaces.reset(appId) }
        }
        "host.apps.exportDev" -> {
            val appId = params.getString("appId")
            val versionCode = params.getLong("versionCode")
            val versionName = params.getString("versionName")
            val status = hermitApp.registry.getDevWorkspace(appId)
                ?: throw HermitException(ErrorCodes.DEV_MODE_REQUIRED, "开发工作副本不存在")
            val built = withContext(Dispatchers.IO) { hermitApp.devWorkspaces.build(appId, status.revision, versionCode, versionName) }
            val artifact = hermitApp.devWorkspaces.artifact(built.getString("buildId"))
                ?: throw HermitException(ErrorCodes.STORAGE, "发布包生成失败")
            val uri = createFileDocument(built.getString("fileName")) ?: return JSONObject().put("cancelled", true)
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "w")?.use { output -> artifact.file.inputStream().use { it.copyTo(output) } }
                    ?: throw HermitException(ErrorCodes.STORAGE, "无法写入发布包")
            }
            built.put("cancelled", false)
        }
        "host.apps.setRuntimeMode" -> {
            val appId = params.getString("appId")
            val requested = when (params.getString("runtimeMode")) {
                "local" -> HappRuntimeMode.LOCAL
                "live" -> HappRuntimeMode.LIVE
                else -> throw HermitException(ErrorCodes.INVALID_ARGUMENT, "运行方式必须是 local 或 live")
            }
            if (requested == HappRuntimeMode.LIVE) {
                if (hermitApp.registry.getInstance(appId)?.launchChannel == LaunchChannel.DEV) {
                    withContext(Dispatchers.IO) { hermitApp.devWorkspaces.leave(appId) }
                }
                val deployStatus = hermitApp.developmentServer.status()
                if (deployStatus.optBoolean("active") && deployStatus.optString("appId") == appId) {
                    hermitApp.developmentServer.stop("Runtime changed")
                }
            }
            val updated = withContext(Dispatchers.IO) {
                runCatching { hermitApp.registry.setRuntimeMode(appId, requested) }
                    .getOrElse { throw HermitException(ErrorCodes.CONFLICT, it.message ?: "无法切换运行方式") }
            }
            processPendingProfileCleanup()
            shortcuts.update(updated)
            updated.toJson()
        }
        "host.apps.rollback" -> {
            val appId = params.getString("appId")
            val app = hermitApp.registry.getInstance(appId)
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            if (app.activeReleaseId == null) throw HermitException(ErrorCodes.CONFLICT, "此 happ 没有本地版本")
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
            hermitApp.agentServer.stop("正在导出备份")
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
            hermitApp.agentServer.stop("正在替换应用数据")
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
            hermitApp.notifications.dispatcher.deleteChannel(appId)
            withContext(Dispatchers.IO) { hermitApp.notifications.repository.deleteInstance(appId) }
            withContext(Dispatchers.IO) { hermitApp.devWorkspaces.delete(appId) }
            val removed = withContext(Dispatchers.IO) { hermitApp.registry.deleteInstance(appId) }
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            shortcuts.disable(appId)
            withContext(Dispatchers.IO) {
                hermitApp.installer.deleteAppFiles(appId)
                hermitApp.registry.finishDelete(appId)
            }
            hermitApp.notifications.scheduler.rebuild()
            JSONObject().put("removed", true).put("appId", removed.appId)
        }
        "host.apps.archive" -> {
            val appId = params.getString("appId")
            withContext(Dispatchers.IO) { hermitApp.devWorkspaces.delete(appId) }
            val archived = withContext(Dispatchers.IO) { hermitApp.registry.archiveInstance(appId) }
                ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            shortcuts.disable(appId)
            hermitApp.notifications.dispatcher.cancelAll(appId)
            withContext(Dispatchers.IO) { hermitApp.installer.deleteAppCode(appId) }
            hermitApp.notifications.scheduler.rebuild()
            JSONObject().put("archived", true).put("instanceId", archived.appId)
        }
        "host.deploy.start" -> {
            val mode = params.optString("mode", "adb")
            if (mode == "lan" && !ensureLanPermission()) throw HermitException(ErrorCodes.OS_PERMISSION_DENIED, "Android 未授予局域网权限")
            hermitApp.agentServer.stop("已切换到单应用部署")
            hermitApp.developmentServer.start(params.optString("appId"), mode)
        }
        "host.deploy.stop" -> hermitApp.developmentServer.stop("Stopped by user")
        "host.deploy.status" -> hermitApp.developmentServer.status()
        "host.agent.start" -> {
            val usbOnly = params.optString("mode") == "usb"
            if (!usbOnly && !ensureLanPermission()) throw HermitException(ErrorCodes.OS_PERMISSION_DENIED, "Android 未授予局域网权限")
            hermitApp.developmentServer.stop("已切换到智能体开发模式")
            hermitApp.agentServer.start(if (usbOnly) "127.0.0.1" else params.optString("address").takeIf { it.isNotBlank() })
        }
        "host.agent.stop" -> hermitApp.agentServer.stop("由用户停止")
        "host.agent.status" -> hermitApp.agentServer.status()
        "host.agent.resetPassword" -> {
            hermitApp.developmentServer.stop("开发凭据已重置")
            hermitApp.agentServer.resetPassword(params.optString("password").takeIf { it.isNotBlank() })
        }
        "host.shell.status" -> hermitApp.officialShell.status(storeRunningMode)
        "host.shell.setMode" -> {
            val requested = params.getString("mode")
            val selected = if (requested == OfficialShellManager.Mode.ONLINE.value) {
                hermitApp.officialShell.activateOnline()
            } else {
                hermitApp.officialShell.setMode(requested)
            }
            hermitApp.officialShell.status(selected.value).also { root.postDelayed({ showTarget(null, false) }, 100) }
        }
        "host.shell.reload" -> hermitApp.officialShell.status(storeRunningMode).put("reloading", true).also {
            root.postDelayed({ showTarget(null, false) }, 100)
        }
        "host.shell.updateLocal" -> {
            val result = hermitApp.officialShell.updateLocal().put("runningMode", storeRunningMode)
            if (hermitApp.officialShell.mode() == OfficialShellManager.Mode.LOCAL) root.postDelayed({ showTarget(null, false) }, 100)
            result
        }
        "host.support.check" -> JSONObject().put("available", supportReachable())
        "host.support.open" -> JSONObject().put("opened", true).also { root.postDelayed({ showSupportBrowser() }, 120) }
        "host.about.info" -> JSONObject().put("name", "Hermit").put("version", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE).put("applicationId", packageName)
            .put("author", "10knet·zhyuzh3d").put("repository", REPOSITORY_URL).put("support", SUPPORT_URL)
        "host.about.openRepository" -> JSONObject().put("opened", true).also { openExternal(Uri.parse(REPOSITORY_URL)) }
        "host.voice.status" -> JSONObject().put("tts", tts.providerStatus()).put("speech", speech.providerStatus())
        "host.voice.ttsVoices" -> tts.providerVoices(params.optString("engineId").takeIf { !params.isNull("engineId") && it.isNotBlank() })
        "host.voice.configure" -> {
            val result = JSONObject()
            params.optJSONObject("tts")?.let { result.put("tts", tts.configure(it)) }
            params.optJSONObject("speech")?.let { result.put("speech", speech.configure(it)) }
            if (result.length() == 0) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "没有需要保存的语音设置")
            result
        }
        "host.voice.testTts" -> tts.speak(JSONObject().put("text", params.optString("text", "Hermit 系统朗读测试"))) { _, _ -> }
        "host.voice.openSettings" -> device.openSettings(when (params.optString("type")) {
            "tts" -> "tts"
            "speech" -> "voiceInput"
            else -> throw HermitException(ErrorCodes.INVALID_ARGUMENT, "语音设置类型必须是 tts 或 speech")
        })
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
                    .put("bridgeMode", bridgeMode())
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
            assets.open("third-party-notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } +
                "\n\nFont Awesome Free 7.3.1 — https://fontawesome.com\n" +
                assets.open("shared/fontawesome/LICENSE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
            else -> throw HermitException(ErrorCodes.UNSUPPORTED, "未知管理方法：$method")
        }
    }

    private suspend fun dispatchPublic(session: RuntimeSession, method: String, params: JSONObject): Any? {
        val app = session.instance
        return when (method) {
            "runtime.info" -> JSONObject().put("apiMajor", 1).put("apiMinor", 10)
                .put("sessionId", session.sessionId).put("appId", session.appId)
                .put("role", session.role.name.lowercase()).put("webViewPackage", WebViewCompat.getCurrentWebViewPackage(this)?.versionName)
                .put("runtimeMode", app?.runtimeMode?.name?.lowercase() ?: if (storeRunningMode == "online") "live" else "local")
                .put("launchChannel", app?.launchChannel?.name?.lowercase() ?: "stable")
                .put("devRevision", session.devRevision ?: JSONObject.NULL)
                .put("bridgeMode", bridgeMode()).put("isolatedProfiles", false)
                .put("siteDataPolicy", "shared-by-origin")
            "runtime.capabilities" -> capabilityDescriptors(session)
            "app.info" -> app?.toJson() ?: JSONObject().put("appId", RuntimeSession.STORE_APP_ID).put("name", "Hermit 应用库")
            "runtime.devReady" -> {
                val revision = params.getLong("revision")
                if (app?.launchChannel != LaunchChannel.DEV || session.devRevision != revision) {
                    throw HermitException(ErrorCodes.SESSION_EXPIRED, "过期开发页面回执已忽略")
                }
                val url = params.optString("url", webView?.url ?: app.runtimeUrl)
                hermitApp.agentServer.reportDevRender(app.appId, revision, url)
                JSONObject().put("recorded", true).put("revision", revision)
            }
            "app.ready" -> JSONObject().put("recorded", true).put("at", System.currentTimeMillis()).also {
                if (app != null) pendingOpenedNotification?.let { opened ->
                    pendingOpenedNotification = null
                    root.post { if (this.session === session) bridge?.emit("notifications.opened", opened) }
                }
            }
            "app.checkUpdate" -> JSONObject().put("updateUrl", app?.updateUrl ?: JSONObject.NULL)
                .put("canCheck", app?.updateUrl != null || app?.sourceAdapter in setOf("online-manifest", "online-descriptor", "https-package", "github"))
            "app.reload" -> {
                root.postDelayed({ showTarget(app?.appId, launchedFromLibrary) }, 80)
                JSONObject().put("reloading", true)
            }
            "app.setRuntimeMode" -> requireApp(app).let { target ->
                val requested = when (params.getString("runtimeMode")) {
                    "local" -> HappRuntimeMode.LOCAL
                    "live" -> HappRuntimeMode.LIVE
                    else -> throw HermitException(ErrorCodes.INVALID_ARGUMENT, "运行方式必须是 local 或 live")
                }
                val changed = target.runtimeMode != requested
                if (changed && requested == HappRuntimeMode.LIVE) {
                    if (target.launchChannel == LaunchChannel.DEV) {
                        withContext(Dispatchers.IO) { hermitApp.devWorkspaces.leave(target.appId) }
                    }
                    val deployStatus = hermitApp.developmentServer.status()
                    if (deployStatus.optBoolean("active") && deployStatus.optString("appId") == target.appId) {
                        hermitApp.developmentServer.stop("Runtime changed")
                    }
                }
                val updated = withContext(Dispatchers.IO) {
                    runCatching { hermitApp.registry.setRuntimeMode(target.appId, requested) }
                        .getOrElse { throw HermitException(ErrorCodes.CONFLICT, it.message ?: "无法切换运行方式") }
                }
                if (changed) {
                    processPendingProfileCleanup()
                    shortcuts.update(updated)
                    root.postDelayed({ showTarget(updated.appId, launchedFromLibrary) }, 180)
                }
                updated.toJson()
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
            "tts.availability" -> tts.availability()
            "tts.preferences" -> tts.preferences()
            "tts.voices" -> tts.voices()
            "tts.languageAvailability" -> tts.languageAvailability(params.getString("language"))
            "tts.speak" -> {
                permissionBroker.require(session, "tts.speak", "使用系统 TTS 扬声器朗读文本")
                tts.speak(params) { event, data -> if (this.session === session) bridge?.emit(event, data) }
            }
            "tts.stop" -> tts.stop()
            "tts.export" -> requireApp(app).let {
                permissionBroker.require(session, "tts.speak", "使用系统 TTS 生成语音文件")
                val output = File(cacheDir, "tts-${UUID.randomUUID()}.wav")
                try {
                    tts.synthesize(params, output)
                    withContext(Dispatchers.IO) { output.inputStream().use { stream ->
                        files.import(it.appId, session.dataGeneration!!, stream, params.optString("name", "speech.wav"), "audio/wav")
                    } }
                } finally { output.delete() }
            }
            "audio.startRecording" -> requireApp(app).let {
                if (!audio.microphoneAvailable()) throw HermitException(ErrorCodes.UNSUPPORTED, "设备没有可用麦克风")
                permissionBroker.require(session, "microphone.record", "使用麦克风录制音频", PermissionBroker.MICROPHONE_PERMISSIONS)
                audio.startRecording(session.sessionId, params) { event, data ->
                    if (this.session === session) bridge?.emit(event, data)
                }
            }
            "audio.stopRecording" -> requireApp(app).let {
                val recorded = audio.stopRecording(session.sessionId, params.optString("recordingId").takeIf { id -> id.isNotBlank() })
                try {
                    withContext(Dispatchers.IO) {
                        recorded.file.inputStream().use { stream ->
                            files.import(
                                it.appId,
                                session.dataGeneration!!,
                                stream,
                                params.optString("name", "recording.m4a"),
                                "audio/mp4",
                            ).put("durationMs", recorded.durationMs)
                        }
                    }
                } finally { recorded.file.delete() }
            }
            "audio.cancelRecording" -> audio.cancelRecording(
                session.sessionId,
                params.optString("recordingId").takeIf { it.isNotBlank() },
            )
            "audio.play" -> requireApp(app).let {
                withContext(Dispatchers.IO) {
                    files.open(it.appId, session.dataGeneration!!, params.getString("logicalFileId")).use { input ->
                        audio.play(session.sessionId, input, params) { event, data ->
                            if (this@MainActivity.session === session) bridge?.emit(event, data)
                        }
                    }
                }
            }
            "audio.stopPlayback" -> audio.stopPlayback(
                session.sessionId,
                params.optString("playbackId").takeIf { it.isNotBlank() },
            )
            "files.import" -> requireApp(app).let {
                val accept = params.optString("accept", "*/*").lowercase().trim()
                if (!accept.matches(Regex("[a-z0-9.+-]+/(?:[a-z0-9.+-]+|\\*)"))) {
                    throw HermitException(ErrorCodes.INVALID_ARGUMENT, "文件类型无效")
                }
                val uri = pickFile(accept) ?: return JSONObject().put("cancelled", true)
                val name = queryDisplayName(uri) ?: "file"
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                if (accept != "*/*" && accept.endsWith("/*") && !mime.startsWith(accept.removeSuffix("*"))) {
                    throw HermitException(ErrorCodes.INVALID_ARGUMENT, "所选文件类型不符合要求")
                }
                if (accept != "*/*" && !accept.endsWith("/*") && mime != accept) {
                    throw HermitException(ErrorCodes.INVALID_ARGUMENT, "所选文件类型不符合要求")
                }
                val input = contentResolver.openInputStream(uri) ?: throw HermitException(ErrorCodes.STORAGE, "无法读取文件")
                val stored = input.use { stream -> withContext(Dispatchers.IO) { files.import(it.appId, session.dataGeneration!!, stream, name, mime) } }
                if (mime.startsWith("video/")) {
                    val metadata = inspectVideo(uri)
                    metadata.keys().forEach { key -> stored.put(key, metadata.get(key)) }
                }
                stored
            }
            "files.pickImage" -> requireApp(app).let { pickInlineImage(params) }
            "files.pickInline" -> requireApp(app).let { pickInlineFile(params) }
            "files.writeText" -> requireApp(app).let { withContext(Dispatchers.IO) {
                files.writeText(it.appId, session.dataGeneration!!, params.optString("name", "note.txt"), params.getString("text"))
            } }
            "files.readText" -> requireApp(app).let { withContext(Dispatchers.IO) {
                files.readText(it.appId, session.dataGeneration!!, params.getString("logicalFileId"), params.optInt("maxBytes", 256 * 1024))
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
            "speech.preferences" -> speech.preferences()
            "speech.languages" -> speech.languages()
            "speech.start" -> {
                permissionBroker.require(session, "speech", "录制声音并交给系统语音识别服务", PermissionBroker.SPEECH_PERMISSIONS)
                speech.start(params) { event, data -> if (this.session === session) bridge?.emit(event, data) }
            }
            "speech.recognizeOnce" -> {
                permissionBroker.require(session, "speech", "打开系统语音识别界面")
                recognizeSpeechOnce(session, params)
            }
            "speech.stop" -> speech.stop(params.optString("subscriptionId").takeIf { it.isNotBlank() })
            "speech.cancel" -> speech.cancel()
            "location.availability" -> location.availability()
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
            "sensors.availability" -> sensors.availability()
            "sensors.watch" -> {
                val type = params.getString("type")
                val capability = if (type in SensorController.STEP_TYPES) "sensors.steps" else "sensors.read"
                if (!sensors.supported(type)) throw HermitException(ErrorCodes.UNSUPPORTED, "当前设备没有此传感器：$type")
                permissionBroker.require(session, capability,
                    if (capability == "sensors.steps") "读取设备计步传感器" else "读取设备运动、方向或环境传感器",
                    capabilityPermissions(capability))
                sensors.watch(params) { event, data -> if (this.session === session) bridge?.emit(event, data) }
            }
            "sensors.clearWatch" -> sensors.clearWatch(params.getString("subscriptionId"))
            "camera.capturePhoto" -> requireApp(app).let {
                if (!capabilitySupported("camera.capture")) throw HermitException(ErrorCodes.UNSUPPORTED, "系统没有可用相机应用")
                permissionBroker.require(session, "camera.capture", "打开系统相机拍摄一张照片")
                capturePhoto(it, session)
            }
            "camera.torchStatus" -> device.torchStatus()
            "camera.setTorch" -> {
                permissionBroker.require(session, "camera.torch", "控制设备闪光灯", listOf(Manifest.permission.CAMERA))
                device.setTorch(params.getBoolean("enabled"))
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
            "network.status" -> device.networkStatus()
            "network.watch" -> device.watchNetwork { event, data -> if (this.session === session) bridge?.emit(event, data) }
            "network.clearWatch" -> device.clearNetworkWatch(params.getString("subscriptionId"))
            "network.request" -> requireApp(app).let {
                nativeHttp.request(it.appId, session.dataGeneration!!, params) { origin, addressClass ->
                    val grantScope = NativeHttpClient.AuthorizationTarget(origin, addressClass).grantScope
                    permissionBroker.require(session, "network", "通过 Hermit 访问 $origin（$addressClass 网络）", scope = grantScope)
                }
            }
            "network.openStream" -> requireApp(app).let {
                nativeHttp.openStream(session.sessionId, it.appId, session.dataGeneration!!, params) { origin, addressClass ->
                    val grantScope = NativeHttpClient.AuthorizationTarget(origin, addressClass).grantScope
                    permissionBroker.require(session, "network", "通过 Hermit 流式访问 $origin（$addressClass 网络）", scope = grantScope)
                }
            }
            "network.readStream" -> requireApp(app).let {
                nativeHttp.readStream(session.sessionId, it.appId, session.dataGeneration!!, params)
            }
            "network.closeStream" -> requireApp(app).let {
                nativeHttp.closeStream(session.sessionId, it.appId, session.dataGeneration!!, params)
            }
            "network.openSocket" -> requireApp(app).let {
                nativeHttp.openSocket(session.sessionId, it.appId, session.dataGeneration!!, params) { origin, addressClass ->
                    val grantScope = NativeHttpClient.AuthorizationTarget(origin, addressClass).grantScope
                    permissionBroker.require(session, "network", "通过 Hermit 建立 $origin WebSocket（$addressClass 网络）", scope = grantScope)
                }
            }
            "network.readSocket" -> requireApp(app).let {
                nativeHttp.readSocket(session.sessionId, it.appId, session.dataGeneration!!, params)
            }
            "network.sendSocket" -> requireApp(app).let {
                nativeHttp.sendSocket(session.sessionId, it.appId, session.dataGeneration!!, params)
            }
            "network.closeSocket" -> requireApp(app).let {
                nativeHttp.closeSocket(session.sessionId, it.appId, session.dataGeneration!!, params)
            }
            "wifi.status" -> wifi.status()
            "wifi.scan" -> {
                permissionBroker.require(session, "wifi.scan", "扫描附近的 Wi-Fi 网络", wifiScanPermissions())
                wifi.scan()
            }
            "wifi.requestNetwork" -> {
                permissionBroker.require(session, "wifi.connect", "请求 Android 连接指定 Wi-Fi 网络", wifiConnectPermissions())
                wifi.requestNetwork(params) { event, data -> if (this.session === session) bridge?.emit(event, data) }
            }
            "wifi.releaseNetwork" -> wifi.releaseNetwork(params.getString("connectionId"))
            "wifi.openSettings" -> device.openSettings("wifi")
            "bluetooth.status" -> bluetooth.status()
            "bluetooth.paired" -> {
                permissionBroker.require(session, "bluetooth.connect", "读取已配对蓝牙设备", bluetoothConnectPermissions())
                bluetooth.paired()
            }
            "bluetooth.scan" -> {
                permissionBroker.require(session, "bluetooth.scan", "扫描附近的低功耗蓝牙设备", bluetoothScanPermissions())
                bluetooth.scan(params) { event, data -> if (this.session === session) bridge?.emit(event, data) }
            }
            "bluetooth.stopScan" -> bluetooth.stopScan(params.getString("subscriptionId"))
            "bluetooth.connect" -> {
                permissionBroker.require(session, "bluetooth.connect", "连接并交换低功耗蓝牙设备数据", bluetoothConnectPermissions())
                bluetooth.connect(params) { event, data -> if (this.session === session) bridge?.emit(event, data) }
            }
            "bluetooth.disconnect" -> bluetooth.disconnect(params.getString("connectionId"))
            "bluetooth.services" -> bluetooth.services(params.getString("connectionId"))
            "bluetooth.read" -> bluetooth.read(params)
            "bluetooth.write" -> bluetooth.write(params)
            "bluetooth.subscribe" -> bluetooth.subscribe(params)
            "bluetooth.openSettings" -> device.openSettings("bluetooth")
            "infrared.status" -> infrared.status()
            "infrared.transmit" -> {
                permissionBroker.require(session, "infrared.transmit", "使用设备红外发射器发送脉冲")
                infrared.transmit(params)
            }
            "battery.status" -> device.batteryStatus()
            "battery.watch" -> device.watchBattery { event, data -> if (this.session === session) bridge?.emit(event, data) }
            "battery.clearWatch" -> device.clearBatteryWatch(params.getString("subscriptionId"))
            "system.openSettings" -> device.openSettings(params.getString("page"))
            "permissions.status" -> {
                val capability = params.getString("capability")
                validateGrantCapability(capability)
                val scope = capabilityScope(capability, params.optString("scope"))
                val target = if (capability == "network") nativeHttp.authorizationTarget(scope) else null
                val supported = capabilitySupported(capability)
                JSONObject(permissionBroker.status(session, capability, capabilityPermissions(capability), target?.grantScope ?: scope))
                    .put("scope", scope)
                    .put("addressClass", target?.addressClass ?: JSONObject.NULL)
                    .put("supported", supported)
                    .also { status -> status.put("usable", supported && status.optBoolean("usable")) }
            }
            "permissions.request" -> {
                val capability = params.getString("capability")
                validateGrantCapability(capability)
                if (!capabilitySupported(capability)) throw HermitException(ErrorCodes.UNSUPPORTED, "当前设备不支持此能力：$capability")
                val scope = capabilityScope(capability, params.optString("scope"))
                val target = if (capability == "network") nativeHttp.authorizationTarget(scope) else null
                val rationale = if (target != null) "通过 Hermit 原生网络连接访问 $scope（${target.addressClass} 网络）" else capabilityRationale(capability)
                permissionBroker.require(session, capability, rationale, capabilityPermissions(capability), target?.grantScope ?: scope)
                JSONObject().put("capability", capability).put("scope", scope)
                    .put("addressClass", target?.addressClass ?: JSONObject.NULL).put("usable", true)
            }
            "notifications.notify" -> requireApp(app).let { target ->
                ensureNotifications(session)
                val spec = NotificationSpec.fromJson(params)
                val enabledTarget = hermitApp.registry.getInstance(target.appId) ?: target
                JSONObject().put("posted", hermitApp.notifications.dispatcher.post(enabledTarget, spec)).put("id", spec.id)
            }
            "notifications.schedule" -> requireApp(app).let { target ->
                ensureNotifications(session)
                if (!ensureExactAlarmAccess()) throw HermitException(ErrorCodes.OS_PERMISSION_DENIED, "Android 未允许精确的闹钟和提醒")
                val spec = NotificationSpec.fromJson(params.getJSONObject("notification"))
                val triggerAt = params.getLong("triggerAt")
                if (triggerAt <= System.currentTimeMillis()) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "首次通知时间必须晚于当前时间")
                val recurrence = runCatching { Recurrence.valueOf(params.optString("recurrence", "once").uppercase()) }
                    .getOrElse { throw HermitException(ErrorCodes.INVALID_ARGUMENT, "不支持的通知重复方式") }
                withContext(Dispatchers.IO) { hermitApp.notifications.repository.upsert(target.appId, spec, triggerAt, recurrence) }
                if (!hermitApp.notifications.scheduler.rebuild()) throw HermitException(ErrorCodes.OS_PERMISSION_DENIED, "无法注册精确提醒")
                JSONObject().put("scheduled", true).put("id", spec.id).put("triggerAt", triggerAt).put("recurrence", recurrence.name.lowercase())
            }
            "notifications.cancel" -> requireApp(app).let { target ->
                val id = params.getString("id")
                hermitApp.notifications.dispatcher.cancel(target.appId, id)
                val cancelled = withContext(Dispatchers.IO) { hermitApp.notifications.repository.cancel(target.appId, id) }
                hermitApp.notifications.scheduler.rebuild()
                JSONObject().put("cancelled", cancelled)
            }
            "notifications.cancelAll" -> requireApp(app).let { target ->
                hermitApp.notifications.dispatcher.cancelAll(target.appId)
                val count = withContext(Dispatchers.IO) { hermitApp.notifications.repository.cancelAll(target.appId) }
                hermitApp.notifications.scheduler.rebuild()
                JSONObject().put("cancelled", count)
            }
            "notifications.getScheduled" -> requireApp(app).let { target ->
                JSONObject().put("items", JSONArray(withContext(Dispatchers.IO) {
                    hermitApp.notifications.repository.list(target.appId).map { item -> item.toJson() }
                }))
            }
            "notifications.setEndpoint" -> requireApp(app).let { target ->
                ensureNotifications(session)
                if (target.liveUrl == null) throw HermitException(ErrorCodes.UNSUPPORTED, "纯本地 happ 不能登记远程通知端点")
                val endpoint = notificationEndpoint(session, params.getString("endpoint"))
                withContext(Dispatchers.IO) { hermitApp.notifications.repository.setEndpoint(target.appId, endpoint, session.origin) }
                hermitApp.notifications.syncNow()
                JSONObject().put("endpoint", endpoint).put("origin", session.origin)
            }
            "notifications.getStatus" -> requireApp(app).let { target ->
                val sync = withContext(Dispatchers.IO) { hermitApp.notifications.repository.sync(target.appId) }
                JSONObject().put("enabled", target.notificationEnabled)
                    .put("systemPermission", Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                    .put("exactAlarm", hermitApp.notifications.scheduler.exactAlarmAvailable())
                    .put("endpoint", sync?.endpoint ?: JSONObject.NULL)
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

    private suspend fun pickFile(contentType: String = "*/*"): Uri? = suspendCancellableCoroutine { continuation ->
        if (pendingFile != null || pendingImage != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有文件选择操作")))
            return@suspendCancellableCoroutine
        }
        pendingFile = continuation
        continuation.invokeOnCancellation { if (pendingFile === continuation) pendingFile = null }
        filePicker.launch(contentType)
    }

    private suspend fun pickImage(): Uri? = suspendCancellableCoroutine { continuation ->
        if (pendingImage != null || pendingFile != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有图片选择操作")))
            return@suspendCancellableCoroutine
        }
        pendingImage = continuation
        continuation.invokeOnCancellation { if (pendingImage === continuation) pendingImage = null }
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private suspend fun pickInlineFile(params: JSONObject): JSONObject {
        val accept = params.optString("accept", "*/*").lowercase().trim()
        if (!accept.matches(Regex("[a-z0-9.+-]+/(?:[a-z0-9.+-]+|\\*)"))) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "文件类型无效")
        }
        val maxBytes = params.optInt("maxBytes", 650 * 1024).coerceIn(1, 900 * 1024)
        val uri = pickFile(accept) ?: return JSONObject().put("cancelled", true)
        val mime = (contentResolver.getType(uri) ?: "application/octet-stream").lowercase()
        if (accept != "*/*" && accept.endsWith("/*") && !mime.startsWith(accept.removeSuffix("*"))) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "所选文件类型不符合要求")
        }
        if (accept != "*/*" && !accept.endsWith("/*") && mime != accept) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "所选文件类型不符合要求")
        }
        val bytes = withContext(Dispatchers.IO) {
            val source = contentResolver.openInputStream(uri)
                ?: throw HermitException(ErrorCodes.STORAGE, "无法读取所选文件")
            source.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw HermitException(ErrorCodes.QUOTA, "所选文件超过 ${maxBytes / 1024} KiB")
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
        }
        return JSONObject().put("cancelled", false)
            .put("name", queryDisplayName(uri) ?: "file")
            .put("mime", mime).put("size", bytes.size)
            .put("dataUrl", "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private suspend fun pickInlineImage(params: JSONObject): JSONObject {
        val maxDimension = params.optInt("maxDimension", 1280).coerceIn(320, 2048)
        val maxBytes = params.optInt("maxBytes", 420 * 1024).coerceIn(64 * 1024, 700 * 1024)
        val uri = pickImage() ?: return JSONObject().put("cancelled", true)
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        if (!mime.startsWith("image/")) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "所选文件不是图片")
        val bytes = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw HermitException(ErrorCodes.INVALID_ARGUMENT, "无法识别所选图片")
            }
            var sample = 1
            while (bounds.outWidth / sample > maxDimension * 2 || bounds.outHeight / sample > maxDimension * 2) sample *= 2
            val original = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "无法读取所选图片")
            try {
                val baseScale = minOf(1.0, maxDimension.toDouble() / maxOf(original.width, original.height).toDouble())
                repeat(5) { attempt ->
                    val scale = baseScale * Math.pow(0.84, attempt.toDouble())
                    val width = maxOf(1, (original.width * scale).toInt())
                    val height = maxOf(1, (original.height * scale).toInt())
                    val scaled = Bitmap.createScaledBitmap(original, width, height, true)
                    val encoded = ByteArrayOutputStream().use { output ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 84 - attempt * 7, output)
                        output.toByteArray()
                    }
                    if (scaled !== original) scaled.recycle()
                    if (encoded.size <= maxBytes) return@withContext encoded
                }
                throw HermitException(ErrorCodes.QUOTA, "图片处理后仍然过大，请选择更简单的图片")
            } finally {
                original.recycle()
            }
        }
        return JSONObject().put("cancelled", false)
            .put("name", (queryDisplayName(uri) ?: "image").substringBeforeLast('.') + ".jpg")
            .put("mime", "image/jpeg").put("size", bytes.size)
            .put("dataUrl", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private suspend fun inspectVideo(uri: Uri): JSONObject = withContext(Dispatchers.IO) {
        val output = JSONObject()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this@MainActivity, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { output.put("durationMs", it) }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { output.put("width", it) }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let { output.put("height", it) }
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                try {
                    val scale = minOf(1f, 240f / maxOf(frame.width, frame.height).toFloat())
                    val preview = if (scale < 1f) Bitmap.createScaledBitmap(frame, maxOf(1, (frame.width * scale).toInt()), maxOf(1, (frame.height * scale).toInt()), true) else frame
                    val bytes = ByteArrayOutputStream().use { encoded ->
                        preview.compress(Bitmap.CompressFormat.JPEG, 68, encoded)
                        encoded.toByteArray()
                    }
                    if (preview !== frame) preview.recycle()
                    if (bytes.size <= 96 * 1024) output.put("previewDataUrl", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
                } finally { frame.recycle() }
            }
        } catch (_: Throwable) {
            // A playable file remains usable even if the platform cannot
            // extract dimensions or a thumbnail from its container.
        } finally { try { retriever.release() } catch (_: Throwable) {} }
        output
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

    private suspend fun pickIcon(): Uri? = suspendCancellableCoroutine { continuation ->
        if (pendingIcon != null) {
            continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有图标选择操作")))
            return@suspendCancellableCoroutine
        }
        pendingIcon = continuation
        continuation.invokeOnCancellation { if (pendingIcon === continuation) pendingIcon = null }
        iconPicker.launch("image/*")
    }

    private suspend fun inspectDirectory(uri: Uri): JSONObject = withContext(Dispatchers.IO) {
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@MainActivity, uri)
            ?: throw HermitException(ErrorCodes.STORAGE, "无法读取所选目录")
        val manifestFile = tree.findFile("hermit.json")?.takeIf { it.isFile }
        val manifest = manifestFile?.let { file ->
            contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val text = reader.readText()
                if (text.length > 256 * 1024) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "hermit.json 过大")
                JSONObject(text)
            }
        }
        val version = manifest?.opt("version")
        val versionName = when (version) {
            is JSONObject -> version.optString("name")
            is String -> version
            else -> ""
        }
        val manifestIcon = manifest?.optString("icon")?.takeIf { it.isNotBlank() }
            ?.let { relative -> findDocument(tree, relative) }
            ?.takeIf { it.isFile }
            ?.let { croppedIconDataUrl(it.uri) }
        JSONObject()
            .put("path", tree.name ?: uri.toString())
            .put("manifestFound", manifest != null)
            .put("name", manifest?.optString("name")?.takeIf { it.isNotBlank() } ?: tree.name ?: "本地应用")
            .put("version", versionName.ifBlank { "1.0.0" })
            .put("entry", manifest?.optString("entry", "index.html") ?: "index.html")
            .put("iconDataUrl", manifestIcon)
    }

    private fun findDocument(root: androidx.documentfile.provider.DocumentFile, relativePath: String): androidx.documentfile.provider.DocumentFile? {
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        var current = root
        for (part in parts) current = current.findFile(part) ?: return null
        return current
    }

    private suspend fun croppedIconDataUrl(uri: Uri): String = withContext(Dispatchers.IO) {
        IconProcessor.centeredPngDataUrl { contentResolver.openInputStream(uri) }
    }

    private suspend fun iconSourceDataUrl(uri: Uri): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "无法识别所选图片")
        var sample = 1
        while (bounds.outWidth / sample > 1536 || bounds.outHeight / sample > 1536) sample *= 2
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "无法读取所选图片")
        val maxDimension = 1024
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(decoded.width, decoded.height))
        val prepared = if (scale < 1f) Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true) else decoded
        val hasAlpha = prepared.hasAlpha()
        val bytes = ByteArrayOutputStream().use { output ->
            prepared.compress(if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, output)
            output.toByteArray()
        }
        if (prepared !== decoded) prepared.recycle()
        decoded.recycle()
        if (bytes.size > 2 * 1024 * 1024) throw HermitException(ErrorCodes.QUOTA, "图片处理后仍然过大，请选择较简单的图片")
        "data:image/${if (hasAlpha) "png" else "jpeg"};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun validateIconDataUrl(value: String): String? {
        val dataUrl = value.trim()
        if (dataUrl.isEmpty()) return null
        val prefix = "data:image/png;base64,"
        if (!dataUrl.startsWith(prefix)) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "图标格式无效")
        val bytes = try { Base64.decode(dataUrl.removePrefix(prefix), Base64.NO_WRAP) }
        catch (_: IllegalArgumentException) { throw HermitException(ErrorCodes.INVALID_ARGUMENT, "图标格式无效") }
        if (bytes.isEmpty() || bytes.size > 512 * 1024) throw HermitException(ErrorCodes.QUOTA, "图标文件过大")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth != 192 || bounds.outHeight != 192) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "图标尺寸无效，请重新选择图片")
        }
        return dataUrl
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

    private suspend fun recognizeSpeechOnce(runtime: RuntimeSession, params: JSONObject): JSONObject {
        if (pendingSpeechActivity != null) throw HermitException(ErrorCodes.CONFLICT, "已有系统语音识别界面")
        val intent = speech.activityIntent(params)
        val result = suspendCancellableCoroutine<JSONObject> { continuation ->
            pendingSpeechActivity = continuation
            continuation.invokeOnCancellation { if (pendingSpeechActivity === continuation) pendingSpeechActivity = null }
            try { speechActivityLauncher.launch(intent) }
            catch (error: Throwable) {
                pendingSpeechActivity = null
                continuation.resumeWith(Result.failure(HermitException(ErrorCodes.UNSUPPORTED,
                    "系统语音识别界面无法打开：${error.message ?: "未知错误"}", true)))
            }
        }
        if (!runtime.alive || session !== runtime) throw HermitException(ErrorCodes.SESSION_EXPIRED, "语音识别期间页面会话已结束")
        return result
    }

    private fun safeDocumentName(value: String): String = value.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]+"), "-").take(60).ifBlank { "hermit-app" }

    private fun capabilityDescriptors(runtime: RuntimeSession): JSONObject {
        val names = listOf("runtime", "app", "data", "files", "audio", "tts", "speech", "location", "sensors", "camera",
            "share", "clipboard", "haptics", "network", "wifi", "bluetooth", "infrared", "battery", "system")
        return JSONObject().put("capabilities", JSONArray(names.map { name ->
            val supported = when (name) {
                "audio" -> audio.microphoneAvailable() || audio.audioOutputAvailable()
                "tts" -> tts.isAvailable()
                "speech" -> speech.availability().optBoolean("available")
                "location" -> getSystemService(android.location.LocationManager::class.java).allProviders.isNotEmpty()
                "sensors" -> sensors.availability().optBoolean("available")
                "camera" -> packageManager.resolveActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY) != null
                "wifi" -> packageManager.hasSystemFeature("android.hardware.wifi")
                "bluetooth" -> packageManager.hasSystemFeature("android.hardware.bluetooth")
                "infrared" -> infrared.status().optBoolean("supported")
                else -> true
            }
            JSONObject().put("name", name).put("implemented", true).put("supported", supported)
                .put("usable", supported && (runtime.role == RuntimeRole.WEB_APP || name in setOf("runtime", "app")))
                .put("lifecycle", if (name in setOf("audio", "speech", "location", "sensors", "tts", "wifi", "bluetooth", "battery", "network")) "foreground-session" else "request")
                .also { descriptor ->
                    authorizationDescriptors(runtime, name).takeIf { it.length() > 0 }?.let { descriptor.put("authorization", it) }
                    when (name) {
                        "audio" -> descriptor.put("features", JSONObject()
                            .put("microphoneRecording", audio.microphoneAvailable())
                            .put("speakerPlayback", audio.audioOutputAvailable()))
                        "tts" -> descriptor.put("features", tts.capabilitySnapshot())
                        "speech" -> descriptor.put("features", speech.availability())
                        "location" -> descriptor.put("features", location.availability())
                        "sensors" -> descriptor.put("features", sensors.availability())
                        "wifi" -> descriptor.put("features", wifi.status())
                        "bluetooth" -> descriptor.put("features", bluetooth.status())
                        "infrared" -> descriptor.put("features", infrared.status())
                        "camera" -> descriptor.put("features", JSONObject().put("photo", supported).put("torch", device.torchStatus().optBoolean("supported")))
                    }
                }
        }))
    }

    private fun authorizationDescriptors(runtime: RuntimeSession, namespace: String): JSONObject {
        val capabilities = when (namespace) {
            "audio" -> listOf("microphone.record")
            "tts" -> listOf("tts.speak")
            "speech" -> listOf("speech")
            "location" -> listOf("location.approximate", "location.precise")
            "sensors" -> listOf("sensors.read", "sensors.steps")
            "camera" -> listOf("camera.capture", "camera.torch")
            "wifi" -> listOf("wifi.scan", "wifi.connect")
            "bluetooth" -> listOf("bluetooth.scan", "bluetooth.connect")
            "infrared" -> listOf("infrared.transmit")
            "clipboard" -> listOf("clipboard.read")
            "notifications" -> listOf("notifications")
            else -> emptyList()
        }
        return JSONObject().also { result -> capabilities.forEach { capability ->
            val supported = capabilitySupported(capability)
            val status = JSONObject(permissionBroker.status(runtime, capability, capabilityPermissions(capability)))
                .put("supported", supported)
            status.put("usable", supported && status.optBoolean("usable"))
            result.put(capability, status)
        } }
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun capabilityPermissions(capability: String): List<String> = when (capability) {
        "speech" -> PermissionBroker.SPEECH_PERMISSIONS
        "microphone.record" -> PermissionBroker.MICROPHONE_PERMISSIONS
        "location.approximate" -> PermissionBroker.COARSE_LOCATION
        "location.precise" -> PermissionBroker.FINE_LOCATION
        "sensors.steps" -> listOf(Manifest.permission.ACTIVITY_RECOGNITION)
        "wifi.scan" -> wifiScanPermissions()
        "wifi.connect" -> wifiConnectPermissions()
        "bluetooth.scan" -> bluetoothScanPermissions()
        "bluetooth.connect" -> bluetoothConnectPermissions()
        "camera.torch" -> listOf(Manifest.permission.CAMERA)
        "notifications" -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        else -> emptyList()
    }

    private fun capabilityRationale(capability: String): String = when (capability) {
        "speech" -> "录制声音并交给系统语音识别服务"
        "microphone.record" -> "使用麦克风录制音频"
        "location.approximate" -> "读取设备的大致位置"
        "location.precise" -> "读取设备的精确位置"
        "tts.speak" -> "使用系统 TTS 朗读或生成语音"
        "sensors.read" -> "读取设备运动、方向和环境传感器"
        "sensors.steps" -> "读取设备计步传感器"
        "wifi.scan" -> "扫描附近的 Wi-Fi 网络"
        "wifi.connect" -> "通过 Android 系统确认连接指定 Wi-Fi 网络"
        "bluetooth.scan" -> "扫描附近的低功耗蓝牙设备"
        "bluetooth.connect" -> "连接并交换低功耗蓝牙设备数据"
        "infrared.transmit" -> "使用设备红外发射器发送脉冲"
        "camera.torch" -> "控制设备闪光灯"
        "clipboard.read" -> "读取当前剪贴板内容"
        "network" -> "通过 Hermit 原生网络连接访问已确认的目标"
        "notifications" -> "在 Android 通知栏显示此 happ 的提醒"
        else -> "允许页面使用 $capability"
    }

    private fun capabilitySupported(capability: String): Boolean = when (capability) {
        "speech" -> speech.availability().optBoolean("available")
        "microphone.record" -> audio.microphoneAvailable()
        "location.approximate", "location.precise" ->
            getSystemService(android.location.LocationManager::class.java).allProviders.isNotEmpty()
        "tts.speak" -> tts.isAvailable()
        "sensors.read" -> sensors.availability().optBoolean("available")
        "sensors.steps" -> SensorController.STEP_TYPES.any(sensors::supported)
        "wifi.scan" -> packageManager.hasSystemFeature("android.hardware.wifi")
        "wifi.connect" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && packageManager.hasSystemFeature("android.hardware.wifi")
        "bluetooth.scan", "bluetooth.connect" -> packageManager.hasSystemFeature("android.hardware.bluetooth")
        "infrared.transmit" -> infrared.status().optBoolean("supported")
        "camera.capture" ->
            packageManager.resolveActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY) != null
        "camera.torch" -> device.torchStatus().optBoolean("supported")
        "clipboard.read", "network", "notifications" -> true
        else -> false
    }

    private fun wifiScanPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    private fun wifiConnectPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else PermissionBroker.FINE_LOCATION

    private fun bluetoothScanPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
    } else PermissionBroker.FINE_LOCATION

    private fun bluetoothConnectPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else emptyList()

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

    private suspend fun ensureNotifications(runtime: RuntimeSession) {
        val permissions = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        permissionBroker.require(runtime, "notifications", "在 Android 通知栏显示此 happ 的提醒", permissions)
        val app = runtime.instance ?: return
        if (!app.notificationEnabled) hermitApp.registry.setNotificationEnabled(app.appId, true)
    }

    private suspend fun ensureExactAlarmAccess(): Boolean {
        if (hermitApp.notifications.scheduler.exactAlarmAvailable()) return true
        if (Build.VERSION.SDK_INT < 31) return true
        return suspendCancellableCoroutine { continuation ->
            if (pendingExactAlarm != null) {
                continuation.resumeWith(Result.failure(HermitException(ErrorCodes.CONFLICT, "已有精确提醒授权请求")))
                return@suspendCancellableCoroutine
            }
            pendingExactAlarm = continuation
            continuation.invokeOnCancellation { if (pendingExactAlarm === continuation) pendingExactAlarm = null }
            runCatching {
                exactAlarmLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }.onFailure {
                pendingExactAlarm = null
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    private fun notificationEndpoint(runtime: RuntimeSession, raw: String): String {
        if (raw.length > MAX_URL_LENGTH || raw.any { it <= '\u001f' }) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "通知端点无效")
        val resolved = runtime.instance!!.runtimeUrl.toHttpUrl().resolve(raw)
            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "通知端点无效")
        val normalized = resolved.toString()
        if (originOf(Uri.parse(normalized)) != runtime.origin) throw HermitException(ErrorCodes.ORIGIN_DENIED, "通知端点必须与当前 happ 同源")
        val host = resolved.host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost") || runCatching { InetAddress.getByName(host) }
                .getOrNull()?.let { it.isLoopbackAddress || it.isAnyLocalAddress } == true) {
            throw HermitException(ErrorCodes.ORIGIN_DENIED, "通知端点不能指向本机")
        }
        return normalized
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
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.forEach { permission ->
            hermitApp.registry.observeSystemPermission(
                permission,
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    private fun fullRuntimeFeaturesAvailable(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private fun bridgeMode(): String = when {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) -> "shared-web-message"
        else -> "shared-legacy-bridge"
    }

    private fun runtimeFeaturesAvailable(): Boolean = WebViewCompat.getCurrentWebViewPackage(this) != null

    private fun runtimeCompatibilityMessage(): String {
        val missing = buildList {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) add("独立资料空间（Multi-Profile）")
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) add("安全消息通道（WebMessageListener）")
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) add("文档起始注入（DocumentStartScript）")
        }
        val provider = WebViewCompat.getCurrentWebViewPackage(this)
        val current = if (provider == null) "未检测到 WebView Provider" else "${provider.packageName} ${provider.versionName}"
        return buildString {
            append(getString(R.string.incompatible_message))
            append("\n\n当前：").append(current)
            if (missing.isNotEmpty()) append("\n缺少：").append(missing.joinToString("、"))
        }
    }

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
        layout.addView(TextView(this).apply { text = title; textSize = 24f; gravity = Gravity.CENTER })
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
        pendingStoreScript = "window.hermitOpenSharedUrl && window.hermitOpenSharedUrl(${JSONObject.quote(candidate)})"
        showTarget(null, false)
    }

    private suspend fun confirmInsecureUrl(url: String): Boolean = promptChoice(
        "允许未加密的 HTTP 页面？",
        "$url\n\n网页内容和凭据可能被同一网络中的其他人读取或篡改。仅在你信任当前网络和服务时继续。",
        listOf(PromptChoice("cancel", "取消"), PromptChoice("continue", "仍然添加", "primary")),
    ) == "continue"

    private suspend fun chooseIdentityInstall(existing: WebAppInstance, incomingPublisherKeyId: String?): IdentityInstallChoice {
        val verified = incomingPublisherKeyId != null && existing.publisherKeyId == incomingPublisherKeyId
        val publisherChanged = existing.publisherKeyId != incomingPublisherKeyId
        val message = if (verified) "“${existing.name}”具有相同 happId 和发布者公钥。更新原实例会保留它的数据、设置和授权；全新安装会创建相互隔离的新实例。"
            else if (publisherChanged) "“${existing.name}”使用相同 happId，但发布者公钥与当前包不同或缺失。只有你确认这是同一 happ 时才更新原实例；更新会采用新包的发布者信息并保留数据与授权。"
            else "“${existing.name}”使用相同 happId，但双方都没有可验证的发布者签名。仅在你确认它们是同一 happ 时更新原实例；也可以创建隔离的新实例。"
        return when (promptChoice(
            if (verified) "已安装同一签名的 happ" else "发现相同 happId 的实例",
            message,
            listOf(PromptChoice("cancel", "取消"), PromptChoice("new", "全新安装"), PromptChoice("update", "更新原实例", "primary")),
        )) {
            "new" -> IdentityInstallChoice.NEW_INSTANCE
            "update" -> IdentityInstallChoice.UPDATE
            else -> IdentityInstallChoice.CANCEL
        }
    }

    private suspend fun promptChoice(title: String, message: String, choices: List<PromptChoice>): String {
        val webChoice = withContext(Dispatchers.Main.immediate) { requestShellPrompt(title, message, choices) }
        return webChoice ?: withContext(Dispatchers.Main.immediate) { requestNativePrompt(title, message, choices) }
    }

    private suspend fun requestShellPrompt(title: String, message: String, choices: List<PromptChoice>): String? =
        suspendCancellableCoroutine { continuation ->
            val view = webView
            if (view == null || session?.role != RuntimeRole.STORE || pendingShellPrompt != null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val token = UUID.randomUUID().toString()
            val payload = JSONObject().put("token", token).put("title", title).put("message", message)
                .put("choices", JSONArray(choices.map { JSONObject().put("value", it.value).put("label", it.label).put("emphasis", it.emphasis) }))
            val pending = PendingShellPrompt(token, choices.mapTo(linkedSetOf()) { it.value }, continuation)
            pendingShellPrompt = pending
            continuation.invokeOnCancellation { if (pendingShellPrompt === pending) pendingShellPrompt = null }
            view.evaluateJavascript("Boolean(window.hermitNativePrompt && window.hermitNativePrompt($payload))") { handled ->
                if (handled != "true" && pendingShellPrompt === pending) {
                    pendingShellPrompt = null
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private suspend fun requestNativePrompt(title: String, message: String, choices: List<PromptChoice>): String =
        suspendCancellableCoroutine { continuation ->
            val labels = choices.map { it.label }.toTypedArray()
            val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setItems(labels) { _, index -> if (continuation.isActive) continuation.resume(choices[index].value) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume("cancel") }
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
        hermitApp.agentServer.setUiHandler(null)
        hermitApp.agentServer.setStateHandler(null)
        destroyRuntime()
        audio.shutdown()
        tts.shutdown()
        speech.shutdown()
        device.shutdown()
        pendingZip?.cancel()
        pendingTree?.cancel()
        pendingFile?.cancel()
        pendingImage?.cancel()
        pendingFileExport?.cancel()
        pendingPermissions?.cancel()
        pendingCamera?.cancel()
        pendingCameraFile?.delete()
        pendingBackupExport?.cancel()
        pendingBackupImport?.cancel()
        pendingQrScan?.cancel()
        pendingIcon?.cancel()
        pendingExactAlarm?.cancel()
        pendingSpeechActivity?.cancel()
        pendingDirectoryImports.clear()
        super.onDestroy()
    }

    override fun onStop() {
        hermitApp.developmentServer.stop("Hermit entered background")
        hermitApp.agentServer.stop("Hermit 已进入后台")
        session?.sessionId?.let(audio::cancelSession)
        location.cancelAll()
        sensors.cancelAll()
        wifi.cancelAll()
        bluetooth.cancelAll()
        device.cancelAll()
        tts.stop()
        speech.cancel()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        observeDeclaredSystemPermissions()
        if (hasResumed && session?.role == RuntimeRole.STORE) {
            webView?.post {
                webView?.evaluateJavascript("window.dispatchEvent(new Event('hermitresume'))", null)
            }
        }
        hasResumed = true
    }

    companion object {
        const val EXTRA_APP_ID = "io.github.zhyuzh3d.hermit.APP_ID"
        const val EXTRA_NOTIFICATION_ID = "io.github.zhyuzh3d.hermit.NOTIFICATION_ID"
        const val EXTRA_NOTIFICATION_DATA = "io.github.zhyuzh3d.hermit.NOTIFICATION_DATA"
        private val GRANT_CAPABILITIES = setOf(
            "speech", "microphone.record", "tts.speak", "location.approximate", "location.precise",
            "sensors.read", "sensors.steps", "wifi.scan", "wifi.connect", "bluetooth.scan", "bluetooth.connect",
            "infrared.transmit", "camera.capture", "camera.torch", "clipboard.read", "network", "notifications"
        )
        private const val STORE_ORIGIN = "https://store.hermit.invalid"
        private const val STORE_URL = "$STORE_ORIGIN/index.html"
        private const val MAX_URL_LENGTH = 4096
        private const val RELOAD_IN_PLACE = "reload"
        private const val RELOAD_RECREATE = "recreate"
        private const val MAX_POST_RELOAD_SCRIPT_CHARS = 64 * 1024
        private const val MAX_PAGE_STATE_CHARS = 512 * 1024
        private const val MAX_STORAGE_VALUE_CHARS = 8 * 1024
        private const val SUPPORT_URL = "https://hermit.10knet.com/pages/donate.html"
        private const val SUPPORT_ORIGIN = "https://hermit.10knet.com"
        private const val REPOSITORY_URL = "https://github.com/zhyuzh3d/hermit"
    }
}
