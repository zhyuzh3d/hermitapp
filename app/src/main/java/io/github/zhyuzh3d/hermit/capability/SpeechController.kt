package io.github.zhyuzh3d.hermit.capability

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HermitException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SpeechController(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var subscriptionId: String? = null

    fun availability(): JSONObject = JSONObject()
        .put("available", SpeechRecognizer.isRecognitionAvailable(context))
        .put("onDeviceAvailable", onDeviceAvailable())

    fun start(params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw HermitException(ErrorCodes.UNSUPPORTED, "系统没有可用的语音识别服务")
        }
        if (recognizer != null) throw HermitException(ErrorCodes.CONFLICT, "已有语音识别任务")
        val id = UUID.randomUUID().toString()
        val requestedOnDevice = params.optBoolean("onDevice", false)
        val useOnDevice = requestedOnDevice && onDeviceAvailable()
        if (requestedOnDevice && !useOnDevice) {
            throw HermitException(ErrorCodes.UNSUPPORTED, "设备没有可用的离线语音识别服务")
        }
        val created = if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = created
        subscriptionId = id
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = emit("speech.ready", JSONObject().put("subscriptionId", id))
            override fun onBeginningOfSpeech() = emit("speech.begin", JSONObject().put("subscriptionId", id))
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = emit("speech.end", JSONObject().put("subscriptionId", id))
            override fun onError(error: Int) {
                emit("speech.error", JSONObject().put("subscriptionId", id).put("code", errorName(error)))
                release(id)
            }
            override fun onResults(results: Bundle?) {
                emit("speech.final", resultJson(id, results))
                release(id)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                emit("speech.partial", resultJson(id, partialResults))
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            params.optString("language").takeIf { it.isNotBlank() }?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, params.optBoolean("partial", true))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, params.optInt("maxResults", 3).coerceIn(1, 5))
        }
        try {
            created.startListening(intent)
        } catch (error: Throwable) {
            release(id)
            throw HermitException(ErrorCodes.INTERNAL, error.message ?: "语音识别启动失败", true)
        }
        return JSONObject().put("subscriptionId", id).put("onDevice", useOnDevice)
    }

    fun stop(id: String?): JSONObject {
        if (idMatches(id)) recognizer?.stopListening()
        return JSONObject().put("stopping", recognizer != null)
    }

    fun cancel(): JSONObject {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        subscriptionId = null
        return JSONObject().put("cancelled", true)
    }

    fun shutdown() = cancel()

    private fun resultJson(id: String, results: Bundle?): JSONObject {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        return JSONObject().put("subscriptionId", id).put("alternatives", JSONArray(texts.mapIndexed { index, text ->
            JSONObject().put("text", text).put("confidence", confidence?.getOrNull(index)?.takeIf { it >= 0 } ?: JSONObject.NULL)
        }))
    }

    private fun release(id: String) {
        if (subscriptionId != id) return
        recognizer?.destroy()
        recognizer = null
        subscriptionId = null
    }

    private fun idMatches(id: String?) = id == null || id == subscriptionId
    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_NO_MATCH -> "no-match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech-timeout"
        else -> "unknown-$error"
    }
}
