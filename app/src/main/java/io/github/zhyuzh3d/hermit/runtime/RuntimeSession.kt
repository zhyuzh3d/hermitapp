package io.github.zhyuzh3d.hermit.runtime

import io.github.zhyuzh3d.hermit.model.CodeRelease
import io.github.zhyuzh3d.hermit.model.WebAppInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.UUID

enum class RuntimeRole { STORE, WEB_APP }

class RuntimeSession(
    val role: RuntimeRole,
    val instance: WebAppInstance?,
    val release: CodeRelease?,
    val origin: String,
    val profileName: String,
) {
    val sessionId: String = UUID.randomUUID().toString()
    val appId: String = instance?.appId ?: STORE_APP_ID
    val dataGeneration: String? = instance?.activeDataGeneration
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile var alive: Boolean = true
        private set

    fun close() {
        alive = false
        scope.cancel()
    }

    companion object {
        const val STORE_APP_ID = "__hermit_store__"
    }
}
