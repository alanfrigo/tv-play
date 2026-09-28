package com.tvplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.socket.base.SocketType
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.input.sources.MediaProjectionHandler
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.rtsp.RtspStream
import com.pedro.rtsp.rtsp.Protocol

class CaptureService : Service() {
    enum class State { IDLE, STARTING, LIVE, STOPPING, ERROR }

    inner class LocalBinder : Binder() {
        val service: CaptureService get() = this@CaptureService
    }

    private class Session(val id: Long) {
        var stream: RtspStream? = null
        var projection: MediaProjection? = null
        var callback: MediaProjection.Callback? = null
        var networkStarted = false
        var networkDisconnected = false
        var retryPending = false
        var failed = false
        var terminalMessage = "Transmissão encerrada."
        var videoBitrate = 0
        var maxVideoBitrate = 0
        var clearSamples = 0
        var fpsClearSamples = 0
        var droppedFrames = 0L
        var videoFps = 30
        var sentBytes = 0L
        var sampleTime = 0L
        var stalledSamples = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()
    private val observers = mutableSetOf<(State, String) -> Unit>()
    private val settings by lazy { getSharedPreferences("capture_status", MODE_PRIVATE) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var state = State.IDLE
    private var message = "Parado"
    private var nextId = 0L
    private var session: Session? = null
    private var foreground = false
    private var disconnectTimeout: Runnable? = null
    private var qualityCheck: Runnable? = null
    private var connectTimeout: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transmissão da TV", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun observe(listener: (State, String) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        observers.add(listener)
        listener(state, message)
    }

