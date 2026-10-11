package com.taylor.spook.core.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente da IA (Groq, API compativel com OpenAI).
 *
 * Roda dentro do processo do hub, no celular, e por isso e HTTP simples com
 * HttpURLConnection: nao vale levar o cliente de OkHttp (que so existe no
 * app Android) para o core, que tambem roda nos testes em JVM.
 *
 * A chave vem do construtor; se vier vazia, a IA responde "nao configurada"
 * em vez de derrubar a conexao.
 */
class Ia(
    private val apiKey: String,
    private val modelo: String = "openai/gpt-oss-20b",
    private val url: String = "https://api.groq.com/openai/v1/chat/completions",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val configurada: Boolean get() = apiKey.isNotBlank()

    /**
     * Mantem historico curto por sessao para a IA ter contexto, e devolve
     * o texto da resposta. Erros do Groq viram Result.failure para o
     * chamador responder com uma mensagem em vez de estourar.
     */
    fun perguntar(
        pergunta: String,
        historico: List<JsonObject>,
        sistema: String,
    ): Result<String> {
        if (!configurada) return Result.failure(IaNaoConfigurada)
        val mensagens = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", sistema)
            })
            for (h in historico) add(h)
            add(buildJsonObject {
                put("role", "user")
                put("content", pergunta)
            })
        }
        val corpo = buildJsonObject {
            put("model", modelo)
            put("messages", mensagens)
            put("temperature", 0.7)
        }.toString()

        try {
            return Result.success(extrairTexto(postar(corpo)))
        } catch (e: Exception) {
            // gpt-oss as vezes emite a tool NATIVA do Groq (nome "system" /
            // "assistant") em vez de escrever o JSON como texto, e o Groq
            // responde 400 tool_use_failed. O corpo do erro TRAZ o tool_call
            // em failed_generation: recuperamos dali em vez de repetir (uma
            // repeticao extra estoura o TPM de 8000/min e vira 429).
            val recuperado = toolCallDeErro(e.message.orEmpty())
            if (recuperado != null) return Result.success(recuperado)
            return Result.failure(e)
        }
    }

    /**
     * Extrai o tool_call de um erro `tool_use_failed` do Groq. O corpo e
     * {"error":{"failed_generation":"{\"name\":\"assistant\",\"arguments\":{\"tool_call\":{...}}}"}}.
     * Devolve no formato que `IaToolCall.extrair` entende (texto com
     * <system>{"tool_call": ...}</system>) ou null se nao der pra recuperar.
     */
    internal fun toolCallDeErro(msg: String): String? {
        val inicio = msg.indexOf('{')
        if (inicio < 0) return null
        return try {
            val raiz = json.parseToJsonElement(msg.substring(inicio)).jsonObject
            val erro = raiz["error"]?.jsonObject ?: return null
            val falha = (erro["failed_generation"] as? JsonPrimitive)?.contentOrNull ?: return null
            val ger = json.parseToJsonElement(falha).jsonObject
            when (val a = ger["arguments"]) {
                // {"arguments":{"tool_call":{...}}} (objeto)
                is JsonObject -> {
                    val tc = a["tool_call"] ?: a
                    "<system>{\"tool_call\": ${json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), tc)}}</system>"
                }
                // {"arguments":"{\"tool_call\":{...}}"} ou {"arguments":"<system>{...}</system>"}
                is JsonPrimitive -> {
                    val c = a.contentOrNull ?: return null
                    if (c.contains("tool_call")) c
                    else "<system>{\"tool_call\": $c}</system>"
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extrai fatos duraveis sobre o usuario de um punhado de mensagens (o
     * mesmo prompt do `_extrair_fatos` do Oracle). Roda fora do caminho da
     * resposta; devolve a lista de fatos ou vazia em qualquer falha.
     */
    fun extrairFatos(mensagens: List<String>): List<String> {
        if (!configurada) return emptyList()
        val texto = mensagens.joinToString("\n").trim()
        if (texto.isEmpty()) return emptyList()
        return try {
            val corpo = buildJsonObject {
                put("model", modelo)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "Extraia fatos uteis e DURADAVEIS sobre o usuario das mensagens abaixo.\n" +
                                "Interessa: nome, preferencias, habitos, projetos, ferramentas, objetivos, " +
                                "maquinas que ele usa, problemas recorrentes.\n" +
                                "Ignore novidades temporarias e fatos sem importancia. Se nao houver nada duravel, " +
                                "retorne lista vazia.\n" +
                                "Responda APENAS com JSON: lista de strings curtas em portugues.\n\n" +
                                "Mensagens:\n" + texto,
                        )
                    })
                })
                put("max_tokens", 500)
                put("temperature", 0.1)
            }
            var bruto = extrairTexto(postar(corpo.toString())).trim()
            if (bruto.startsWith("```")) {
                bruto = bruto.trim('`')
                bruto = if (bruto.contains("\n")) bruto.substringAfter("\n") else bruto
            }
            val arr = json.parseToJsonElement(bruto) as? JsonArray ?: return emptyList()
            arr.mapNotNull { (it as? JsonPrimitive)?.content }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun postar(corpo: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 30_000
        con.readTimeout = 120_000
        con.doOutput = true
        con.setRequestProperty("Authorization", "Bearer $apiKey")
        con.setRequestProperty("Content-Type", "application/json")
        con.outputStream.use { it.write(corpo.toByteArray()) }
        val codigo = con.responseCode
        val fluxo = if (codigo in 200..299) con.inputStream else con.errorStream
        val texto = fluxo?.bufferedReader()?.use { it.readText() }.orEmpty()
        con.disconnect()
        if (codigo !in 200..299) throw IllegalStateException("Groq HTTP $codigo: ${texto.take(4000)}")
        return texto
    }

    private fun extrairTexto(bruto: String): String {
        val obj = json.parseToJsonElement(bruto).jsonObject
        val escolhas = obj["choices"]?.jsonArray ?: return ""
        val primeira = escolhas.firstOrNull()?.jsonObject ?: return ""
        val msg = primeira["message"]?.jsonObject ?: return ""
        // o modelo pode devolver content como texto ou como array de blocos
        (msg["content"] as? JsonPrimitive)?.let { return it.content }
        (msg["content"] as? JsonArray)?.forEach { parte ->
            val p = parte.jsonObject
            val t = (p["text"] as? JsonPrimitive)?.content
            if (!t.isNullOrBlank()) return t
        }
        return ""
    }

    companion object {
        val IaNaoConfigurada = IllegalStateException("IA nao configurada (sem chave).")

        /** Instrucao de sistema: o PT-BR do Taylor, curto e direto. */
        const val SISTEMA_PADRAO =
            "Voce e o SPOOK, assistente de pentest do Taylor. Responda em portugues " +
                "do Brasil, de forma curta e tecnica. Contexto: o SPOOK e um conjunto de " +
                "ferramentas de pentest e automacao; o hub roda no celular Redmi e o " +
                "notebook e um Arch Linux. Se ele pedir para executar algo, explique o comando."
    }
}
