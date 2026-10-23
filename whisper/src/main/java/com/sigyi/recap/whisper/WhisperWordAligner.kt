package com.sigyi.recap.whisper

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

class WhisperWordAligner(context: Context) {
    private val modelManager = WhisperModelManager(context.applicationContext)
    private var native: WhisperNative? = null

    @Synchronized
    fun align(
        wavFile: File,
        onModelProgress: (Int) -> Unit = {}
    ): List<WhisperWord> {
        val model = modelManager.ensureModel(onModelProgress)
        if (native == null) native = WhisperNative.open(model)
        val samples = WavPcmReader.readMono16k(wavFile)
        return native!!.transcribeWordTimestamps(samples)
    }

    @Synchronized
    fun close() {
        native?.close()
        native = null
    }
}

private object WavPcmReader {
    fun readMono16k(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "Whisper alignment အတွက် PCM WAV လိုအပ်ပါတယ်။"
        }

        var offset = 12
        var audioFormat = 1
        var channels = 1
        var sampleRate = 16_000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = intLE(bytes, offset + 4)
            val body = offset + 8
            if (body + size > bytes.size) break
            when (id) {
                "fmt " -> {
                    audioFormat = shortLE(bytes, body)
                    channels = shortLE(bytes, body + 2)
                    sampleRate = intLE(bytes, body + 4)
                    bitsPerSample = shortLE(bytes, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataSize = size
                    break
                }
            }
            offset = body + size + (size and 1)
        }
        require(dataOffset >= 0 && sampleRate > 0 && channels > 0) { "WAV data chunk မတွေ့ပါ။" }
        require(audioFormat == 1 || audioFormat == 3) { "PCM/float WAV သာ support လုပ်ထားပါတယ်။" }

        val frameBytes = channels * (bitsPerSample / 8)
        require(frameBytes > 0) { "WAV bit depth မမှန်ပါ။" }
        val frames = dataSize / frameBytes
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                val position = dataOffset + frame * frameBytes + channel * (bitsPerSample / 8)
                sum += sample(bytes, position, bitsPerSample, audioFormat)
            }
            mono[frame] = sum / channels
        }
        if (sampleRate == 16_000) return mono
        val outputFrames = floor(mono.size.toDouble() * 16_000.0 / sampleRate).toInt().coerceAtLeast(1)
        return FloatArray(outputFrames) { index ->
            val source = index.toDouble() * sampleRate / 16_000.0
            val left = source.toInt().coerceIn(0, mono.lastIndex)
            val right = (left + 1).coerceAtMost(mono.lastIndex)
            val fraction = (source - left).toFloat()
            mono[left] * (1f - fraction) + mono[right] * fraction
        }
    }

    private fun sample(bytes: ByteArray, offset: Int, bits: Int, format: Int): Float {
        return when {
            format == 3 && bits == 32 -> ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
            bits == 8 -> (bytes[offset].toInt() - 128) / 128f
            bits == 16 -> shortLE(bytes, offset) / 32768f
            bits == 24 -> {
                val value = (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    (bytes[offset + 2].toInt() shl 16)
                val signed = if (value and 0x800000 != 0) value or -0x1000000 else value
                signed / 8388608f
            }
            bits == 32 -> intLE(bytes, offset) / 2147483648f
            else -> 0f
        }.coerceIn(-1f, 1f)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int) =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun shortLE(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun intLE(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
}
