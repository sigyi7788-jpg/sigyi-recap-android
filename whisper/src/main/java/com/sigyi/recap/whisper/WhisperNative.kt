package com.sigyi.recap.whisper

import org.json.JSONArray

class WhisperNative private constructor(private var contextPtr: Long) {
    fun transcribeWordTimestamps(audio: FloatArray, threads: Int = preferredThreads()): List<WhisperWord> {
        check(contextPtr != 0L) { "Whisper context မရှိပါ။" }
        val json = transcribeWordTimestamps(contextPtr, audio, threads, "my")
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    WhisperWord(
                        text = item.optString("text").trim(),
                        startMs = item.optLong("start_ms"),
                        endMs = item.optLong("end_ms")
                    )
                )
            }
        }
    }

    fun close() {
        if (contextPtr != 0L) {
            freeContext(contextPtr)
            contextPtr = 0L
        }
    }

    companion object {
        init { System.loadLibrary("sigyi_whisper") }

        fun open(modelFile: java.io.File): WhisperNative {
            require(modelFile.exists()) { "Whisper model မရှိပါ: ${modelFile.absolutePath}" }
            val ptr = initContext(modelFile.absolutePath)
            check(ptr != 0L) { "Whisper model load မအောင်မြင်ပါ။" }
            return WhisperNative(ptr)
        }

        @JvmStatic private external fun initContext(modelPath: String): Long
        @JvmStatic private external fun freeContext(contextPtr: Long)
        @JvmStatic private external fun transcribeWordTimestamps(
            contextPtr: Long,
            audioData: FloatArray,
            numThreads: Int,
            language: String
        ): String

        private fun preferredThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}

data class WhisperWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)
