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
        private const val SAMPLE_RATE = 16000 // 16kHz
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
                val TARGET_CHUNK_SIZE = 3200 // 100ms of audio at 16kHz

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        chunkBuffer.write(buffer, 0, read)
                        if (chunkBuffer.size() >= TARGET_CHUNK_SIZE) {
                            onAudioDataCaptured?.invoke(chunkBuffer.toByteArray())
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
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun startPlayback() {
        if (isPlaying) return

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Volume Boost
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 3) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC, 
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 
                    0
                )
            }

            val trackBufferSize = Math.max(minBufferSizeOut * 8, 16384)

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
                        Thread.sleep(10)
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
        }
    }

    fun stopPlayback() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        jitterBuffer.clear()
    }

    fun queueAudioForPlayback(data: ByteArray) {
        if (isPlaying) {
            jitterBuffer.offer(data)
            // Jitter buffer: wait for at least 2 packets before starting to play to smooth out "cut cut"
            // but keep it small enough to avoid massive delay
            if (jitterBuffer.size > 10) {
                jitterBuffer.poll() 
            }
        }
    }
}
