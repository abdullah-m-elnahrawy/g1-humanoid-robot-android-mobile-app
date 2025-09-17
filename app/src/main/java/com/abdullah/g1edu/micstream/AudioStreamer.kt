package com.abdullah.g1edu.micstream

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.TrafficStats
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class AudioStreamer(
    private val context: Context,
    private val groupIp: String,
    private val port: Int,
    private val useUnicast: Boolean,
    private val unicastIp: String,
    private val preferVoiceComm: Boolean,
    private val onLog: (String) -> Unit,
    private val onState: (Boolean) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun isRunning() = running.get()

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(::runLoop, "G1MicStreamThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        onState(true)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
        onState(false)
    }

    private fun runLoop() {
        TrafficStats.setThreadStatsTag(0xA11D) // mark for debugging
        var audioRecord: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        var socket: DatagramSocket? = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prevMode = audioManager.mode
        val prevSpeaker = audioManager.isSpeakerphoneOn

        try {
            // Prefer voice communication mode for better acoustic pipeline
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true // typical for handheld; change if using headset
        } catch (_: Exception) { }

        try {
            val source = if (preferVoiceComm) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                         else MediaRecorder.AudioSource.VOICE_RECOGNITION

            // Try 24k first, fall back to 48k, 44.1k, 16k
            val desired = listOf(BuildConfig.TARGET_SR, 48000, 44100, 16000)
            var chosenRate = 0
            var minBuf = 0

            for (sr in desired) {
                val sz = AudioRecord.getMinBufferSize(
                    sr,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (sz > 0 && sz != AudioRecord.ERROR_BAD_VALUE) {
                    chosenRate = sr
                    minBuf = sz
                    break
                }
            }
            if (chosenRate == 0) {
                onLog("No supported sample rate found.")
                running.set(false); return
            }

            val frameMs = BuildConfig.FRAME_MS
            val inSamplesPerFrame = (chosenRate * frameMs) / 1000
            val outSamplesPerFrame = (BuildConfig.TARGET_SR * frameMs) / 1000 // 480 samples
            val frameBytes = outSamplesPerFrame * 2

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(chosenRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                onLog("AudioRecord init failed.")
                running.set(false); return
            }

            // Enable AEC/NS/AGC if available
            val sessionId = audioRecord.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
                onLog("AEC enabled: ${aec?.enabled == true}")
            } else onLog("AEC not available.")

            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
                onLog("NoiseSuppressor enabled: ${ns?.enabled == true}")
            } else onLog("NoiseSuppressor not available.")

            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
                onLog("AGC enabled: ${agc?.enabled == true}")
            } else onLog("AGC not available.")

            audioRecord.startRecording()
            onLog("Recording at ${chosenRate} Hz (target ${BuildConfig.TARGET_SR} Hz).")

            // UDP socket
            socket = DatagramSocket()
            socket.soTimeout = 0
            val targetAddr = InetAddress.getByName(if (useUnicast) unicastIp else groupIp)

            val inBuf = ShortArray(inSamplesPerFrame)
            val outBuf = ShortArray(outSamplesPerFrame)
            val outBytes = ByteArray(frameBytes)

            // For 48k -> 24k, do simple 2:1 average downsample (better than drop)
            fun downsample2to1(src: ShortArray, dst: ShortArray) {
                val n = dst.size
                for (i in 0 until n) {
                    val a = src[2 * i].toInt()
                    val b = src[2 * i + 1].toInt()
                    val avg = ((a + b) / 2)
                    dst[i] = avg.toShort()
                }
            }

            // Generic linear resample (simple, ok for speech)
            fun linearResample(src: ShortArray, srcRate: Int, dst: ShortArray, dstRate: Int) {
                val ratio = srcRate.toDouble() / dstRate.toDouble()
                val nDst = dst.size
                val nSrc = src.size
                var srcPos = 0.0
                for (i in 0 until nDst) {
                    val p0 = srcPos.toInt().coerceIn(0, nSrc - 1)
                    val p1 = (p0 + 1).coerceAtMost(nSrc - 1)
                    val frac = srcPos - p0
                    val s0 = src[p0].toInt()
                    val s1 = src[p1].toInt()
                    val v = (s0 + (s1 - s0) * frac).roundToInt()
                    dst[i] = v.toShort()
                    srcPos += ratio
                }
            }

            while (running.get()) {
                val read = audioRecord.read(inBuf, 0, inBuf.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    onLog("Audio read error: $read")
                    break
                }

                // Resample to 24k if needed
                when {
                    chosenRate == BuildConfig.TARGET_SR && read == outBuf.size -> {
                        System.arraycopy(inBuf, 0, outBuf, 0, outBuf.size)
                    }
                    chosenRate == 48000 && read >= outBuf.size * 2 -> {
                        downsample2to1(inBuf, outBuf)
                    }
                    else -> {
                        // Fallback linear resample per-frame
                        linearResample(inBuf, chosenRate, outBuf, BuildConfig.TARGET_SR)
                    }
                }

                // Little-endian PCM16 payload
                ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    .put(outBuf, 0, outBuf.size)

                val packet = DatagramPacket(outBytes, outBytes.size, targetAddr, port)
                socket.send(packet)
            }

        } catch (ex: Exception) {
            onLog("Streamer error: ${ex.message ?: ex.javaClass.simpleName}")
            Log.e("G1MicStream", "Streamer error", ex)
        } finally {
            try { threadSleep(10) } catch (_: Exception) { }
            try { socket?.close() } catch (_: Exception) { }
            try { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
                mode = prevMode
                isSpeakerphoneOn = prevSpeaker
            } } catch (_: Exception) { }
            try { aec?.enabled = false; aec?.release() } catch (_: Exception) { }
            try { ns?.enabled = false; ns?.release() } catch (_: Exception) { }
            try { agc?.enabled = false; agc?.release() } catch (_: Exception) { }
            try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) { }
        }
    }

    private fun threadSleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }
}
