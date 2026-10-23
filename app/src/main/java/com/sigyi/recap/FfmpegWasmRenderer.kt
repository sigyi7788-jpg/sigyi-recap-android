package com.sigyi.recap

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

class FfmpegWasmRenderer(
    private val context: Context,
    private val webView: WebView,
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onProgress(progress: Int)
        fun onComplete(outputFile: File)
        fun onError(message: String)
    }

    private var pageReady = false
    private var inputVideoFile: File? = null
    private var inputAudioFiles: List<File> = emptyList()
    private var outputStream: FileOutputStream? = null
    private var outputFile: File? = null
    private var expectedOutputChunk = 0

    init {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = false
        webView.settings.allowFileAccessFromFileURLs = false
        webView.settings.allowUniversalAccessFromFileURLs = false
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.settings.safeBrowsingEnabled = true
        webView.addJavascriptInterface(Bridge(), "SigyiNative")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url?.startsWith("file:///android_asset/") == true) {
                    webView.evaluateJavascript("window.sigyiLoadEngine && window.sigyiLoadEngine()", null)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = !request.url.toString().startsWith("file:///android_asset/")
        }
        webView.loadUrl("file:///android_asset/ffmpeg_engine.html")
    }

    fun render(
        videoUri: Uri,
        clips: List<NarrationSegment>,
        options: FfmpegRenderOptions = FfmpegRenderOptions()
    ) {
        if (!pageReady) {
            listener.onError("FFmpeg.wasm engine မပြီးသေးပါ။ ခဏစောင့်ပြီး ပြန်နှိပ်ပါ။")
            return
        }
        if (clips.isEmpty()) {
            listener.onError("Narration segment မရှိပါ။")
            return
        }

        try {
            cleanupInputFiles()
            inputVideoFile = copyVideoToCache(videoUri)
            inputAudioFiles = clips.map { it.audioFile }

            val subtitleArray = JSONArray()
            clips.forEach { clip ->
                val words = JSONArray()
                clip.words.forEach { word ->
                    words.put(
                        JSONObject()
                            .put("start_ms", clip.startMs + word.startMs)
                            .put("end_ms", clip.startMs + word.endMs)
                            .put("text", word.text)
                    )
                }
                subtitleArray.put(
                    JSONObject()
                        .put("start_ms", clip.startMs)
                        .put("end_ms", clip.endMs)
                        .put("text", clip.text)
                        .put("words", words)
                )
            }

            // Original audio is intentionally never mapped. Narration is the only output audio.
            val renderOptions = JSONObject()
                .put("include_subtitles", options.includeSubtitles)
                .put("replace_original_audio", true)

            webView.evaluateJavascript(
                "window.sigyiRender(" +
                    "${JSONObject.quote(subtitleArray.toString())}," +
                    "${JSONObject.quote(renderOptions.toString())}," +
                    "${clips.size})",
                null
            )
        } catch (error: Exception) {
            cleanupInputFiles()
            listener.onError(error.message ?: "Render data ပြင်ဆင်မရပါ။")
        }
    }

    private fun copyVideoToCache(uri: Uri): File {
        val target = File(context.cacheDir, "sigyi_input_${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(1024 * 256)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Video ကို ဖတ်မရပါ။")
        return target
    }

    private fun readChunk(file: File, offset: Long, requestedLength: Int): String {
        require(offset >= 0L) { "Invalid chunk offset" }
        require(requestedLength in 1..(1024 * 1024)) { "Invalid chunk size" }
        if (!file.exists() || offset >= file.length()) return ""
        val available = minOf(requestedLength.toLong(), file.length() - offset).toInt()
        val bytes = ByteArray(available)
        RandomAccessFile(file, "r").use { random ->
            random.seek(offset)
            random.readFully(bytes)
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun cleanupInputFiles() {
        outputStream?.close()
        outputStream = null
        inputVideoFile?.delete()
        inputVideoFile = null
        // Audio files belong to the TTS session; do not delete caller-owned files here.
        inputAudioFiles = emptyList()
    }

    fun destroy() {
        outputStream?.close()
        cleanupInputFiles()
        outputFile?.delete()
        outputFile = null
        webView.removeJavascriptInterface("SigyiNative")
        webView.stopLoading()
        webView.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onEngineReady() {
            pageReady = true
            listener.onReady()
        }

        @JavascriptInterface
        fun onProgress(value: Int) = listener.onProgress(value)

        @JavascriptInterface
        fun getVideoSize(): Long = inputVideoFile?.length() ?: 0L

        @JavascriptInterface
        fun readVideoChunk(offset: Long, length: Int): String {
            val file = inputVideoFile ?: return ""
            return readChunk(file, offset, length)
        }

        @JavascriptInterface
        fun getAudioSize(index: Int): Long = inputAudioFiles.getOrNull(index)?.length() ?: 0L

        @JavascriptInterface
        fun readAudioChunk(index: Int, offset: Long, length: Int): String {
            val file = inputAudioFiles.getOrNull(index) ?: return ""
            return readChunk(file, offset, length)
        }

        @JavascriptInterface
        fun onOutputStart(totalChunks: Int) {
            outputStream?.close()
            outputFile?.delete()
            outputFile = File(context.cacheDir, "sigyi_render_${System.currentTimeMillis()}.mp4")
            outputStream = FileOutputStream(outputFile!!)
            expectedOutputChunk = 0
        }

        @JavascriptInterface
        fun onOutputChunk(index: Int, totalChunks: Int, base64: String) {
            require(index == expectedOutputChunk) { "Output chunk order မမှန်ပါ။" }
            outputStream?.write(Base64.decode(base64, Base64.DEFAULT))
            expectedOutputChunk += 1
        }

        @JavascriptInterface
        fun onOutputComplete() {
            outputStream?.flush()
            outputStream?.close()
            outputStream = null
            val completed = outputFile ?: error("Output file မရှိပါ။")
            cleanupInputFiles()
            listener.onProgress(100)
            listener.onComplete(completed)
        }

        @JavascriptInterface
        fun onError(message: String) {
            outputStream?.close()
            outputStream = null
            outputFile?.delete()
            outputFile = null
            cleanupInputFiles()
            listener.onError(message)
        }
    }
}
