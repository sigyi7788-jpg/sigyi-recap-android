package com.sigyi.recap.whisper

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class WhisperModelManager(private val context: Context) {
    companion object {
        const val MODEL_FILE = "ggml-tiny.bin"
        const val MODEL_SHA256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true"
    }

    fun modelFile(): File = File(File(context.filesDir, "whisper-models"), MODEL_FILE)

    fun ensureModel(onProgress: (Int) -> Unit = {}): File {
        val target = modelFile()
        if (target.exists() && target.length() > 1_000_000L) {
            if (sha256(target).equals(MODEL_SHA256, ignoreCase = true)) {
                onProgress(100)
                return target
            }
            target.delete()
        }

        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "$MODEL_FILE.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            requestMethod = "GET"
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Whisper model download failed (${connection.responseCode})")
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 128)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 99))
                    }
                }
            }
            if (!sha256(temporary).equals(MODEL_SHA256, ignoreCase = true)) {
                temporary.delete()
                error("Whisper model checksum မကိုက်ညီပါ။ Download ပြန်လုပ်ပါ။")
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) error("Whisper model file finalize မလုပ်နိုင်ပါ။")
            onProgress(100)
            return target
        } finally {
            connection.disconnect()
            if (temporary.exists() && !target.exists()) temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 128)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
