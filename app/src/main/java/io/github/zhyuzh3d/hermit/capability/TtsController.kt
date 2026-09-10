package io.github.zhyuzh3d.hermit.capability

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HermitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TtsController(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var engine: TextToSpeech? = null
    private val callbacks = ConcurrentHashMap<String, (String, JSONObject) -> Unit>()
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private suspend fun getEngine(): TextToSpeech = engine ?: mutex.withLock {
        engine ?: run {
            val ready = CompletableDeferred<Int>()
            val created = TextToSpeech(appContext) { status -> ready.complete(status) }
            val status = try {
                withTimeout(15_000) { ready.await() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                created.shutdown()
                throw HermitException(ErrorCodes.TIMEOUT, "系统朗读服务初始化超时", true)
            }
            if (status != TextToSpeech.SUCCESS) {
                created.shutdown()
                throw HermitException(ErrorCodes.UNSUPPORTED, "系统朗读服务不可用")
            }
            created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { utteranceId?.let { callbacks[it]?.invoke("tts.start", JSONObject().put("utteranceId", it)) } }
                override fun onDone(utteranceId: String?) { utteranceId?.let {
                    callbacks.remove(it)?.invoke("tts.done", JSONObject().put("utteranceId", it))
                    completions.remove(it)?.complete(true)
                } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { utteranceId?.let {
                    callbacks.remove(it)?.invoke("tts.error", JSONObject().put("utteranceId", it).put("code", "engine"))
                    completions.remove(it)?.complete(false)
                } }
            })
            engine = created
            created
        }
    }

    suspend fun voices(): JSONObject {
        val tts = getEngine()
        val items = JSONArray()
        tts.voices?.sortedBy { it.name }?.forEach { voice ->
            items.put(JSONObject().put("id", voice.name).put("locale", voice.locale.toLanguageTag())
                .put("networkRequired", voice.isNetworkConnectionRequired))
        }
        return JSONObject().put("voices", items)
    }

    suspend fun speak(params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        val text = params.optString("text")
        if (text.isBlank() || text.length > 10_000) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "朗读文本为空或过长")
        val tts = getEngine()
        val language = params.optString("language").takeIf { it.isNotBlank() }
        if (language != null) tts.language = Locale.forLanguageTag(language)
        tts.setSpeechRate(params.optDouble("rate", 1.0).toFloat().coerceIn(0.25f, 2f))
        tts.setPitch(params.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 2f))
        val id = UUID.randomUUID().toString()
        callbacks[id] = emit
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            throw HermitException(ErrorCodes.INTERNAL, "朗读任务启动失败")
        }
        return JSONObject().put("utteranceId", id)
    }

    suspend fun synthesize(params: JSONObject, file: File): JSONObject {
        val text = params.optString("text")
        if (text.isBlank() || text.length > 10_000) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "朗读文本为空或过长")
        val tts = getEngine()
        params.optString("language").takeIf { it.isNotBlank() }?.let { tts.language = Locale.forLanguageTag(it) }
        tts.setSpeechRate(params.optDouble("rate", 1.0).toFloat().coerceIn(0.25f, 2f))
        tts.setPitch(params.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 2f))
        val id = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Boolean>()
        completions[id] = completion
        val result = tts.synthesizeToFile(text, null, file, id)
        if (result != TextToSpeech.SUCCESS) {
            completions.remove(id)
            throw HermitException(ErrorCodes.INTERNAL, "语音文件生成失败")
        }
        val completed = try {
            withTimeout(60_000) { completion.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            tts.stop()
            throw HermitException(ErrorCodes.TIMEOUT, "语音文件生成超时", true)
        } finally { completions.remove(id) }
        if (!completed || !file.isFile || file.length() == 0L) throw HermitException(ErrorCodes.INTERNAL, "语音文件生成失败")
        return JSONObject().put("utteranceId", id)
    }

    fun stop(): JSONObject {
        engine?.stop()
        callbacks.clear()
        completions.values.forEach { it.cancel() }
        completions.clear()
        return JSONObject().put("stopped", true)
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        callbacks.clear()
        completions.values.forEach { it.cancel() }
        completions.clear()
    }
}
