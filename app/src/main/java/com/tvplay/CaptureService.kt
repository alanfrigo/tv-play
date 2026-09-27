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
        var failed = false
        var terminalMessage = "Transmissão encerrada."
        var initialWidth = 0
        var initialHeight = 0
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

                override fun onCapturedContentResize(width: Int, height: Int) {
                    runOnMain {
                        if (session?.id != current.id || state == State.STOPPING) return@runOnMain
                        if (current.initialWidth == 0 && current.initialHeight == 0) {
                            current.initialWidth = width
                            current.initialHeight = height
                        } else if (current.initialWidth != width || current.initialHeight != height) {
                            stopCapture("A tela mudou. Inicie a transmissão novamente.", true)
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
            (stream.getStreamClient() as com.pedro.library.util.streamclient.RtspStreamClient).apply {
                setProtocol(Protocol.TCP)
                setAuthorization("tvpublisher", password)
                setLogs(false)
                setReTries(0)
                setSocketTimeout(5_000L)
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
            val videoReady = try {
                stream.prepareVideo(
                    1280, 720, 2_000_000, fps = 30, iFrameInterval = 1,
                    rotation = 0, profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
            } catch (error: Exception) {
                logCleanup("Video prepare", error)
                false
            }
            if (!videoReady) {
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
        override fun onConnectionStarted(url: String) = Unit
        override fun onConnectionSuccess() {
            runOnMain {
                if (session?.id == current.id && state == State.STARTING) publish(State.LIVE, "Transmitindo")
            }
        }
        override fun onConnectionFailed(reason: String) {
            connectionLost(current)
        }
        override fun onDisconnect() {
            runOnMain {
                if (session?.id != current.id) return@runOnMain
                current.networkDisconnected = true
                if (state == State.STOPPING) finishStop(current)
                else if (state == State.STARTING || state == State.LIVE) connectionLost(current)
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

    private fun connectionLost(current: Session) {
        runOnMain {
            if (session?.id == current.id && state != State.STOPPING) {
                stopCapture(
                    "Conexão com o NAS encerrada. Verifique rede, endereço e se já existe outra transmissão.",
                    true
                )
            }
        }
    }

    private fun stopCapture(terminalMessage: String, failed: Boolean) {
        val current = session ?: return
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
                    current.terminalMessage =
                        "Encerramento de rede incompleto. Aguarde 10 segundos antes de tentar novamente."
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
