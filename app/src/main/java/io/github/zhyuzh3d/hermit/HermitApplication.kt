package io.github.zhyuzh3d.hermit

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.zhyuzh3d.hermit.deploy.DevelopmentServer
import io.github.zhyuzh3d.hermit.deploy.AgentDevelopmentServer
import io.github.zhyuzh3d.hermit.deploy.DevWorkspaceManager
import io.github.zhyuzh3d.hermit.install.InstallCoordinator
import io.github.zhyuzh3d.hermit.install.RemoteSourceInstaller
import io.github.zhyuzh3d.hermit.registry.AppRegistry
import io.github.zhyuzh3d.hermit.runtime.OfficialShellManager
import io.github.zhyuzh3d.hermit.notification.NotificationCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class HermitApplication : Application(), DefaultLifecycleObserver {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var registry: AppRegistry
        private set
    lateinit var installer: InstallCoordinator
        private set
    lateinit var remoteInstaller: RemoteSourceInstaller
        private set
    lateinit var developmentServer: DevelopmentServer
        private set
    lateinit var agentServer: AgentDevelopmentServer
        private set
    lateinit var devWorkspaces: DevWorkspaceManager
        private set
    lateinit var officialShell: OfficialShellManager
        private set
    lateinit var notifications: NotificationCenter
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        registry = AppRegistry(this)
        installer = InstallCoordinator(this, registry)
        devWorkspaces = DevWorkspaceManager(this, registry, installer)
        remoteInstaller = RemoteSourceInstaller(this, registry, installer)
        developmentServer = DevelopmentServer(this, registry, installer, applicationScope)
        agentServer = AgentDevelopmentServer(this, registry, installer, devWorkspaces, applicationScope)
        officialShell = OfficialShellManager(this)
        notifications = NotificationCenter(this, registry)
        val installState = getSharedPreferences("hermit-install-state", MODE_PRIVATE)
        val installedAt = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val replaced = installState.getLong("lastUpdateTime", -1L).let { it != -1L && it != installedAt }
        if (replaced) officialShell.resetToEmbedded()
        installState.edit().putLong("lastUpdateTime", installedAt).apply()
        registry.recoverInterruptedOperations()
        installer.recoverStorage()
        devWorkspaces.recoverStorage()
        agentServer.restoreIfEnabled()
        notifications.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        developmentServer.stop("Hermit entered background")
    }

    override fun onTerminate() {
        developmentServer.stop("Application terminated")
        agentServer.stop("Application terminated")
        applicationScope.cancel()
        notifications.close()
        registry.close()
        super<Application>.onTerminate()
    }
}
