package com.taylor.spook.core.server

import com.taylor.spook.core.model.Mensagem
import com.taylor.spook.core.model.Relatorio
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * Midia e leitura/apagamento de relatorios no proprio hub.
 *
 * Antes esses endpoints viviam no relay do Oracle, espelhando o Google Drive.
 * Como o hub virou a fonte da verdade, a mesma funcionalidade roda aqui
 * sobre o armazenamento local do celular: sem Drive, sem rclone e sem
 * depender de nenhuma maquina ligada.
 *
 * As rotas ficam em /api/... (o relay usava /media e /relatorios sem prefixo).
 */

/** Pasta onde os relatorios Convertidos vivem no celular. */
const val DIR_RELATORIOS = "/sdcard/Download/spook_relatorios"

/** Pasta de midia (anexos de chat enviados pelo app/Termux). */
const val DIR_MIDIA = "/sdcard/Download/spook_media"

/** Extensoes que um relatorio pode ter; o app pede a base sem extensao. */
private val EXT_RELATORIO = listOf(".docx", ".pdf", ".txt", ".log", ".md")

/** Trava o nome contra path traversal: so o basename, sem separadores. */
private fun nomeSeguro(bruto: String): String =
    File(bruto.replace('\\', '/')).name.replace(" ", "_").ifBlank { "arquivo" }

/**
 * Resolve o caminho real de um relatorio. O app manda a base sem extensao
 * (o nome no Drive era assim), entao tentamos as extensoes em ordem.
 */
private fun resolverRelatorio(hub: Hub, pedido: String): Pair<Relatorio, File>? {
    val base = nomeSeguro(pedido)
    val candidatos = if (EXT_RELATORIO.any { base.endsWith(it, true) }) listOf(base)
    else EXT_RELATORIO.map { base + it }
    for (nome in candidatos) {
        val reg = hub.store.relatorios().firstOrNull { it.nome == nome } ?: continue
        val arq = File(reg.caminho)
        if (arq.isFile) return reg to arq
    }
    // Variante que existe no disco mas nao esta registrada: o .docx nasce do
    // .txt no upload mas so o .txt vai pra lista. Sem este fallback o app nao
    // consegue abrir o DOCX (o registro so conhece o nome original).
    for (nome in candidatos) {
        val arq = File(DIR_RELATORIOS, nome)
        if (arq.isFile) {
            val reg = Relatorio(
                nome = nome,
                bytes = arq.length(),
                ts = arq.lastModified() / 1000.0,
                caminho = arq.absolutePath,
                documentos = listOf(nome.substringAfterLast('.', "")),
            )
            return reg to arq
        }
    }
    // Registro existe mas o arquivo nao esta mais: devolve o primeiro
    // registro para a API responder 200 em vez de sumir com o item.
    hub.store.relatorios().firstOrNull { it.nome == base }?.let { reg ->
        return reg to File(reg.caminho)
    }
    return null
}

// -------------------------------------------------------------- relatorios

