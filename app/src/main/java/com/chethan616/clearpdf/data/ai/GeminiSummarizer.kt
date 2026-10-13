package com.chethan616.clearpdf.data.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.chethan616.clearpdf.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prototype AI summary backed by the Gemini API (generateContent over plain HTTPS).
 *
 * Unlike every other feature in the app this one sends document text off the device, so it
 * only runs after the user has agreed to that ([hasConsent]) and only in builds that both hold
 * INTERNET and were built with a GEMINI_API_KEY (foss + local.properties).
 *
 * The key is compiled into BuildConfig, which is fine for local testing but extractable from a
 * shipped APK — a release build should call Gemini through a backend or Firebase AI Logic.
 */
object GeminiSummarizer {

    /** Characters of document text sent per request; keeps a long PDF inside free-tier token limits. */
    private const val MAX_INPUT_CHARS = 120_000
    private const val PREFS_NAME = "clearpdf_ai"
    private const val KEY_CONSENT = "summary_consent"

    class QuotaExceededException : IOException("Gemini free-tier quota exceeded")

    private val json = Json { ignoreUnknownKeys = true }

    fun isAvailable(context: Context): Boolean =
        BuildConfig.GEMINI_API_KEY.isNotBlank() &&
            context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_CONSENT, false)

    fun grantConsent(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONSENT, true).apply()
    }

    /** Returns true when [text] had to be cut to [MAX_INPUT_CHARS] before sending. */
    fun isTruncated(text: String) = text.length > MAX_INPUT_CHARS

    suspend fun summarize(text: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", SYSTEM_PROMPT) }
                }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", text.take(MAX_INPUT_CHARS)) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.3)
            }
        }.toString()

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${BuildConfig.GEMINI_MODEL}:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 429) throw QuotaExceededException()
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Gemini HTTP $code: ${errorMessage(err) ?: "request failed"}")
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            parseText(response) ?: throw IOException("Gemini returned no summary")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseText(response: String): String? = runCatching {
        json.parseToJsonElement(response).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun errorMessage(body: String): String? = runCatching {
        (json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private val SYSTEM_PROMPT = """
        You summarize documents for a PDF reader app.
        Write the summary in the same language as the document.
        Start with one or two sentences giving the overall gist, then list the key points as short
        bullet lines starting with "• ". Keep names, numbers, dates and amounts exactly as written.
        Use plain text only, no Markdown headings or bold. Never invent facts that are not in the text.
    """.trimIndent()
}
