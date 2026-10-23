package com.sigyi.recap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileOutputStream
import com.sigyi.recap.whisper.WhisperWordAligner
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var videoName: TextView
    private lateinit var videoMeta: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var scriptInput: EditText
    private lateinit var scriptStatus: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var analyzeButton: Button
    private lateinit var previewVoiceButton: Button
    private lateinit var renderButton: Button
    private lateinit var speedSeekBar: SeekBar
    private lateinit var pitchSeekBar: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var pitchValue: TextView
    private lateinit var subtitleCheck: CheckBox
    private lateinit var replaceAudioCheck: CheckBox
    private lateinit var ttsEngine: MyanmarTtsEngine
    private lateinit var wasmRenderer: FfmpegWasmRenderer
    private var whisperAligner: WhisperWordAligner? = null
    private var recapSegments: List<RecapSegment> = emptyList()

    private var selectedVideo: Uri? = null
    private val background = Executors.newSingleThreadExecutor()
    private val securePreferences by lazy {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this,
            "sigyi_secure_preferences",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult

            selectedVideo = uri
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // A provider may not support persistable permissions.
            }

            videoName.text = queryDisplayName(uri) ?: "Selected video"
            videoMeta.text = "Video ready • AI analysis လုပ်နိုင်ပါပြီ"
            analyzeButton.isEnabled = true
            renderButton.isEnabled = false
            statusText.text = "Video ready — Gemini နဲ့ script စရေးနိုင်ပါပြီ။"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        restoreSavedApiKey()
        ttsEngine = MyanmarTtsEngine(this) { ready ->
            runOnUiThread {
                statusText.text = if (ready) {
                    "Myanmar TTS ready — narration preview လုပ်နိုင်ပါပြီ။"
                } else {
                    "ဒီဖုန်းမှာ Myanmar TTS voice မရှိသေးပါ။"
                }
            }
        }
        wasmRenderer = FfmpegWasmRenderer(
            this,
            findViewById<WebView>(R.id.wasmWebView),
            object : FfmpegWasmRenderer.Listener {
                override fun onReady() = runOnUiThread {
                    statusText.text = "FFmpeg.wasm ready — render လုပ်နိုင်ပါပြီ။"
                }

                override fun onProgress(progress: Int) = runOnUiThread {
                    progressBar.progress = progress
                    statusText.text = "FFmpeg.wasm render လုပ်နေပါတယ်… $progress%"
                }

                override fun onComplete(outputFile: File) = runOnUiThread {
                    saveRenderedVideo(outputFile)
                }

                override fun onError(message: String) = runOnUiThread {
                    setBusy(false, "Render မအောင်မြင်ပါ: $message")
                    showMessage(message)
                }
            }
        )
        setupActions()
    }

    private fun bindViews() {
        videoName = findViewById(R.id.videoName)
        videoMeta = findViewById(R.id.videoMeta)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        scriptInput = findViewById(R.id.scriptInput)
        scriptStatus = findViewById(R.id.scriptStatus)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        analyzeButton = findViewById(R.id.analyzeButton)
        previewVoiceButton = findViewById(R.id.previewVoiceButton)
        renderButton = findViewById(R.id.renderButton)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        pitchSeekBar = findViewById(R.id.pitchSeekBar)
        speedValue = findViewById(R.id.speedValue)
        pitchValue = findViewById(R.id.pitchValue)
        subtitleCheck = findViewById(R.id.subtitleCheck)
        replaceAudioCheck = findViewById(R.id.replaceAudioCheck)
    }

    private fun setupActions() {
        findViewById<Button>(R.id.chooseVideoButton).setOnClickListener {
            videoPicker.launch(arrayOf("video/*"))
        }

        analyzeButton.isEnabled = false
        renderButton.isEnabled = false

        analyzeButton.setOnClickListener {
            startAiScriptDraft()
        }

        previewVoiceButton.setOnClickListener {
            val script = scriptInput.text.toString().trim()
            if (script.isEmpty()) {
                showMessage("အရင်ဆုံး script ရေးပါ။")
            } else {
                ttsEngine.speak(
                    script,
                    speedPercent = 50 + speedSeekBar.progress,
                    pitchPercent = 50 + pitchSeekBar.progress
                ) { ok ->
                    if (!ok) showMessage("Myanmar TTS voice မရပါ။")
                }
            }
        }

        renderButton.setOnClickListener {
            startRenderDraft()
        }

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                speedValue.text = "Voice speed ${50 + value}%"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        pitchSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                pitchValue.text = "Voice pitch ${50 + value}%"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun restoreSavedApiKey() {
        val savedKey = securePreferences
            .getString(KEY_GEMINI_API, "")
            .orEmpty()
        apiKeyInput.setText(savedKey)
    }

    private fun saveApiKey(key: String) {
        securePreferences
            .edit()
            .putString(KEY_GEMINI_API, key)
            .apply()
    }

    private fun startAiScriptDraft() {
        val video = selectedVideo
        if (video == null) {
            showMessage("အရင်ဆုံး video ရွေးပါ။")
            return
        }

        val apiKey = apiKeyInput.text.toString().trim()
        if (apiKey.isEmpty()) {
            apiKeyInput.error = "Gemini API key ထည့်ပါ"
            apiKeyInput.requestFocus()
            showMessage("Gemini API key မရှိသေးပါ။")
            return
        }

        saveApiKey(apiKey)
        setBusy(true, "Gemini analysis အတွက် ပြင်ဆင်နေပါတယ်…")

        val displayName = videoName.text.toString().ifBlank { "sigyi_video.mp4" }
        background.execute {
            try {
                val segments = GeminiClient(
                    contentResolver = contentResolver,
                    apiKey = apiKey,
                    model = "gemini-2.5-flash"
                ).createMyanmarSegments(video, displayName)

                runOnUiThread {
                    recapSegments = segments
                    scriptInput.setText(segments.joinToString("\n") { it.text })
                    scriptStatus.text = "${segments.size} segments ready • စာသားကို ပြင်ပြီး Render လုပ်နိုင်ပါတယ်။"
                    renderButton.isEnabled = true
                    setBusy(false, "Gemini script အောင်မြင်ပါပြီ — narration settings ကို စစ်ပါ။")
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setBusy(false, "AI analysis မအောင်မြင်ပါ: ${error.message ?: "Unknown error"}")
                    showMessage("Gemini error: ${error.message ?: "Unknown error"}")
                }
            }
        }
    }

    private fun startRenderDraft() {
        val video = selectedVideo
        if (scriptInput.text.toString().trim().isEmpty()) {
            showMessage("Script မရှိသေးပါ။")
            return
        }
        if (video == null) {
            showMessage("Video မရှိသေးပါ။")
            return
        }

        val editedScript = scriptInput.text.toString().trim()
        val originalScript = recapSegments.joinToString("\n") { it.text }.trim()
        val segments = if (recapSegments.isNotEmpty() && editedScript == originalScript) {
            recapSegments
        } else {
            listOf(RecapSegment(editedScript))
        }

        setBusy(true, "${segments.size} narration segments ထုတ်နေပါတယ်…")
        val narrationDirectory = File(cacheDir, "sigyi_tts_${System.currentTimeMillis()}")
        val speed = 50 + speedSeekBar.progress
        val pitch = 50 + pitchSeekBar.progress
        ttsEngine.synthesizeSegments(segments, narrationDirectory, speed, pitch) { clips, error ->
            runOnUiThread {
                if (clips == null) {
                    setBusy(false, error ?: "TTS audio မထုတ်နိုင်ပါ။")
                    showMessage(error ?: "Myanmar TTS audio မထုတ်နိုင်ပါ။")
                    return@runOnUiThread
                }
                statusText.text = "Whisper tiny model ဖြင့် exact word timing တွက်နေပါတယ်…"
                background.execute {
                    try {
                        val aligner = whisperAligner ?: WhisperWordAligner(this@MainActivity).also {
                            whisperAligner = it
                        }
                        val aligned = clips.map { clip ->
                            val words = aligner.align(clip.audioFile) { progress ->
                                runOnUiThread {
                                    statusText.text = "Whisper model download လုပ်နေပါတယ်… $progress%"
                                }
                            }
                            clip.copy(words = words)
                        }
                        runOnUiThread {
                            statusText.text = "Exact word timing ပြီးပါပြီ — FFmpeg.wasm render စနေပါတယ်…"
                            wasmRenderer.render(
                                video,
                                aligned,
                                FfmpegRenderOptions(
                                    includeSubtitles = subtitleCheck.isChecked,
                                    replaceOriginalAudio = true
                                )
                            )
                            aligned.forEach { it.audioFile.deleteOnExit() }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            setBusy(false, "Whisper alignment မအောင်မြင်ပါ: ${error.message}")
                            showMessage("Whisper error: ${error.message ?: "Unknown error"}")
                        }
                    }
                }
            }
        }
    }

    private fun saveRenderedVideo(outputFile: File) {
        try {
            val name = "SIGYI_${System.currentTimeMillis()}.mp4"
            val values = android.content.ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SIGYI")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Gallery output မဖန်တီးနိုင်ပါ။")
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    outputFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: error("Gallery output stream မဖွင့်နိုင်ပါ။")
                contentResolver.update(
                    uri,
                    android.content.ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                setBusy(false, "အောင်မြင်ပါပြီ — Movies/SIGYI ထဲမှာ သိမ်းထားပါတယ်။")
                outputFile.delete()
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                outputFile.delete()
                throw error
            }
        } catch (error: Exception) {
            setBusy(false, "Output save မအောင်မြင်ပါ: ${error.message}")
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        analyzeButton.isEnabled = !busy && selectedVideo != null
        previewVoiceButton.isEnabled = !busy
        renderButton.isEnabled = !busy && scriptInput.text.toString().trim().isNotEmpty()
        statusText.text = message
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment
    }

    private fun showMessage(message: String) {
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        background.shutdownNow()
        if (::ttsEngine.isInitialized) ttsEngine.shutdown()
        if (::wasmRenderer.isInitialized) wasmRenderer.destroy()
        whisperAligner?.close()
        super.onDestroy()
    }

    companion object {
        private const val KEY_GEMINI_API = "gemini_api_key"
    }
}
