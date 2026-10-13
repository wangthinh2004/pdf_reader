package com.chethan616.clearpdf.data.source.remote

import com.chethan616.clearpdf.BuildConfig
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.core.result.AppError
import kotlinx.coroutines.CoroutineDispatcher
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini generateContent over plain HTTPS. The key comes from BuildConfig (local.properties),
 * which is fine for testing but extractable from an APK; a release must go through a backend.
 */
@Singleton
class GeminiApi @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotBlank()

    suspend fun generate(systemPrompt: String, text: String): String = withContext(io) {
        val body = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", text) } }
                }
            }
            putJsonObject("generationConfig") { put("temperature", 0.3) }
        }.toString()

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${BuildConfig.GEMINI_MODEL}:generateContent")
        val conn = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            }
        } catch (e: IOException) {
            throw AppError.Network(e)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 429) throw AppError.QuotaExceeded()
            if (code !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw AppError.Remote("Gemini HTTP $code: ${errorMessage(error) ?: "request failed"}")
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            parseText(response) ?: throw AppError.Remote("Gemini returned no summary")
        } catch (e: IOException) {
            throw AppError.Network(e)
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
}