/** DELETE /api/relatorios  {"nomes":["a.docx","b.docx"]} */
internal suspend fun ApplicationCall.apagarRelatorios(hub: Hub) {
    if (!autorizado(hub)) return
    val o = corpo() ?: return erro(400, "corpo invalido")
    val nomes = (o["nomes"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.filter { it.isNotBlank() }
        ?: return erro(400, "nomes obrigatorio")

    var apagados = 0
    var falhas = 0
    for (pedido in nomes) {
        val achado = resolverRelatorio(hub, pedido)
        if (achado == null) { falhas++; continue }
        val (reg, arq) = achado
        // Apaga o arquivo de cada variante do mesmo relatorio: o app manda a
        // base, e no disco podem existir .docx + .txt + .log do mesmo scan.
        val base = reg.nome.substringBeforeLast('.', reg.nome)
        var saiu = false
        for (ext in listOf("") + EXT_RELATORIO) {
            val f = File(arq.parentFile, base + ext)
            if (f.isFile && f.delete()) saiu = true
        }
        if (hub.store.apagarRelatorio(reg.nome)) apagados++ else falhas++
        if (saiu) hub.broadcast("/", "relatorio_apagado", buildJsonObject { put("nome", reg.nome) })
    }
    respondText(
        buildJsonObject {
            put("ok", true)
            put("apagados", apagados)
            put("falhas", falhas)
        }.toString(),
        ContentType.Application.Json,
    )
}

/** GET /api/relatorios/{nome} -> bytes do arquivo. */
internal suspend fun ApplicationCall.baixarRelatorio(hub: Hub, nome: String) {
    if (!autorizado(hub)) return
    val achado = resolverRelatorio(hub, nome)
        ?: return erro(404, "relatorio nao encontrado")
    val (_, arq) = achado
    if (!arq.isFile) return erro(404, "arquivo ausente no celular")
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=${arq.name}")
    respondBytes(arq.readBytes(), arq.name.contentTypePara())
}

private fun String.contentTypePara(): ContentType = when {
    endsWith(".pdf", true) -> ContentType.Application.Pdf
    endsWith(".txt", true) || endsWith(".log", true) || endsWith(".md", true) ->
        ContentType.Text.Plain
    else -> ContentType.Application.OctetStream
}

// -------------------------------------------------------------------- media

/** GET /api/media -> {"items":[{"name":...}]} */
internal suspend fun ApplicationCall.listarMedia(hub: Hub) {
    if (!autorizado(hub)) return
    val dir = File(DIR_MIDIA)
    val itens = if (dir.isDirectory) dir.listFiles()?.filter { it.isFile }
        ?.sortedByDescending { it.lastModified() }
        ?.map { buildJsonObject { put("name", it.name); put("bytes", it.length()) } }
        ?: emptyList()
    else emptyList()
    respondText(
        buildJsonObject {
            put("items", buildJsonArray { itens.forEach { add(it) } })
        }.toString(),
        ContentType.Application.Json,
    )
}

/** GET /api/media/{nome} -> bytes. */
internal suspend fun ApplicationCall.baixarMedia(hub: Hub, pedido: String) {
    if (!autorizado(hub)) return
    val arq = File(DIR_MIDIA, nomeSeguro(pedido))
    if (!arq.isFile) return erro(404, "midia nao encontrada")
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=${arq.name}")
    respondBytes(arq.readBytes(), arq.name.contentTypePara())
}

/**
 * POST /api/upload (multipart): o Termux manda o log do scan e o app manda
 * anexos. Com `pasta=relatorios` o arquivo entra na lista de relatorios em
 * vez de virar mensagem de chat.
 */
internal suspend fun ApplicationCall.upload(hub: Hub) {
    if (!autorizado(hub)) return
    var multipart = runCatching { receiveMultipart() }.getOrNull()
        ?: return erro(400, "multipart invalido")

    var nomeArquivo = ""
    var bytes = ByteArray(0)
    val campos = HashMap<String, String>()
    multipart.forEachPart { parte ->
        when (parte) {
            is PartData.FileItem -> {
                nomeArquivo = parte.originalFileName.orEmpty()
                // Ktor 3: `provider` e um Function0<ByteReadChannel> (propriedade,
                // getProvider() no bytecode). Tem que invocar com provider().
                val canal = parte.provider()
                val resto = canal.readRemaining()
                bytes = resto.readText().toByteArray()
            }
            is PartData.FormItem -> campos[parte.name.orEmpty()] = parte.value
            else -> Unit
        }
        parte.dispose()
    }
    if (nomeArquivo.isBlank() || bytes.isEmpty()) return erro(400, "arquivo obrigatorio")

    val nome = nomeSeguro(nomeArquivo)
    val destino = if ((campos["pasta"] ?: "").lowercase() == "relatorios") DIR_RELATORIOS else DIR_MIDIA
    File(destino).mkdirs()
    val arq = File(destino, nome)
    arq.writeBytes(bytes)

    if (destino == DIR_RELATORIOS) {
        val ext = nome.substringAfterLast('.', "")
        val documentos = mutableListOf(ext)
        // O Oracle subia .docx (editavel no Drive) junto do .txt cru. Sem Drive,
        // o .txt fica no celular (o app renderiza) e o .docx e gerado aqui na
        // hora, no proprio aparelho, com a mesma paleta/fonte da janela.
        val textual = nome.endsWith(".txt", true) || nome.endsWith(".log", true) || nome.endsWith(".md", true)
        if (textual && ext != "docx") {
            val texto = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            if (!texto.isNullOrBlank()) {
                val docx = RelatorioDoc.gerarDocx(texto)
                if (docx != null) {
                    val base = nome.substringBeforeLast('.')
                    runCatching { File(destino, "$base.docx").writeBytes(docx) }
                        .onSuccess { documentos.add("docx") }
                }
            }
        }
        val reg = Relatorio(
            nome = nome,
            bytes = bytes.size.toLong(),
            ts = System.currentTimeMillis() / 1000.0,
            caminho = arq.absolutePath,
            documentos = documentos,
        )
        hub.store.registrarRelatorio(reg)
        hub.broadcast("/", "relatorio_novo", hub.relatorioJson(reg))
        respondText(
            buildJsonObject {
                put("ok", true)
                put("filename", nome)
                put("pasta", "relatorios")
                put("documentos", buildJsonArray { documentos.forEach { add(JsonPrimitive(it)) } })
            }.toString(),
            ContentType.Application.Json,
        )
        return
    }

    // Midia vira mensagem de chat, como o app espera.
    val msg = Mensagem(
        id = campos["id"]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        ts = System.currentTimeMillis() / 1000.0,
        autor = campos["autor"]?.takeIf { it.isNotBlank() } ?: "android",
        tipo = campos["tipo"]?.takeIf { it.isNotBlank() } ?: "arquivo",
        texto = campos["texto"] ?: "",
        // o arquivo viaja como extra: o app le "arquivo" do mesmo objeto.
        extra = buildJsonObject {
            put("arquivo", "media/$nome")
            put("bytes", bytes.size)
        },
    )
    hub.store.inserirMensagem(msg)
    hub.broadcast("/", "chat_nova", hub.mensagemJson(msg))
    respondText(
        buildJsonObject {
            put("ok", true)
            put("filename", nome)
            put("id", msg.id)
        }.toString(),
        ContentType.Application.Json,
    )
}
