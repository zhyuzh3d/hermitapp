package io.github.zhyuzh3d.hermit.capability

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HermitException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** Local microphone recording and managed-file playback without an online media service. */
class AudioController(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var recording: Recording? = null
    private var playback: Playback? = null

    data class RecordedAudio(val file: File, val durationMs: Long)

    private data class Recording(
        val sessionId: String,
        val id: String,
        val file: File,
        val startedAt: Long,
        var recorder: MediaRecorder?,
        var finalizedAt: Long? = null,
    )

    private data class Playback(val sessionId: String, val id: String, val player: MediaPlayer)

    fun microphoneAvailable(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    fun audioOutputAvailable(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    @Suppress("DEPRECATION")
    fun startRecording(sessionId: String, params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        if (!microphoneAvailable()) throw HermitException(ErrorCodes.UNSUPPORTED, "设备没有可用麦克风")
        val maxDurationMs = params.optLong("maxDurationMs", DEFAULT_MAX_DURATION_MS)
            .coerceIn(MIN_MAX_DURATION_MS, MAX_MAX_DURATION_MS)
        val id = UUID.randomUUID().toString()
        val directory = File(appContext.cacheDir, "audio-recordings").apply { mkdirs() }
        val file = File(directory, "$id.m4a")
        synchronized(lock) {
            if (recording != null) throw HermitException(ErrorCodes.CONFLICT, "已有录音正在进行")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()
            val active = Recording(sessionId, id, file, android.os.SystemClock.elapsedRealtime(), recorder)
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setOutputFile(file.absolutePath)
                recorder.setMaxDuration(maxDurationMs.toInt())
                recorder.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        val outcome = synchronized(lock) {
                            if (recording !== active || active.recorder == null) null else runCatching { finalizeLocked(active) }
                        }
                        outcome?.fold(
                            onSuccess = { emit("audio.recording.limit", JSONObject().put("recordingId", id).put("maxDurationMs", maxDurationMs)) },
                            onFailure = {
                                synchronized(lock) {
                                    if (recording === active) recording = null
                                    releaseRecorder(active)
                                    active.file.delete()
                                }
                                emit("audio.recording.error", JSONObject().put("recordingId", id).put("code", ErrorCodes.INTERNAL))
                            },
                        )
                    }
                }
                recorder.prepare()
                recorder.start()
                recording = active
            } catch (error: Throwable) {
                runCatching { recorder.reset() }
                recorder.release()
                file.delete()
                throw HermitException(ErrorCodes.UNSUPPORTED, "无法启动麦克风录音：${error.message ?: "设备录音器不可用"}", true)
            }
        }
        return JSONObject().put("recordingId", id).put("maxDurationMs", maxDurationMs)
    }

    fun stopRecording(sessionId: String, recordingId: String?): RecordedAudio {
        val active = synchronized(lock) {
            val current = recording ?: throw HermitException(ErrorCodes.CONFLICT, "当前没有录音")
            if (current.sessionId != sessionId || (!recordingId.isNullOrBlank() && current.id != recordingId)) {
                throw HermitException(ErrorCodes.CONFLICT, "录音不属于当前页面会话")
            }
            if (current.recorder != null) {
                try {
                    finalizeLocked(current)
                } catch (error: Throwable) {
                    recording = null
                    releaseRecorder(current)
                    current.file.delete()
                    throw HermitException(ErrorCodes.CONFLICT, "录音时间过短或录音失败，请重试", true)
                }
            }
            recording = null
            current
        }
        if (!active.file.isFile || active.file.length() == 0L) {
            active.file.delete()
            throw HermitException(ErrorCodes.STORAGE, "没有生成有效录音文件", true)
        }
        return RecordedAudio(active.file, (active.finalizedAt ?: android.os.SystemClock.elapsedRealtime()) - active.startedAt)
    }

    fun cancelRecording(sessionId: String, recordingId: String?): JSONObject = synchronized(lock) {
        val current = recording
        if (current == null || current.sessionId != sessionId || (!recordingId.isNullOrBlank() && current.id != recordingId)) {
            return@synchronized JSONObject().put("cancelled", false)
        }
        recording = null
        releaseRecorder(current)
        current.file.delete()
        JSONObject().put("cancelled", true)
    }

    fun play(
        sessionId: String,
        input: FileInputStream,
        params: JSONObject,
        emit: (String, JSONObject) -> Unit,
    ): JSONObject {
        if (!audioOutputAvailable()) throw HermitException(ErrorCodes.UNSUPPORTED, "设备没有可用音频输出")
        val id = UUID.randomUUID().toString()
        val player = MediaPlayer()
        synchronized(lock) {
            if (playback != null) {
                player.release()
                throw HermitException(ErrorCodes.CONFLICT, "已有音频正在播放")
            }
            try {
                player.setDataSource(input.fd)
                player.isLooping = params.optBoolean("loop", false)
                val volume = params.optDouble("volume", 1.0).coerceIn(0.0, 1.0).toFloat()
                player.setVolume(volume, volume)
                player.setOnCompletionListener {
                    val completed = synchronized(lock) {
                        if (playback?.player !== it) false else {
                            playback = null
                            it.release()
                            true
                        }
                    }
                    if (completed) emit("audio.playback.done", JSONObject().put("playbackId", id))
                }
                player.setOnErrorListener { failed, _, _ ->
                    val owned = synchronized(lock) {
                        if (playback?.player !== failed) false else {
                            playback = null
                            failed.release()
                            true
                        }
                    }
                    if (owned) emit("audio.playback.error", JSONObject().put("playbackId", id).put("code", ErrorCodes.INTERNAL))
                    true
                }
                player.prepare()
                playback = Playback(sessionId, id, player)
                player.start()
            } catch (error: Throwable) {
                if (playback?.player === player) playback = null
                player.release()
                throw HermitException(ErrorCodes.UNSUPPORTED, "无法播放此音频：${error.message ?: "不支持的音频格式"}", true)
            }
        }
        return JSONObject().put("playbackId", id)
    }

    fun stopPlayback(sessionId: String, playbackId: String?): JSONObject = synchronized(lock) {
        val current = playback
        if (current == null || current.sessionId != sessionId || (!playbackId.isNullOrBlank() && current.id != playbackId)) {
            return@synchronized JSONObject().put("stopped", false)
        }
        playback = null
        runCatching { current.player.stop() }
        current.player.release()
        JSONObject().put("stopped", true)
    }

    fun cancelSession(sessionId: String) = synchronized(lock) {
        recording?.takeIf { it.sessionId == sessionId }?.let {
            recording = null
            releaseRecorder(it)
            it.file.delete()
        }
        playback?.takeIf { it.sessionId == sessionId }?.let {
            playback = null
            runCatching { it.player.stop() }
            it.player.release()
        }
    }

    fun shutdown() = synchronized(lock) {
        recording?.let { releaseRecorder(it); it.file.delete() }
        recording = null
        playback?.let { active -> runCatching { active.player.stop() }; active.player.release() }
        playback = null
    }

    private fun finalizeLocked(active: Recording) {
        val recorder = active.recorder ?: return
        recorder.stop()
        recorder.release()
        active.recorder = null
        active.finalizedAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun releaseRecorder(active: Recording) {
        active.recorder?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
            active.recorder = null
        }
    }

    companion object {
        private const val MIN_MAX_DURATION_MS = 1_000L
        private const val DEFAULT_MAX_DURATION_MS = 5L * 60_000
        private const val MAX_MAX_DURATION_MS = 30L * 60_000
    }
}
