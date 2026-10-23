package com.sigyi.recap

import java.io.File
import com.sigyi.recap.whisper.WhisperWord

data class RecapSegment(
    val text: String,
    val sourceStartMs: Long = 0L,
    val sourceEndMs: Long = 0L
)

data class NarrationSegment(
    val text: String,
    val audioFile: File,
    val startMs: Long,
    val endMs: Long,
    val words: List<WhisperWord> = emptyList()
)
