package com.taylor.spook.core

import com.taylor.spook.core.server.IaToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * O parser do tool_call e a parte que mais erra: o modelo pode envolver o JSON
 * em <system>, aninhar objetos em params ou escrever texto antes. A regex
 * `{.*?}` antiga parava no primeiro "}" e quebrava com params aninhados.
 */
class IaToolCallTest {

    private fun acao(tc: JsonObject?) = tc?.get("acao")?.jsonPrimitive?.content

    @Test
    fun `texto puro nao tem tool call`() {
        val (txt, tc) = IaToolCall.extrair("Boa noite, Taylor!")
        assertNull(tc)
        assertEquals("Boa noite, Taylor!", txt)
    }

    @Test
    fun `extrai tool call simples envolvido em system`() {
        val bruto = "<system>{\"tool_call\": {\"acao\": \"executar_scan\", " +
            "\"params\": {\"scan\": \"nmap\", \"alvo\": \"1.2.3.4\"}}}</system>"
        val (txt, tc) = IaToolCall.extrair(bruto)
        assertEquals("", txt)
        assertEquals("executar_scan", acao(tc))
        assertEquals("nmap", tc?.get("params")?.let { (it as JsonObject)["scan"]?.jsonPrimitive?.content })
    }

    /** Regressao: params aninhados faziam a regex antiga parar cedo. */
    @Test
    fun `params aninhados sao capturados inteiros`() {
        val bruto = "{\"tool_call\": {\"acao\": \"executar_comando_notebook\", " +
            "\"params\": {\"comando\": \"ls\", \"opts\": {\"a\": 1, \"b\": [1, 2]}}}}"
        val (_, tc) = IaToolCall.extrair(bruto)
        assertEquals("executar_comando_notebook", acao(tc))
        val params = tc!!["params"] as JsonObject
        assertNotNull(params["opts"])
    }

    @Test
    fun `texto antes do tool call e preservado no limpo`() {
        val bruto = "Vou rodar o scan.\n{\"tool_call\": {\"acao\": \"obter_info_sistema\", \"params\": {}}}"
        val (txt, tc) = IaToolCall.extrair(bruto)
        assertEquals("Vou rodar o scan.", txt)
        assertEquals("obter_info_sistema", acao(tc))
    }

    @Test
    fun `chaves dentro de strings nao confundem o contador`() {
        val bruto = "{\"tool_call\": {\"acao\": \"executar_comando_notebook\", " +
            "\"params\": {\"comando\": \"echo }{ oi\"}}}"
        val (_, tc) = IaToolCall.extrair(bruto)
        assertEquals("executar_comando_notebook", acao(tc))
    }

    @Test
    fun `json quebrado devolve null sem estourar`() {
        val (txt, tc) = IaToolCall.extrair("{\"tool_call\": {\"acao\": ")
        assertNull(tc)
        assertEquals("{\"tool_call\": {\"acao\": ", txt)
    }
}
