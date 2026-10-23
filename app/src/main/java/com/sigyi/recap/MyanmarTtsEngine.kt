package com.sigyi.recap

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Edge TTS adapter. Requires the companion HTTPS service in edge-tts-server/. */
class MyanmarTtsEngine(
    context: Context,
    private val onReady: (Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private var previewPlayer: MediaPlayer? = null
    private val endpoint: String = BuildConfig.EDGE_TTS_BASE_URL.trim().trimEnd('/')
    private val configured: Boolean get() = endpoint.startsWith("https://") && !endpoint.contains("YOUR-EDGE-TTS-SERVICE")

    init { onReady(configured) }

    fun speak(text: String, speedPercent: Int = 100, pitchPercent: Int = 100, onFinished: (Boolean) -> Unit = {}) {
        if (!configured || text.isBlank()) { onFinished(false); return }
        worker.execute {
            val file = File(appContext.cacheDir, "edge_preview_${System.currentTimeMillis()}.wav")
            val ok = requestWav(text, speedPercent, pitchPercent, file)
            if (ok) {
                try {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            previewPlayer?.release()
                            previewPlayer = MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                setOnCompletionListener { it.release(); previewPlayer = null; file.delete() }
                                setOnErrorListener { mp, _, _ -> mp.release(); previewPlayer = null; file.delete(); true }
                                prepare(); start()
                            }
                            onFinished(true)
                        } catch (e: Exception) { file.delete(); onFinished(false) }
                    }
                } catch (_: Exception) { file.delete(); onFinished(false) }
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onFinished(false) }
            }
        }
    }

    fun synthesizeSegments(
        segments: List<RecapSegment>, outputDirectory: File, speedPercent: Int, pitchPercent: Int,
        onFinished: (List<NarrationSegment>?, String?) -> Unit
    ) {
        if (!configured) { onFinished(null, "Edge TTS service URL ကို app/build.gradle.kts ထဲမှာ HTTPS URL နဲ့ သတ်မှတ်ပါ။"); return }
        if (segments.isEmpty()) { onFinished(null, "TTS အတွက် script segment မရှိပါ။"); return }
        worker.execute {
            try {
                if (!outputDirectory.exists() && !outputDirectory.mkdirs()) error("TTS cache folder မဖန်တီးနိုင်ပါ။")
                val results = mutableListOf<NarrationSegment>()
                segments.forEachIndexed { index, segment ->
                    val output = File(outputDirectory, "edge_segment_%04d.wav".format(index))
                    if (!requestWav(segment.text, speedPercent, pitchPercent, output)) error("Edge TTS segment ${index + 1} မအောင်မြင်ပါ။ Network/service URL စစ်ပါ။")
                    val duration = wavDurationMs(output)
                    if (duration <= 0) error("Edge TTS segment ${index + 1} WAV duration မဖတ်နိုင်ပါ။")
                    val start = results.lastOrNull()?.endMs ?: 0L
                    results += NarrationSegment(segment.text, output, start, start + duration)
                }
                onFinished(results, null)
            } catch (e: Exception) {
                onFinished(null, e.message ?: "Edge TTS မအောင်မြင်ပါ။")
            }
        }
    }

    private fun requestWav(text: String, speedPercent: Int, pitchPercent: Int, output: File): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$endpoint/tts").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 120000; doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "audio/wav")
            }
            val body = JSONObject().put("text", text).put("rate", speedPercent.coerceIn(70, 130))
                .put("pitch", (pitchPercent - 100).coerceIn(-20, 20)).toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) {
                val message = try { connection.errorStream?.bufferedReader()?.readText().orEmpty() } catch (_: Exception) { "" }
                Log.e("SigyiEdgeTTS", "HTTP ${connection.responseCode}: $message")
                false
            } else {
                connection.inputStream.use { input -> FileOutputStream(output).use { out -> input.copyTo(out) } }
                output.length() > 44
            }
        } catch (e: Exception) {
            Log.e("SigyiEdgeTTS", "TTS request failed", e); output.delete(); false
        } finally { connection?.disconnect() }
    }

    private fun wavDurationMs(file: File): Long {
        if (!file.isFile || file.length() < 44) return 0L
        return try {
            val bytes = file.inputStream().use { it.readBytes() }
            fun u32(i: Int) = (bytes[i].toLong() and 255) or ((bytes[i+1].toLong() and 255) shl 8) or ((bytes[i+2].toLong() and 255) shl 16) or ((bytes[i+3].toLong() and 255) shl 24)
            fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8)
            var pos = 12; var rate = 0L; var channels = 0; var bits = 0; var data = 0L
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, StandardCharsets.US_ASCII)
                val size = u32(pos + 4).coerceAtMost((bytes.size - pos - 8).toLong()).toInt()
                when (id) { "fmt " -> if (size >= 16) { channels = u16(pos + 10); rate = u32(pos + 12); bits = u16(pos + 22) }; "data" -> { data = size.toLong(); break } }
                pos += 8 + size + (size and 1)
            }
            val bytesPerSec = rate * channels * bits / 8
            if (bytesPerSec <= 0 || data <= 0) 0L else data * 1000 / bytesPerSec
        } catch (_: Exception) { 0L }
    }

    fun shutdown() { previewPlayer?.release(); previewPlayer = null; worker.shutdownNow() }
}
