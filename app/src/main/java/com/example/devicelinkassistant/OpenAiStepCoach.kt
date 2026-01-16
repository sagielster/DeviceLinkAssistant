package com.example.devicelinkassistant

import android.graphics.Bitmap
import android.util.Log
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Step 1:
 * Ask ChatGPT (OpenAI) "what should the user tap next" and return a short instruction.
 *
 * IMPORTANT:
 * - This is intentionally minimal and returns a short string suitable for feeding into Gemini locator.
 * - Update model name and endpoint if your project uses a different OpenAI route.
 */
class OpenAiStepCoach {

    sealed class Result {
        data class Ok(val instruction: String) : Result()
        object InsufficientQuota : Result()
        data class Error(val message: String) : Result()
    }

    fun nextTapInstruction(
        apiKey: String,
        expectedAppName: String,
        expectedAppQuery: String,
        selectedDevice: String,
        bitmap: Bitmap
    ): Result {
        val sys = "You are an on-device UI setup coach. Use the screenshot. Return ONLY a short instruction of what to tap next. No extra text."
        val user = buildString {
            append("Goal: continue smart home setup.\n")
            if (expectedAppName.isNotBlank()) append("App: ").append(expectedAppName).append("\n")
            if (expectedAppQuery.isNotBlank()) append("App query: ").append(expectedAppQuery).append("\n")
            if (selectedDevice.isNotBlank()) append("Device: ").append(selectedDevice).append("\n")
            append("Return only something like: \"Tap the + button\" or \"Tap Open\" or \"Tap Install\".\n")
        }

        val imgB64 = bitmapToJpegBase64(bitmap, quality = 70)
        val dataUrl = "data:image/jpeg;base64,$imgB64"

        val body = JSONObject().apply {
            put("model", "gpt-4o-mini") // vision-capable with chat/completions
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", sys)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    // TEXT ONLY. Gemini will do coordinate localization.
                    put("content", user)
                })
            })
            // chat/completions uses max_tokens (max_output_tokens is for Responses API)
            put("max_tokens", 80)
        }

        val resp = postJson(
            url = "https://api.openai.com/v1/chat/completions",
            bearer = apiKey,
            body = body
        ) ?: return Result.Error("network_failed")

        Log.d("OpenAiStepCoach", "raw response: ${resp.take(1200)}")

        // Handle OpenAI error payloads explicitly.
        try {
            val j = JSONObject(resp)
            if (j.has("error")) {
                val err = j.getJSONObject("error")
                val code = err.optString("code", "")
                val type = err.optString("type", "")
                val msg = err.optString("message", "unknown_error")
                if (code == "insufficient_quota" || type == "insufficient_quota") {
                    return Result.InsufficientQuota
                }
                return Result.Error("$code:$type:$msg")
            }
        } catch (_: Throwable) {
            // fall through to normal parse attempt
        }

        return try {
            val j = JSONObject(resp)
            val content = j.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            val cleaned = content.trim()
            if (cleaned.isBlank()) Result.Error("empty_instruction") else Result.Ok(cleaned)
        } catch (_: Throwable) {
            Result.Error("parse_failed")
        }
    }

    private fun postJson(url: String, bearer: String, body: JSONObject): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun bitmapToJpegBase64(bmp: Bitmap, quality: Int): String {
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 95), baos)
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
