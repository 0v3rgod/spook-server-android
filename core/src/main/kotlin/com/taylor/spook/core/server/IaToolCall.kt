package com.taylor.spook.core.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Parser do tool_call que a IA devolve em texto. Fica fora do Hub para poder
 * ser testado sem subir um servidor nem falar com a Groq.
 *
 * Uma regex `{.*?}` quebrava com JSON aninhado (params tem objetos dentro) e
 * parava no primeiro "}"; aqui contamos chaves, respeitando strings/escapes.
 */
internal object IaToolCall {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Devolve (textoLimpo, toolCall|null). `textoLimpo` e a resposta sem o
     * bloco JSON, para o caso de o modelo ter escrito algo antes.
     */
    fun extrair(conteudo: String): Pair<String, JsonObject?> {
        var idx = conteudo.indexOf("{\"tool_call")
        if (idx == -1) idx = conteudo.indexOf("{'tool_call'")
        if (idx == -1) return conteudo to null

        val alvo = conteudo.substring(idx)
        var profundidade = 0
        var inicio = -1
        var fim = 0
        var aspas = false
        var escape = false
        for (i in alvo.indices) {
            val ch = alvo[i]
            if (aspas) {
                if (escape) escape = false
                else if (ch == '\\') escape = true
                else if (ch == '"') aspas = false
                continue
            }
            when (ch) {
                '"' -> aspas = true
                '{' -> { if (profundidade == 0) inicio = i; profundidade++ }
                '}' -> { profundidade--; if (profundidade == 0) { fim = i + 1; break } }
            }
        }
        if (inicio < 0 || fim <= inicio) return conteudo to null
        val bloco = alvo.substring(inicio, fim)
        return try {
            val dados = json.parseToJsonElement(bloco).jsonObject
            val tc = (dados["tool_call"] as? JsonObject) ?: dados
            val limpo = (conteudo.substring(0, idx) + conteudo.substring(idx + fim))
                .replace("<system>", "").replace("</system>", "").trim()
            limpo to tc
        } catch (e: Exception) {
            conteudo to null
        }
    }
}
