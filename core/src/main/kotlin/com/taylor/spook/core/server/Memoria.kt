package com.taylor.spook.core.server

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Memoria da IA em arquivos, dentro do proprio celular -- o mesmo papel do
 * `MEMORIA_DIR`/`usuario-*.json`/`sessao-*.json` do relay do Oracle, sem
 * depender de Drive, rclone ou de nenhuma maquina ligada.
 *
 * Dois niveis:
 *  - historico curto por SESSAO (uma conversa/dispositivo), ate 20 mensagens;
 *  - perfil de LONGO PRAZO por USUARIO (fatos duraveis + instrucoes
 *    permanentes), compartilhado por todos os dispositivos, ate 60 fatos.
 *
 * Escrita atomica (tmp + rename), como o `_escrever_json` do Python: uma
 * gravacao interrompida nao deixa JSON pela metade.
 */
class MemoriaIa(private val dir: File, private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }) {

    data class Usuario(
        val perfil: MutableList<String> = mutableListOf(),
        var instrucoes: String = "",
    )

    init {
        runCatching { dir.mkdirs() }
    }

    private fun seguro(nome: String): String =
        nome.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .ifBlank { "default" }

    private fun arquivoSessao(sessao: String) = File(dir, "sessao-${seguro(sessao)}.json")
    private fun arquivoUsuario(usuario: String) = File(dir, "usuario-${seguro(usuario.ifBlank { "taylor" })}.json")

    // ------------------------------------------------------------- historico

    fun historico(sessao: String): MutableList<JsonObject> {
        val arq = arquivoSessao(sessao)
        if (!arq.isFile) return mutableListOf()
        return runCatching {
            val obj = json.parseToJsonElement(arq.readText()).jsonObject
            val arr = obj["historico"] as? JsonArray ?: return mutableListOf()
            arr.mapNotNull { it as? JsonObject }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun salvarHistorico(sessao: String, historico: List<JsonObject>) {
        val doc = buildJsonObject {
            put("historico", buildJsonArray { historico.forEach { add(it) } })
        }
        escrever(arquivoSessao(sessao), doc)
    }

    // --------------------------------------------------------------- usuario

    fun usuario(nome: String): Usuario {
        val arq = arquivoUsuario(nome)
        if (!arq.isFile) return Usuario()
        return runCatching {
            val obj = json.parseToJsonElement(arq.readText()).jsonObject
            val perfil = (obj["perfil"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.toMutableList() ?: mutableListOf()
            val instr = (obj["instrucoes"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            Usuario(perfil, instr)
        }.getOrDefault(Usuario())
    }

    fun salvarUsuario(nome: String, u: Usuario) {
        val doc = buildJsonObject {
            put("perfil", buildJsonArray { u.perfil.forEach { add(JsonPrimitive(it)) } })
            put("instrucoes", u.instrucoes)
            put("atualizado", java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()))
        }
        escrever(arquivoUsuario(nome), doc)
    }

    /**
     * Funde fatos novos no perfil, sem duplicar (mesma regra do Oracle: se um
     * fato contem o outro, ja conta como repetido). Retorna quantos entraram.
     */
    fun fundirPerfil(usuario: String, fatos: List<String>): Int {
        val u = usuario(usuario)
        var novos = 0
        for (f in fatos) {
            val fs = f.trim()
            if (fs.isEmpty()) continue
            val jaTem = u.perfil.any { it.lowercase().contains(fs.lowercase()) || fs.lowercase().contains(it.lowercase()) }
            if (!jaTem) { u.perfil.add(fs); novos++ }
        }
        // mantem so os ultimos 60, como o Oracle
        while (u.perfil.size > MAX_PERFIL) u.perfil.removeAt(0)
        // Salva sempre que entrou fato novo -- mesmo que o cap tenha removido
        // a mesma quantidade, o tamanho nao muda mas o conteudo sim.
        if (novos > 0) salvarUsuario(usuario, u)
        return novos
    }

    private fun escrever(arq: File, doc: JsonObject) {
        runCatching {
            arq.parentFile?.mkdirs()
            val tmp = File(arq.parentFile, arq.name + ".tmp")
            tmp.writeText(json.encodeToString(JsonObject.serializer(), doc))
            if (!tmp.renameTo(arq)) {
                arq.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        const val MAX_HISTORICO = 20
        const val MAX_PERFIL = 60
    }
}
