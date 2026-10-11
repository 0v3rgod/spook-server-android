package com.taylor.spook.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * HTTP simples para o app chamar a propria API local.
 * Usa Dispatchers.IO para nao bloquear a UI.
 */
object Rede {

    @Suppress("UNUSED_PARAMETER")
    suspend fun postJson(
        urlStr: String,
        headers: Map<String, String>,
        body: Map<String, Any>
    ): String = withContext(Dispatchers.IO) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val jsonObj = buildJsonObject {
            body.forEach { (k, v) ->
                when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
        val json = Json.encodeToString(JsonObject.serializer(), jsonObj)
        conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val input = if (code >= 400) conn.errorStream else conn.inputStream
        input?.bufferedReader()?.use { it.readText() } ?: "codigo $code sem corpo"
    }
}