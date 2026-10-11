package com.taylor.spook.core

import com.taylor.spook.core.server.Ia
import com.taylor.spook.core.server.IaToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * gpt-oss no Groq as vezes emite a tool NATIVA em vez de texto e o Groq
 * responde 400 tool_use_failed. O corpo do erro carrega o tool_call; em vez de
 * repetir (que estoura o TPM), recuperamos dali.
 */
class IaRecuperacaoTest {

    private val ia = Ia("chave-fake")

    private val corpo = """
        Groq HTTP 400: {"error":{"message":"Tool choice is none, but model called a tool","type":"invalid_request_error","code":"tool_use_failed","failed_generation":"{\"name\": \"assistant\", \"arguments\": {\"tool_call\": {\"acao\": \"obter_info_sistema\", \"params\": {}}}}"}}
    """.trimIndent()

    @Test
    fun `recupera tool call de failed_generation`() {
        val texto = ia.toolCallDeErro(corpo)
        val (_, tc) = IaToolCall.extrair(texto.orEmpty())
        assertEquals("obter_info_sistema", tc?.get("acao")?.toString()?.trim('"'))
    }

    @Test
    fun `arguments como string tambem e aceito`() {
        val c = """Groq HTTP 400: {"error":{"failed_generation":"{\"name\":\"system\",\"arguments\":\"{\\\"acao\\\":\\\"executar_scan\\\",\\\"params\\\":{\\\"scan\\\":\\\"nmap\\\"}}\"}"}}"""
        val texto = ia.toolCallDeErro(c)
        val (_, tc) = IaToolCall.extrair(texto.orEmpty())
        assertEquals("executar_scan", tc?.get("acao")?.toString()?.trim('"'))
    }

    @Test
    fun `erro sem failed_generation devolve null`() {
        assertNull(ia.toolCallDeErro("Groq HTTP 429: {\"error\":{\"message\":\"rate limit\"}}"))
        assertNull(ia.toolCallDeErro("sem json aqui"))
    }

    /** Forma real vista: arguments e a STRING "<system>{...}</system>". */
    @Test
    fun `arguments string com tag system e recuperado`() {
        val falha = buildJsonObject {
            put("name", "system")
            put("arguments", "<system>\n{\"tool_call\": {\"acao\": \"obter_info_sistema\", \"params\": {}}}\n</system>")
        }
        val corpo = buildJsonObject {
            put("error", buildJsonObject { put("failed_generation", falha.toString()) })
        }.toString()
        val texto = ia.toolCallDeErro("Groq HTTP 400: $corpo")
        val (_, tc) = IaToolCall.extrair(texto.orEmpty())
        assertEquals("obter_info_sistema", tc?.get("acao")?.toString()?.trim('"'))
    }
}
