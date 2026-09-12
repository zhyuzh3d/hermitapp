package io.github.zhyuzh3d.hermit

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.zhyuzh3d.hermit.deploy.DevelopmentServer
import io.github.zhyuzh3d.hermit.deploy.AgentDevelopmentServer
import io.github.zhyuzh3d.hermit.install.InstallCoordinator
import io.github.zhyuzh3d.hermit.install.RemoteSourceInstaller
import io.github.zhyuzh3d.hermit.registry.AppRegistry
import io.github.zhyuzh3d.hermit.runtime.OfficialShellManager
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
    lateinit var officialShell: OfficialShellManager
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        registry = AppRegistry(this)
        installer = InstallCoordinator(this, registry)
        remoteInstaller = RemoteSourceInstaller(this, registry, installer)
        developmentServer = DevelopmentServer(this, registry, installer, applicationScope)
        agentServer = AgentDevelopmentServer(this, registry, installer, applicationScope)
        officialShell = OfficialShellManager(this)
        officialShell.setMode(OfficialShellManager.Mode.LOCAL.value)
        registry.recoverInterruptedOperations()
        installer.recoverStorage()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        developmentServer.stop("Hermit entered background")
        agentServer.stop("Hermit 已进入后台")
    }

    override fun onTerminate() {
        developmentServer.stop("Application terminated")
        agentServer.stop("Application terminated")
        applicationScope.cancel()
        registry.close()
        super<Application>.onTerminate()
    }
}
