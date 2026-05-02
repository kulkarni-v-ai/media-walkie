package com.mediawalkie.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

import android.content.Context

class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        private const val SAMPLE_RATE = 8000 // 8kHz for minimum bandwidth usage
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var isRecording = false
    private var isPlaying = false

    private val minBufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
    private val minBufferSizeOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)

    private val jitterBuffer = ConcurrentLinkedQueue<ByteArray>()
    
    // Callback to send captured audio to RoutingManager
    var onAudioDataCaptured: ((ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRecording) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                minBufferSizeIn
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            Thread {
                val buffer = ByteArray(minBufferSizeIn)
                val chunkBuffer = java.io.ByteArrayOutputStream()
                val TARGET_CHUNK_SIZE = 1600 // 50ms of audio - much lower latency

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        chunkBuffer.write(buffer, 0, read)
                        if (chunkBuffer.size() >= TARGET_CHUNK_SIZE) {
                            val data = chunkBuffer.toByteArray()
                            Log.d(TAG, "Captured chunk: ${data.size} bytes")
                            onAudioDataCaptured?.invoke(data)
                            chunkBuffer.reset()
                        }
                    }
                }
                if (chunkBuffer.size() > 0) {
                    onAudioDataCaptured?.invoke(chunkBuffer.toByteArray())
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
        }
    }

    fun stopCapture() {
        Log.d(TAG, "Stopping capture")
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun startPlayback() {
        if (isPlaying) return
        Log.d(TAG, "Starting playback")

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Volume Boost: Wrapped in try-catch to avoid crashing if permission is denied
            try {
                if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 3) {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC, 
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 
                        0
                    )
                }
            } catch (volEx: Exception) {
                Log.w(TAG, "Could not adjust volume: ${volEx.message}")
            }

            // Small buffer for low latency
            val trackBufferSize = Math.max(minBufferSizeOut * 2, 4096)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(trackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true

            Thread {
                while (isPlaying) {
                    val buffer = jitterBuffer.poll()
                    if (buffer != null) {
                        audioTrack?.write(buffer, 0, buffer.size)
                    } else {
                        // Very short sleep to stay responsive
                        Thread.sleep(5)
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
        }
    }

    fun stopPlayback() {
        Log.d(TAG, "Stopping playback")
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        jitterBuffer.clear()
    }

    fun queueAudioForPlayback(data: ByteArray) {
        if (isPlaying) {
            Log.d(TAG, "Queuing ${data.size} bytes for playback. Jitter buffer: ${jitterBuffer.size}")
            jitterBuffer.offer(data)
            // Jitter buffer: keep only the most recent 300ms to keep latency low
            if (jitterBuffer.size > 6) {
                jitterBuffer.poll() 
            }
        }
    }
}
