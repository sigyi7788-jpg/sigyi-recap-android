package com.sigyi.recap

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Small dependency-free Gemini Files API client.
 * The API key remains on the device and is never written to logs.
 */
class GeminiClient(
    private val contentResolver: ContentResolver,
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash"
) {
    fun createMyanmarSegments(videoUri: Uri, displayName: String): List<RecapSegment> {
        val mimeType = contentResolver.getType(videoUri) ?: "video/mp4"
        val tempVideo = File.createTempFile("sigyi_upload_", ".video")

        try {
            contentResolver.openInputStream(videoUri)?.use { input ->
                tempVideo.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Video file ကို ဖတ်မရပါ။")

            val uploaded = uploadFile(tempVideo, displayName, mimeType)
            val fileName = uploaded.getJSONObject("file").getString("name")
            val fileUri = uploaded.getJSONObject("file").getString("uri")
            val uploadedMime = uploaded.getJSONObject("file").optString("mimeType", mimeType)

            waitUntilReady(fileName)
            return generateScript(fileUri, uploadedMime)
        } finally {
            tempVideo.delete()
        }
    }

    private fun uploadFile(file: File, displayName: String, mimeType: String): JSONObject {
        val boundary = "----SIGYI-${UUID.randomUUID()}"
        val endpoint = URL(
            "https://generativelanguage.googleapis.com/upload/v1beta/files?key=" +
                encode(apiKey)
        )
        val connection = endpoint.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("X-Goog-Upload-Protocol", "multipart")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        DataOutputStream(connection.outputStream).use { output ->
            output.writeBytes("--$boundary\r\n")
            output.writeBytes("Content-Disposition: form-data; name=\"metadata\"\r\n")
            output.writeBytes("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            output.writeBytes(JSONObject().put("file", JSONObject().put("display_name", displayName)).toString())
            output.writeBytes("\r\n--$boundary\r\n")
            output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$displayName\"\r\n")
            output.writeBytes("Content-Type: $mimeType\r\n\r\n")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count)
                }
            }
            output.writeBytes("\r\n--$boundary--\r\n")
        }

        return readJson(connection, "Video upload မအောင်မြင်ပါ")
    }

    private fun waitUntilReady(fileName: String) {
        val endpoint = URL(
            "https://generativelanguage.googleapis.com/v1beta/$fileName?key=" +
                encode(apiKey)
        )

        repeat(60) {
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val file = readJson(connection, "Uploaded video status မဖတ်နိုင်ပါ")
            val state = file.getJSONObject("file").optString("state", "ACTIVE")
            when (state) {
                "ACTIVE" -> return
                "FAILED" -> error("Gemini video processing မအောင်မြင်ပါ။")
                else -> Thread.sleep(2_000)
            }
        }
        error("Gemini video processing timeout ဖြစ်သွားပါတယ်။")
    }

    private fun generateScript(fileUri: String, mimeType: String): List<RecapSegment> {
        val prompt = GeminiRecapPrompt.build()

        val body = JSONObject()
            .put(
                "contents",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "parts",
                        org.json.JSONArray()
                            .put(JSONObject().put("file_data", JSONObject().put("mime_type", mimeType).put("file_uri", fileUri)))
                            .put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0.35).put("maxOutputTokens", 8_192))

        val endpoint = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                encode(model) + ":generateContent?key=" + encode(apiKey)
        )
        val connection = endpoint.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 180_000
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        DataOutputStream(connection.outputStream).use { it.writeBytes(body.toString()) }

        val response = readJson(connection, "Gemini script မထုတ်နိုင်ပါ")
        val parts = response.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: error("Gemini response parts မရှိပါ။")
        val text = buildString {
            for (index in 0 until parts.length()) {
                append(parts.optJSONObject(index)?.optString("text").orEmpty())
            }
        }.trim().takeIf { it.isNotEmpty() } ?: error("Gemini က script အလွတ်ပြန်ပေးပါတယ်။")

        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) error("Gemini response ထဲမှာ JSON array မတွေ့ပါ။")
        val jsonText = text.substring(start, end + 1)
        val array = runCatching { org.json.JSONArray(jsonText) }
            .getOrElse { error("Gemini segment JSON မမှန်ပါ: ${it.message}") }
        val segments = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val narration = item.optString("text").trim()
                if (narration.isNotEmpty()) {
                    val sourceStart = item.optLong("source_start_ms", 0L).coerceAtLeast(0L)
                    val sourceEnd = item.optLong("source_end_ms", 0L).coerceAtLeast(sourceStart)
                    add(RecapSegment(narration, sourceStart, sourceEnd))
                }
            }
        }
        if (segments.isEmpty()) error("Gemini segment အလွတ်ပြန်ပေးပါတယ်။")
        return segments
    }

    private fun readJson(connection: HttpURLConnection, errorPrefix: String): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = BufferedReader(InputStreamReader(stream ?: error("Empty response"))).use { it.readText() }
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).toString() }.getOrDefault(text)
            error("$errorPrefix ($code): ${detail.take(500)}")
        }
        return JSONObject(text)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
