package com.example.devicelinkassistant

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Step 2:
 * Gemini "Nano Banana" locator:
 * Given a screenshot + the instruction (from OpenAI), return precise normalized coordinates.
 *
 * This file is intentionally narrow: it only returns a bbox (x,y,w,h) in 0..1 space.
 *
 * TODO:
 * - Replace MODEL + ENDPOINT with your working Gemini route for Nano Banana.
 * - Adjust JSON parsing to match the response you configured.
 */
object GeminiNanoBanana {

    data class Box(val x: Double, val y: Double, val w: Double, val h: Double)
    data class Located(val box: Box, val matchedText: String)

    // Backoff to avoid hammering Gemini when quota/rate-limit is hit.
    @Volatile private var backoffUntilElapsedMs: Long = 0L

    fun locateTapTarget(
        apiKey: String,
        modelId: String = "gemini-2.0-flash",
        instruction: String,
        bitmap: Bitmap
    ): Located? {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed < backoffUntilElapsedMs) {
            val remainingMs = backoffUntilElapsedMs - nowElapsed
            Log.w("GeminiNanoBanana", "backoff active; skipping Gemini call for ${remainingMs}ms")
            return null
        }

        val imgB64 = bitmapToJpegBase64(bitmap, quality = 70)

        // Ask Gemini to return ONLY JSON for easy parsing.
        val prompt = """
You are a precise UI element locator for a phone screenshot.
Task: find the single tap target that best matches the instruction.

Instruction: $instruction

Rules:
0) Prefer an element whose visible label EXACTLY matches the instruction's key text (e.g., Open/Continue/Install).
   If multiple matches exist, choose the most prominent actionable button on the main flow.
0b) Ignore "Sponsored" / advertisement cards and their Install buttons unless the instruction explicitly mentions Sponsored/Ad.
1) Return ONLY a single JSON object with keys x,y,w,h (no markdown, no extra text).
2) x,y is the TOP-LEFT of the target’s bounding box; w,h are width/height.
3) All values MUST be normalized to the image size in [0,1].
4) The box MUST tightly cover the tappable element (e.g., the whole "Continue" button).
5) NEVER return all zeros. Only return {"x":0,"y":0,"w":0,"h":0,"matched_text":""} if the target does not exist anywhere on screen.

Output format (ONLY this):
{"x":0.12,"y":0.34,"w":0.56,"h":0.08,"matched_text":"Continue"}
""".trimIndent()

        val body = JSONObject().apply {
            // TODO: set your Gemini model here
            // Examples in other codebases: "gemini-1.5-pro", "gemini-2.0-flash", etc.
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", imgB64)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 120)
            })
        }

        // TODO: replace with the correct Nano Banana endpoint you are using.
        // Common pattern:
        // https://generativelanguage.googleapis.com/v1beta/models/<MODEL>:generateContent?key=...
        val safeModel = modelId.trim().ifBlank { "gemini-2.0-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent?key=$apiKey"

        val resp = postJson(url, body) ?: return null

        Log.d("GeminiNanoBanana", "raw response: ${resp.take(2000)}")

        // Handle quota/rate-limit errors explicitly so we don't spam retries.
        try {
            val j = JSONObject(resp)
            if (j.has("error")) {
                val err = j.getJSONObject("error")
                val code = err.optInt("code", -1)
                val status = err.optString("status", "")
                val msg = err.optString("message", "")

                if (code == 429 || status == "RESOURCE_EXHAUSTED") {
                    // Gemini often includes: "Please retry in 9.249343677s."
                    val retrySeconds = parseRetryAfterSeconds(msg)
                    val backoffMs = when {
                        retrySeconds != null -> (retrySeconds * 1000.0).toLong().coerceAtLeast(2_000L)
                        else -> 30_000L
                    }
                    backoffUntilElapsedMs = SystemClock.elapsedRealtime() + backoffMs
                    Log.w("GeminiNanoBanana", "Gemini quota/rate-limit hit; backing off for ${backoffMs}ms")
                    return null
                }
            }
        } catch (_: Throwable) {
            // ignore and fall through
        }

        // Gemini wraps the model text in:
        // candidates[0].content.parts[0].text = "{\"x\":...}"
        val inner = extractModelText(resp) ?: return null
        val jsonText = extractFirstJsonObject(inner) ?: return null
        val parsed = try {
            val j = JSONObject(jsonText)
            val box = Box(
                x = j.getDouble("x"),
                y = j.getDouble("y"),
                w = j.getDouble("w"),
                h = j.getDouble("h")
            )
            val matched = j.optString("matched_text", "").trim()
            Located(box = box, matchedText = matched)
        } catch (_: Throwable) {
            null
        } ?: return null

        // Treat all-zeros as "no target"
        val b = parsed.box
        if (b.x == 0.0 && b.y == 0.0 && b.w == 0.0 && b.h == 0.0) return null
        // If Gemini didn't provide matched_text, keep it but let caller decide.
        // (We still prefer it to be present so we can validate correctness.)
        return parsed
    }

    private fun extractModelText(resp: String): String? {
        return try {
            val j = JSONObject(resp)
            val candidates = j.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null
            parts.getJSONObject(0).optString("text", null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun postJson(url: String, body: JSONObject): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
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
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseRetryAfterSeconds(message: String): Double? {
        // Looks for "Please retry in 9.249343677s."
        val needle = "Please retry in "
        val i = message.indexOf(needle)
        if (i < 0) return null
        val start = i + needle.length
        val end = message.indexOf('s', start)
        if (end <= start) return null
        val num = message.substring(start, end).trim()
        return num.toDoubleOrNull()
    }

    /**
     * Gemini responses often wrap model output inside fields.
     * This tries to find the first {...} block in the raw response string.
     * If you prefer, replace this with exact field parsing for your response schema.
     */
    private fun extractFirstJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until s.length) {
            val c = s[i]
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return s.substring(start, i + 1)
            }
        }
        return null
    }
}
