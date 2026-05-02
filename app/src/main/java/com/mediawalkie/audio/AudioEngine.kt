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

    private val deviceId = java.util.UUID.randomUUID().toString().substring(0, 4)
    private var sequenceNumber = 0L
    private val processedPacketIds = mutableSetOf<String>()

    companion object {
        private const val TAG = "AudioEngine"
        private const val SAMPLE_RATE = 16000 // Revert to 16kHz for better hardware compatibility
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
        Log.d(TAG, "Starting capture")

        try {
            val minBufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                minBufferSizeIn * 2
            )

            audioRecord?.startRecording()
            isRecording = true

            // Enable Hardware Echo Cancellation & Noise Suppression
            try {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    android.media.audiofx.AcousticEchoCanceler.create(audioRecord!!.audioSessionId).apply {
                        enabled = true
                    }
                }
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    android.media.audiofx.NoiseSuppressor.create(audioRecord!!.audioSessionId).apply {
                        enabled = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Hardware audio FX not available: ${e.message}")
            }

            Thread {
                val buffer = ByteArray(minBufferSizeIn)
                val chunkBuffer = java.io.ByteArrayOutputStream()
                val TARGET_CHUNK_SIZE = 3200 // 100ms at 16kHz

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        chunkBuffer.write(buffer, 0, read)
                        if (chunkBuffer.size() >= TARGET_CHUNK_SIZE) {
                            val audioData = chunkBuffer.toByteArray()
                            
                            // Add Deduplication Header: [DeviceID(4b)][Seq(8b)][Data...]
                            val packet = java.io.ByteArrayOutputStream()
                            packet.write(deviceId.toByteArray())
                            val seqBytes = java.nio.ByteBuffer.allocate(8).putLong(sequenceNumber++).array()
                            packet.write(seqBytes)
                            packet.write(audioData)
                            
                            val finalPayload = packet.toByteArray()
                            Log.d(TAG, "Captured chunk: ${finalPayload.size} bytes (Seq: $sequenceNumber)")
                            onAudioDataCaptured?.invoke(finalPayload)
                            chunkBuffer.reset()
                        }
                    }
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

    fun queueAudioForPlayback(payload: ByteArray) {
        if (!isPlaying) return

        try {
            // Extract Header: [DeviceID(4b)][Seq(8b)]
            if (payload.size < 12) return
            val senderId = String(payload.copyOfRange(0, 4))
            val seq = java.nio.ByteBuffer.wrap(payload.copyOfRange(4, 12)).long
            val packetId = "$senderId-$seq"

            // 1. Deduplication: Don't play (or relay) the same packet twice
            if (processedPacketIds.contains(packetId)) return
            processedPacketIds.add(packetId)
            if (processedPacketIds.size > 100) processedPacketIds.remove(processedPacketIds.first())

            // 2. Self-Mute: Don't play our own forwarded audio
            if (senderId == deviceId) return

            val audioData = payload.copyOfRange(12, payload.size)
            Log.d(TAG, "Queuing packet $packetId for playback")
            
            jitterBuffer.offer(audioData)
            if (jitterBuffer.size > 6) jitterBuffer.poll()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming packet", e)
        }
    }
}
