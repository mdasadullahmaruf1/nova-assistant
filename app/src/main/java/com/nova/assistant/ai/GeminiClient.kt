package com.nova.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    private val conversationHistory = mutableListOf<JSONObject>()
    val memories = mutableListOf<String>()

    fun clearHistory() { conversationHistory.clear() }

    private fun buildSystemPrompt(): String {
        val memSection = if (memories.isNotEmpty())
            "\n\nWhat you remember about this user:\n${
                memories.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
            }" else ""

        return """You are NOVA, an AI assistant that controls an Android phone via voice commands.
Always end your response with a JSON block:
```action

{"type":"ACTION_TYPE","params":{}}
{"type":"SEQUENCE","params":{"steps":[{"type":"OPEN_APP","params":{"app":"YouTube"}},{"type":"TAP_TEXT","params":{"text":"Search"}},{"type":"TYPE_TEXT","params":{"text":"Believer"}},{"type":"PRESS_KEY","params":{"key":"SEARCH"}}]}}
```$memSection"""
    }

    suspend fun sendMessage(userText: String): NovaResponse = withContext(Dispatchers.IO) {
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", userText) }) })
        })

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", buildSystemPrompt()) }) })
            })
            put("contents", JSONArray(conversationHistory))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 800)
            })
        }

        val request = Request.Builder()
            .url("$API_URL?key=${ApiConfig.GEMINI_API_KEY}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val responseStr = client.newCall(request).execute().use { it.body?.string() ?: "" }
        val responseJson = JSONObject(responseStr)

        val candidates = responseJson.optJSONArray("candidates")
        val fullText = if (candidates != null && candidates.length() > 0) {
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            (0 until (parts?.length() ?: 0)).joinToString("") {
                parts!!.getJSONObject(it).optString("text", "")
            }
        } else throw Exception(responseJson.optJSONObject("error")?.optString("message") ?: "No response")

        conversationHistory.add(JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", fullText) }) })
        })

        if (conversationHistory.size > 20) {
            val trimmed = conversationHistory.takeLast(20)
            conversationHistory.clear()
            conversationHistory.addAll(trimmed)
        }

        parseResponse(fullText)
    }

    private fun parseResponse(raw: String): NovaResponse {
        val regex = Regex("```action\\s*([\\s\\S]*?)```")
        val match = regex.find(raw)
        val displayText = raw.replace(regex, "").trim()
        return if (match != null) {
            try {
                val actionJson = JSONObject(match.groupValues[1].trim())
                if (actionJson.getString("type") == "REMEMBER") {
                    val fact = actionJson.optJSONObject("params")?.optString("fact") ?: ""
                    if (fact.isNotEmpty() && !memories.contains(fact)) memories.add(fact)
                }
                NovaResponse(text = displayText.ifEmpty { "Done!" }, action = actionJson)
            } catch (e: Exception) {
                NovaResponse(text = displayText.ifEmpty { raw }, action = null)
            }
        } else NovaResponse(text = displayText.ifEmpty { raw }, action = null)
    }
}

data class NovaResponse(val text: String, val action: JSONObject?)