    fun removeObserver(listener: (State, String) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        observers.remove(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android delivers service commands on main; each callback below returns to same looper.
        when (intent?.action) {
            ACTION_START -> if (state != State.STARTING && state != State.LIVE && state != State.STOPPING) {
                startCapture(intent)
            } else {
                // A duplicate consent result cannot become a later session's authorization.
                intent.removeExtra("nas_ip")
                intent.removeExtra("publish_password")
                intent.removeExtra("result_code")
                intent.removeExtra("result_data")
            }
            ACTION_STOP -> if (session != null) stopCapture("Transmissão encerrada.", false)
                else stopSelf(startId)
            else -> if (session == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val ip: String?
        val password: String?
        val code: Int
        val data: Intent?
        try {
            ip = intent.getStringExtra("nas_ip")
            password = intent.getStringExtra("publish_password")
            code = intent.getIntExtra("result_code", 0)
            @Suppress("DEPRECATION")
            data = intent.getParcelableExtra<Intent>("result_data")
        } catch (error: RuntimeException) {
            logCleanup("Capture extras", error)
            finishInvalidStart()
            return
        } finally {
            intent.removeExtra("nas_ip")
            intent.removeExtra("publish_password")
            intent.removeExtra("result_code")
            intent.removeExtra("result_data")
        }
        if (ip == null || !MainActivity.isPrivateIPv4(ip) || password.isNullOrEmpty() ||
            code != android.app.Activity.RESULT_OK || data == null ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            finishInvalidStart()
            return
        }

        val current = Session(++nextId)
        session = current
        publish(State.STARTING, "Iniciando transmissão")
        try {
            startForeground(
                NOTIFICATION_ID, notification("Transmissão da TV ativa"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            foreground = true
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(code, data)
                ?: throw IllegalStateException("No projection returned")
            current.projection = projection
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    runOnMain {
                        if (session?.id == current.id && state != State.STOPPING) {
                            stopCapture("Captura de tela encerrada pela TV.", true)
                        }
                    }
                }
            }
            current.callback = callback
            projection.registerCallback(callback, handler)

            val audio = PlaybackAudioSource(projection) { reason ->
                runOnMain { if (session?.id == current.id && state != State.STOPPING) stopCapture(reason, true) }
            }
            val stream = RtspStream(
                applicationContext, checker(current), ScreenSource(applicationContext, projection), audio
            )
            current.stream = stream
            stream.setVideoCodec(VideoCodec.H264)
            stream.setAudioCodec(AudioCodec.OPUS)
            stream.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
            val client = stream.getStreamClient() as com.pedro.library.util.streamclient.RtspStreamClient
            client.apply {
                setProtocol(Protocol.TCP)
                setSocketType(SocketType.KTOR)
                setAuthorization("tvpublisher", password)
                setLogs(false)
                setReTries(7)
                setSocketTimeout(10_000L)
            }
            stream.setEncoderErrorCallback(object : CodecErrorCallback {
                override fun onCodecError(type: CodecUtil.CodecTypeError, e: MediaCodec.CodecException) {
                    encoderFailed(current)
                }

                override fun onEncodeError(type: CodecUtil.CodecTypeError, e: IllegalStateException): Boolean {
                    encoderFailed(current)
                    return false
                }
            })
            // WebRTC browsers reject H.264 B-frames; Baseline keeps direct playback compatible.
            val videoReady = try {
                stream.prepareVideo(
                    1920, 1080, 4_000_000, fps = 30, iFrameInterval = 1,
                    profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
            } catch (error: Exception) {
                logCleanup("1080p prepare", error)
                false
            }
            val prepared = if (videoReady) {
                current.videoBitrate = 4_000_000
                current.maxVideoBitrate = 4_000_000
                true
            } else {
                try {
                    stream.prepareVideo(
                        1280, 720, 2_000_000, fps = 30, iFrameInterval = 1,
                        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    ).also {
                        if (it) {
                            current.videoBitrate = 2_000_000
                            current.maxVideoBitrate = 2_000_000
                        }
                    }
                } catch (error: Exception) {
                    logCleanup("720p prepare", error)
                    false
                }
            }
            if (!prepared) {
                stopCapture("Esta TV não oferece codificação H.264 compatível.", true)
                return
            }
            val audioReady = try {
                stream.prepareAudio(
                    sampleRate = 48_000, isStereo = true, bitrate = 96_000,
                    echoCanceler = false, noiseSuppressor = false
                )
            } catch (error: Exception) {
                logCleanup("Audio prepare", error)
                false
            }
            if (!audioReady) {
                stopCapture("Esta TV não oferece captura de áudio/Opus compatível.", true)
                return
            }
            stream.getGlInterface().setForceRender(true, 30)
            // startStream starts RTSP before sources: synchronous source failure still needs teardown.
            current.networkStarted = true
            watchConnection(current)
            stream.startStream("rtsp://$ip:8554/tv")
        } catch (error: Exception) {
            logCleanup("Capture start", error)
            stopCapture("Não foi possível iniciar captura nesta TV.", true)
        }
    }

    private fun encoderFailed(current: Session) {
        handler.post {
            if (session?.id == current.id && state != State.STOPPING) {
                stopCapture("Falha no codificador da TV.", true)
            }
        }
    }

    private fun checker(current: Session) = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            runOnMain {
                if (session?.id != current.id || state != State.STARTING) return@runOnMain
                current.retryPending = false
                watchConnection(current)
            }
        }
        override fun onConnectionSuccess() {
            runOnMain {
                if (session?.id == current.id && state == State.STARTING) {
                    connectTimeout?.let(handler::removeCallbacks)
                    connectTimeout = null
                    current.stream?.requestKeyframe()
                    publish(State.LIVE, "Transmitindo")
                    monitorQuality(current)
                }
            }
        }
        override fun onConnectionFailed(reason: String) {
            runOnMain {
                if (session?.id != current.id || state == State.STOPPING || current.retryPending) return@runOnMain
                val client = current.stream?.getStreamClient() ?: return@runOnMain
                if (!reason.contains("access denied", ignoreCase = true) && client.reTry(2_000L, reason)) {
                    current.retryPending = true
                    qualityCheck?.let(handler::removeCallbacks)
                    qualityCheck = null
                    publish(State.STARTING, "Reconectando ao NAS")
                    watchConnection(current)
                } else {
                    stopCapture("Conexão com o NAS encerrada. Verifique rede, endereço e se já existe outra transmissão.", true)
                }
            }
        }
        override fun onDisconnect() {
            runOnMain {
                if (session?.id != current.id) return@runOnMain
                current.networkDisconnected = true
                if (state == State.STOPPING) finishStop(current)
                else if (state == State.STARTING || state == State.LIVE) {
                    stopCapture("Conexão com o NAS encerrada.", true)
                }
            }
        }
        override fun onAuthError() {
            runOnMain {
                if (session?.id == current.id && state != State.STOPPING) {
                    stopCapture("Senha de transmissão inválida.", true)
                }
            }
        }
        override fun onAuthSuccess() = Unit
    }

    private fun watchConnection(current: Session) {
        connectTimeout?.let(handler::removeCallbacks)
        val timeout = Runnable {
            if (session?.id == current.id && state == State.STARTING) {
                stopCapture("Tempo limite para conectar ao NAS. Inicie novamente.", true)
            }
        }
        connectTimeout = timeout
        handler.postDelayed(timeout, 20_000L)
    }

    private fun monitorQuality(current: Session) {
        val client = current.stream?.getStreamClient() ?: return
        current.sentBytes = client.getBytesSend()
        current.sampleTime = android.os.SystemClock.elapsedRealtime()
        current.droppedFrames = client.getDroppedVideoFrames()
        current.clearSamples = 0
        current.fpsClearSamples = 0
        current.stalledSamples = 0
        val check = object : Runnable {
            override fun run() {
                if (session?.id != current.id || state != State.LIVE) return
                val stream = current.stream ?: return
                val client = stream.getStreamClient()
                val now = android.os.SystemClock.elapsedRealtime()
                val bytes = client.getBytesSend()
                val elapsed = now - current.sampleTime
                val sent = bytes - current.sentBytes
                current.sampleTime = now
                current.sentBytes = bytes
                val bitrate = if (elapsed > 0 && sent >= 0) sent * 8_000L / elapsed else 0L
                val limit = current.maxVideoBitrate * 11L / 10L // áudio e overhead RTSP
                val dropped = client.getDroppedVideoFrames()
                val congested = client.hasCongestion(10f) || dropped > current.droppedFrames
                // ponytail: Ktor não limita escrita NIO; progresso + fila detectam bloqueio real.
                current.stalledSamples = if (sent <= 0 && client.hasCongestion(10f))
                    current.stalledSamples + 1 else 0
                if (current.stalledSamples >= 5) {
                    checker(current).onConnectionFailed("RTSP sem progresso de envio")
                    return
                }
                current.droppedFrames = dropped
                if (congested) {
                    current.clearSamples = 0
                    val target = maxOf(current.videoBitrate * 3 / 4, 2_000_000)
                    if (target < current.videoBitrate) {
                        current.videoBitrate = target
                        stream.setVideoBitrateOnFly(target)
                    }
                    if (sent > 0 && client.hasCongestion(50f)) {
                        client.clearCache()
                        stream.requestKeyframe()
                    }
                } else if (++current.clearSamples >= 3) {
                    current.clearSamples = 0
                    val target = minOf(current.videoBitrate + 500_000, current.maxVideoBitrate)
                    if (target > current.videoBitrate) {
                        current.videoBitrate = target
                        stream.setVideoBitrateOnFly(target)
                    }
                }
                // ponytail: encoder da TV pode ignorar bitrate; limitar quadros, sem trocar resolução em sessão ativa.
                val fps = when {
                    congested -> {
                        current.fpsClearSamples = 0
                        maxOf(current.videoFps * 3 / 4, 5)
                    }
                    bitrate > limit -> {
                        current.fpsClearSamples = 0
                        maxOf((current.videoFps * limit / bitrate).toInt(), 5)
                    }
                    bitrate > 0 && current.videoFps < 30 &&
                        bitrate * (current.videoFps + 1) <= limit * current.videoFps * 19 / 20 -> {
                        if (++current.fpsClearSamples >= 2) {
                            current.fpsClearSamples = 0
                            val step = if (bitrate * (current.videoFps + 3) <=
                                limit * current.videoFps * 19 / 20) 3 else 1
                            minOf(current.videoFps + step, 30)
                        } else current.videoFps
                    }
                    else -> {
                        current.fpsClearSamples = 0
                        current.videoFps
                    }
                }
                if (fps != current.videoFps) {
                    current.videoFps = fps
                    stream.getGlInterface().forceFpsLimit(fps)
                }
                handler.postDelayed(this, 2_000L)
            }
        }
        qualityCheck = check
        handler.postDelayed(check, 2_000L)
    }

    private fun stopCapture(terminalMessage: String, failed: Boolean) {
        val current = session ?: return
        connectTimeout?.let(handler::removeCallbacks)
        connectTimeout = null
        qualityCheck?.let(handler::removeCallbacks)
        qualityCheck = null
        if (state == State.STOPPING) return
        current.terminalMessage = terminalMessage
        current.failed = failed
        publish(State.STOPPING, "Encerrando transmissão")
        if (foreground) {
            try {
                notifications.notify(NOTIFICATION_ID, notification("Encerrando transmissão"))
            } catch (error: Exception) {
                logCleanup("Notification update", error)
            }
        }
        releaseLocal(current)
        if (current.networkStarted && !current.networkDisconnected) {
            val timeout = Runnable {
                if (session?.id == current.id && state == State.STOPPING) {
                    current.failed = true
                    if (current.terminalMessage == "Transmissão encerrada.") {
                        current.terminalMessage = "Encerramento de rede incompleto. Aguarde 10 segundos antes de tentar novamente."
                    }
                    finishStop(current)
                }
            }
            disconnectTimeout = timeout
            handler.postDelayed(timeout, 10_000L)
        } else {
            finishStop(current)
        }
    }

    private fun releaseLocal(current: Session) {
        // RootEncoder release() includes stopStream(); never call stopStream() separately.
        try {
            current.stream?.release()
        } catch (error: Exception) {
            logCleanup("Stream release", error)
            // If upstream release stops before reaching remaining sources, try both independently.
            try {
                current.stream?.audioSource?.stop()
            } catch (stopError: Exception) {
                logCleanup("Audio cleanup", stopError)
            }
            try {
                current.stream?.videoSource?.stop()
            } catch (stopError: Exception) {
                logCleanup("Video cleanup", stopError)
            }
        } finally {
            try {
                current.stream?.getStreamClient()?.setAuthorization(null, null)
            } catch (error: Exception) {
                logCleanup("Credential cleanup", error)
            }
            current.stream = null
            val projection = current.projection
            current.projection = null
            try {
                if (projection != null && current.callback != null) {
                    projection.unregisterCallback(current.callback!!)
                }
            } catch (error: Exception) {
                logCleanup("Projection unregister", error)
            } finally {
                current.callback = null
                try {
                    projection?.stop()
                } catch (error: Exception) {
                    logCleanup("Projection stop", error)
                } finally {
                    if (MediaProjectionHandler.mediaProjection === projection) {
                        MediaProjectionHandler.mediaProjection = null
                    }
                }
            }
        }
    }

    private fun finishStop(current: Session) {
        if (session?.id != current.id) return
        disconnectTimeout?.let(handler::removeCallbacks)
        connectTimeout?.let(handler::removeCallbacks)
        connectTimeout = null
        qualityCheck?.let(handler::removeCallbacks)
        qualityCheck = null
        disconnectTimeout = null
        session = null
        settings.edit().putString("terminal_message", current.terminalMessage).apply()
        publish(if (current.failed) State.ERROR else State.IDLE, current.terminalMessage)
        if (foreground) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun finishInvalidStart() {
        val terminal = "Dados de captura inválidos. Inicie novamente."
        settings.edit().putString("terminal_message", terminal).apply()
        publish(State.ERROR, terminal)
        stopSelf()
    }

    private fun publish(newState: State, newMessage: String) {
        state = newState
        message = newMessage
        observers.toList().forEach { it(newState, newMessage) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun notification(title: String): Notification {
        val stop = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val pending = PendingIntent.getService(
            this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Parar", pending)
            .build()
    }

    override fun onDestroy() {
        qualityCheck?.let(handler::removeCallbacks)
        connectTimeout?.let(handler::removeCallbacks)
        connectTimeout = null
        qualityCheck = null
        disconnectTimeout?.let(handler::removeCallbacks)
        disconnectTimeout = null
        session?.let { current ->
            session = null
            releaseLocal(current)
        }
        if (foreground) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        observers.clear()
        super.onDestroy()
    }

    private fun logCleanup(operation: String, error: Exception) {
        Log.w(TAG, "$operation: ${error.javaClass.simpleName}")
    }

    private companion object {
        const val TAG = "TvPlayCapture"
        const val CHANNEL = "tvplay_capture"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.tvplay.START"
        const val ACTION_STOP = "com.tvplay.STOP"
    }
}
