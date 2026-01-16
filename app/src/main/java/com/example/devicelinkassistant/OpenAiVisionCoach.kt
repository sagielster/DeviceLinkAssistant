package com.example.devicelinkassistant

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OpenAiVisionCoach {

    data class TapTarget(val x: Double, val y: Double, val w: Double, val h: Double)
    data class Decision(
        val safety: String,
        val target: TapTarget?,
        val confidence: Double?,
        val message: String
    )

    fun analyzeForTapTarget(
        apiKey: String,
        goal: String,
        bitmap: Bitmap
    ): Decision? {
        val jpeg = bitmapToJpeg(bitmap, quality = 60)
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val instructions = """
You are a mobile UI navigation coach.
Return ONE JSON object only.

Goal: $goal

Task:
- Identify the single best NEXT TAP target on screen to progress toward the goal.
- Return a bounding box for that target in NORMALIZED coordinates (0..1) relative to the image:
  x,y,w,h where (x,y) is top-left.

Safety:
- If the screen contains password entry, 2FA codes, payment/checkout, or private messages,
  set safety="stop" and do not return a target box.

Output JSON schema:
{
  "safety": "ok" | "stop",
  "confidence": 0.0-1.0,
  "target": {"x":0.0-1.0,"y":0.0-1.0,"w":0.0-1.0,"h":0.0-1.0} | null,
  "message": "short"
}
""".trim()

        val input = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "input_text").put("text", "Find the next tap target and return JSON."))
                    .put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
                )
        )

        val req = JSONObject()
            .put("model", "gpt-4.1-mini")
            .put("instructions", instructions)
            .put("input", input)
            .put("temperature", 0.7)
            .put("store", false)
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))

        val raw = postJson("https://api.openai.com/v1/responses", apiKey, req.toString()) ?: return null
        val resp = JSONObject(raw)

        val outputText = resp.optString("output_text", "").trim()
        val jsonText = if (outputText.startsWith("{")) outputText else extractText(resp)
        if (jsonText.isBlank()) return null

        val o = try { JSONObject(jsonText) } catch (_: Throwable) { return null }

        val safety = o.optString("safety", "ok").trim().ifBlank { "ok" }
        val conf = if (o.has("confidence")) o.optDouble("confidence") else null
        val msg = o.optString("message", "").trim()

        val tgtObj = o.optJSONObject("target")
        val target = if (tgtObj != null && safety == "ok") {
            TapTarget(
                x = tgtObj.optDouble("x", 0.0),
                y = tgtObj.optDouble("y", 0.0),
                w = tgtObj.optDouble("w", 0.0),
                h = tgtObj.optDouble("h", 0.0),
            )
        } else null

        return Decision(safety = safety, target = target, confidence = conf, message = msg)
    }

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 90), bos)
        return bos.toByteArray()
    }

    private fun extractText(resp: JSONObject): String {
        val out = resp.optJSONArray("output") ?: return ""
        for (i in 0 until out.length()) {
            val item = out.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val t = part.optString("text", "").trim()
                if (t.isNotBlank()) return t
            }
        }
        return ""
    }

    private fun postJson(url: String, apiKey: String, body: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
