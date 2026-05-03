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
    
    var userName: String = "User" // Set from MainActivity

    companion object {
        private const val TAG = "AudioEngine"
        private const val SAMPLE_RATE = 16000 
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val HEADER_SIZE = 32 // Increased for Name
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var isRecording = false
    private var isPlaying = false

    private val minBufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
    private val minBufferSizeOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)

    private val jitterBuffer = ConcurrentLinkedQueue<ByteArray>()
    
    var onAudioDataCaptured: ((ByteArray) -> Unit)? = null
    var onSpeakerChanged: ((String?) -> Unit)? = null

    private var currentSpeaker: String? = null
    private var lastSpeakerTimestamp = 0L

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRecording) return
        Log.d(TAG, "Starting capture")

        try {
            val minBufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            
            // Try VOICE_COMMUNICATION first, fallback to MIC
            val sources = listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
            var initialized = false
            
            for (source in sources) {
                try {
                    audioRecord = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, minBufferSizeIn * 2)
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord?.startRecording()
                        Log.d(TAG, "Started capture with source: $source")
                        initialized = true
                        break
                    } else {
                        audioRecord?.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to init source $source: ${e.message}")
                }
            }
            
            if (!initialized) {
                Log.e(TAG, "COULD NOT INITIALIZE ANY AUDIO SOURCE")
                return
            }
            
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
                val TARGET_CHUNK_SIZE = 1600 // 50ms at 16kHz - low latency

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // Amplitude Check: Log every 100 packets to avoid spam
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i+1].toInt() shl 8)
                            sum += Math.abs(sample.toDouble())
                        }
                        val avg = sum / (read / 2)
                        if (sequenceNumber % 100 == 0L) {
                            Log.d(TAG, "Mic Capture: $read bytes, Avg Amplitude: $avg")
                        }

                        chunkBuffer.write(buffer, 0, read)
                        if (chunkBuffer.size() >= TARGET_CHUNK_SIZE) {
                            val audioData = chunkBuffer.toByteArray()
                            
                            // 32-Byte Header: [ID(4)][Seq(8)][Name(20)]
                            val packet = java.io.ByteArrayOutputStream()
                            packet.write(deviceId.toByteArray())
                            val seqBytes = java.nio.ByteBuffer.allocate(8).putLong(sequenceNumber++).array()
                            packet.write(seqBytes)
                            
                            // Name: Padded to 20 bytes
                            val nameBytes = userName.toByteArray().let {
                                if (it.size > 20) it.copyOfRange(0, 20)
                                else it + ByteArray(20 - it.size)
                            }
                            packet.write(nameBytes)
                            packet.write(audioData)
                            
                            val finalPayload = packet.toByteArray()
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
            
            val trackBufferSize = Math.max(minBufferSizeOut * 2, 4096)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
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
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            audioTrack?.play()
            isPlaying = true

            Thread {
                val PREFETCH_COUNT = 3
                while (isPlaying) {
                    if (jitterBuffer.size < PREFETCH_COUNT) {
                        // Wait for buffer to fill to avoid jitter/clicking
                        delay(20)
                        continue
                    }
                    
                    val buffer = jitterBuffer.poll()
                    if (buffer != null) {
                        audioTrack?.write(buffer, 0, buffer.size)
                        
                        // Speaker status timeout
                        if (System.currentTimeMillis() - lastSpeakerTimestamp > 1500) {
                            updateSpeaker(null)
                        }
                    } else {
                        Thread.sleep(10)
                        if (System.currentTimeMillis() - lastSpeakerTimestamp > 800) {
                            updateSpeaker(null)
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
        }
    }

    private fun updateSpeaker(name: String?) {
        if (currentSpeaker != name) {
            currentSpeaker = name
            onSpeakerChanged?.invoke(name)
        }
    }

    fun stopPlayback() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        jitterBuffer.clear()
    }

    fun queueAudioForPlayback(payload: ByteArray) {
        if (!isPlaying) return

        try {
            if (payload.size < HEADER_SIZE) return
            val senderId = String(payload.copyOfRange(0, 4))
            val seq = java.nio.ByteBuffer.wrap(payload.copyOfRange(4, 12)).long
            val packetId = "$senderId-$seq"

            // 1. Deduplication
            if (processedPacketIds.contains(packetId)) return
            processedPacketIds.add(packetId)
            if (processedPacketIds.size > 200) processedPacketIds.remove(processedPacketIds.first())

            // 2. Self-Mute
            if (senderId == deviceId) return

            // 3. Extract Name
            val senderName = String(payload.copyOfRange(12, 32)).trim { it <= ' ' || it == '\u0000' }
            lastSpeakerTimestamp = System.currentTimeMillis()
            updateSpeaker(senderName)

            val audioData = payload.copyOfRange(HEADER_SIZE, payload.size)
            jitterBuffer.offer(audioData)
            if (jitterBuffer.size > 16) jitterBuffer.poll() // Increased to 16 for better jitter handling
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming packet", e)
        }
    }
}
