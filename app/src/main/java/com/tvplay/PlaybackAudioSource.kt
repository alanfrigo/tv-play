package com.tvplay

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.pedro.common.TimeUtils
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource

class PlaybackAudioSource(
    private val projection: MediaProjection,
    private val onFailure: (String) -> Unit
) : AudioSource() {
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var recordingStarted = false
    private var used = false
    @Volatile private var running = false

    override fun create(
        sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean
    ): Boolean {
        if (sampleRate != 48_000 || !isStereo || echoCanceler || noiseSuppressor) return false
        return try {
            val minSize = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minSize <= 0) return false
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minSize, 15_360))
                .build()
            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                stop()
                false
            } else true
        } catch (error: Exception) {
            Log.w(TAG, "Audio setup: ${error.javaClass.simpleName}")
            stop()
            false
        }
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        val audio = record ?: throw IllegalStateException("Playback capture not prepared")
        if (used || running || reader != null) throw IllegalStateException("Playback capture already used")
        used = true
        try {
            this.getMicrophoneData = getMicrophoneData
            audio.startRecording()
            recordingStarted = true
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Playback capture did not start")
            }
            running = true
            reader = Thread({ readLoop(audio) }, "tvplay-audio").also { it.start() }
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    private fun readLoop(audio: AudioRecord) {
        var buffer = ByteArray(3_840)
        while (running) {
            val count = try {
                audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (error: Exception) {
                Log.w(TAG, "Audio read: ${error.javaClass.simpleName}")
                -1
            }
            if (!running) return
            if (count == 0) {
                try {
                    Thread.sleep(10)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (!running) return
                    break
                }
                continue
            }
            if (count < 0) break
            // read() blocks; returned PCM began count / 192_000 seconds before this clock sample.
            // 3_840 bytes of 48 kHz stereo 16-bit PCM span 20 ms, not 10 ms.
            val timestamp = TimeUtils.getCurrentTimeMicro() - count * 1_000_000L / 192_000L
            try {
                synchronized(this) {
                    if (!running) return
                    getMicrophoneData?.let { microphone ->
                        // Frames queue asynchronously: never overwrite a delivered buffer.
                        microphone.inputPCMData(Frame(buffer, 0, count, timestamp))
                        buffer = ByteArray(3_840)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Audio delivery: ${error.javaClass.simpleName}")
                break
            }
        }
        if (running) {
            running = false
            onFailure("Falha na captura de áudio da TV.")
        }
    }

    override fun stop() {
        running = false
        synchronized(this) { getMicrophoneData = null }
        val audio = record
        val thread = reader
        reader = null
        record = null
        try {
            if (recordingStarted) {
                try {
                    audio?.stop()
                } catch (error: Exception) {
                    Log.w(TAG, "Audio stop: ${error.javaClass.simpleName}")
                }
            }
            recordingStarted = false
            if (thread != null && thread !== Thread.currentThread()) {
                try {
                    thread.join(1_000)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(TAG, "Audio join: ${error.javaClass.simpleName}")
                }
            }
        } finally {
            try {
                audio?.release()
            } catch (error: Exception) {
                Log.w(TAG, "Audio release: ${error.javaClass.simpleName}")
            }
            created = false
        }
    }

    override fun release() = stop()

    override fun isRunning(): Boolean = running

    private companion object {
        const val TAG = "TvPlayAudio"
    }
}
