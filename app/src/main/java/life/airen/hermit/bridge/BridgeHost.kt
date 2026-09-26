package life.airen.hermit.bridge

import life.airen.hermit.runtime.RuntimeSession
import org.json.JSONObject

fun interface BridgeHost {
    suspend fun dispatch(session: RuntimeSession, method: String, params: JSONObject): Any?
}
