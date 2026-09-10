package io.github.zhyuzh3d.hermit.bridge

import io.github.zhyuzh3d.hermit.runtime.RuntimeSession
import org.json.JSONObject

fun interface BridgeHost {
    suspend fun dispatch(session: RuntimeSession, method: String, params: JSONObject): Any?
}
