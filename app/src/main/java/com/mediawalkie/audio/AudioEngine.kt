package com.mediawalkie.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

class AudioEngine {

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
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val dataToSend = buffer.copyOf(read)
                        onAudioDataCaptured?.invoke(dataToSend)
                    }
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
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT,
                minBufferSizeOut,
                AudioTrack.MODE_STREAM
            )

            audioTrack?.play()
            isPlaying = true

            Thread {
                while (isPlaying) {
                    val buffer = jitterBuffer.poll()
                    if (buffer != null) {
                        audioTrack?.write(buffer, 0, buffer.size)
                    } else {
                        // Sleep briefly if buffer is empty
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
            // Limit buffer size to prevent massive latency build-up
            if (jitterBuffer.size > 20) {
                jitterBuffer.poll() // Drop oldest packet
            }
        }
    }
}
