package com.taylor.spook.core.server

import com.taylor.spook.core.model.Mensagem
import com.taylor.spook.core.model.Relatorio
import com.taylor.spook.core.model.Task
import com.taylor.spook.core.protocol.SocketIoPacket
import com.taylor.spook.core.protocol.SocketIoType
import com.taylor.spook.core.store.Store
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Erro de autenticacao: o socket recebe CONNECT_ERROR e nao entra no namespace. */
class AuthException(message: String) : RuntimeException(message)

/** Usuario padrao da memoria da IA (o relay do Oracle usava o mesmo). */
private const val USUARIO_PADRAO = "taylor"

/** A cada quantas perguntas do usuario a IA extrai fatos duraveis. */
private const val N_PERGUNTAS_EXTRACAO = 3

/**
 * O "cerebro" do SPOOK SERVER. Independente de transporte: recebe
 * SocketIoPacket, devolve SocketIoPacket. O mesmo Hub roda no celular e
 * nos testes, sem Ktor nem Android.
 *
 * Os nomes dos eventos sao o contrato do app (ver ConexaoNotebook.kt /
 * ConexaoChat.kt / ConexaoIa.kt). Mudar aqui = quebrar o app.
 */
class Hub(
    val store: Store,
    private val tokenValido: (String) -> Boolean,
    private val agora: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val infoSistema: () -> JsonObject = { buildJsonObject {} },
    private val catalogoComandos: () -> JsonObject = { buildJsonObject {} },
    private val verificarSenha: ((String) -> String?)? = null,
    private val definirSenha: ((String) -> Unit)? = null,
    /** IA opcional: se null, o namespace /ia responde que nao ha chave. */
    private val ia: Ia? = null,
    /**
     * Memoria persistente da IA (perfil de longo prazo + historico por sessao).
     * O app passa uma apontando para filesDir; nos testes fica null e o Hub
     * usa um historico em memoria, sem I/O.
     */
    private val memoria: MemoriaIa? = null,
) {

    /** Fallback quando nao ha memoria persistente (testes/JVM sem arquivo). */
    private val historicoMemoria = java.util.concurrent.ConcurrentHashMap<String, MutableList<JsonObject>>()

    /**
     * Acoes com tool_call em andamento: sessao_id -> pergunta/usuario. O
     * resultado chega depois (pela bridge do Arch) e a conversa continua ali.
     */
    private val perguntasPendentes = java.util.concurrent.ConcurrentHashMap<String, Pendente>()

    private data class Pendente(val pergunta: String, val usuario: String)

    /** Contador de perguntas por usuario, para extrair fatos a cada N. */
    private val contadorExtracao = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Catalogo de comandos do Arch (recebido via evento info_arch).
     * Mesclado com o catalogo do Android quando o app pede listar_comandos.
     */
    @Volatile
    private var catalogoArch: JsonObject = buildJsonObject {}

    fun atualizarCatalogoArch(novo: JsonObject) {
        catalogoArch = novo
    }

    val sessoes = java.util.Collections.synchronizedList(mutableListOf<Session>())

    /**
     * O Arch publica as metricas dele como cliente do hub (ver `spook-agente`
     * / `servidor.py`). Guardamos o ultimo valor para responder a quem
     * acabou de conectar, porque antes disso nao existe nenhum pacote
     * `metricas_notebook` em transito e a tela ficaria com RAM 0% / CPU 0.0%.
     */
    @Volatile
    private var metricasArch: JsonObject? = null

    @Volatile
    private var infoArch: JsonObject? = null

    /** Session ID do Arch (o cliente que envia info_arch). */
    @Volatile
    private var archSessionId: String? = null

    /** Ultimas metricas conhecidas do Arch, ou null se ele nunca.publicou nada. */
    fun metricasConhecidas(): JsonObject? = metricasArch

    /** Ultima info de sistema publicada pelo Arch, ou null. */
    fun infoArchConhecida(): JsonObject? = infoArch

    /** O REST do HubServer valida por aqui em vez de duplicar o token. */
    fun tokenOk(t: String?): Boolean = !t.isNullOrBlank() && tokenValido(t)

    /** Verifica senha do painel web: se ok, devolve o token de pareamento. */
    fun verificarSenhaWeb(senha: String): String? = verificarSenha?.invoke(senha)

    /** Define/altera senha do painel web. */
    fun definirSenhaWeb(senha: String) = definirSenha?.invoke(senha)

    /** /api/info: o mesmo info_sistema, mais as contagens do banco. */
    fun resumoSistema(): JsonObject = buildJsonObject {
        for ((k, v) in infoSistema()) put(k, v)
        put("sessoes", sessoes.size)
        put("mensagens", store.mensagens().size)
        put("tasks", store.tasks().size)
        put("relatorios", store.relatorios().size)
        infoArch?.let { arch ->
            put("arch", arch)
        }
    }

    // ------------------------------------------------------------- entrada

    fun entrada(session: Session, packet: SocketIoPacket) {
        when (packet.type) {
            SocketIoType.CONNECT -> conectar(session, packet)
            SocketIoType.EVENT -> {
                if (!session.isAuthenticated(packet.namespace)) {
                    session.send(
                        SocketIoPacket.connectError(packet.namespace, "authentication required").toEngineIo()
                    )
                    return
                }
                evento(session, packet)
            }
            SocketIoType.DISCONNECT -> {
                if (session.isAuthenticated(packet.namespace)) {
                    session.disconnectNamespace(packet.namespace)
                }
            }
            else -> Unit
        }
    }

    private fun conectar(session: Session, packet: SocketIoPacket) {
        val auth = packet.payload as? JsonObject
        val tok = (auth?.get("token") as? JsonPrimitive)?.content

        if (tok.isNullOrBlank() || !tokenValido(tok)) {
            session.send(SocketIoPacket.connectError(packet.namespace, "invalid token").toEngineIo())
            return
        }
        session.authenticate(tok)
        session.socketSid(packet.namespace)
        session.send(SocketIoPacket.connectAck(packet.namespace, session.socketSid(packet.namespace)).toEngineIo())

        // Reenvia o estado do Arch para quem acabou de entrar: sem isso a tela
        // ficaria zerada (RAM 0%, CPU 0.0%) ate o proximo pacote de metricas.
        // So no namespace padrao: em /ia (e em qualquer namespace nova) o
        // cliente ainda esta esperando o ack, e um EVENT extra no mesmo
        // payload faz o socket.io cliente abortar a conexao do namespace.
        if (packet.namespace == "/") {
            // Presenca do celular: o hub roda DENTRO do celular, entao quem
            // consegue conectar ja prova que ele esta online. Mantem o
            // contrato do Oracle (o overlay espera celular_presenca).
            session.emit(packet.namespace, "celular_presenca", buildJsonObject { put("online", true) })
            metricasArch?.let { session.emit(packet.namespace, "metricas_notebook", it) }
            infoArch?.let { session.emit(packet.namespace, "info_sistema", it) }
        }
    }

    private fun evento(session: Session, packet: SocketIoPacket) {
        val nome = packet.eventName ?: return
        val args = packet.eventArgs
        val ns = packet.namespace

        when (nome) {
            // ------------------------------------------------------------ chat
            "chat_listar" -> listaChat(session)
            "chat_enviar" -> {
                val o = args as? JsonObject ?: return
                val corpo = texto(o, "texto") ?: return
                val m = Mensagem(
                    id = texto(o, "id") ?: UUID.randomUUID().toString(),
                    texto = corpo,
                    autor = texto(o, "autor"),
                    tipo = texto(o, "tipo") ?: "texto",
                    ts = agora(),
                )
                store.inserirMensagem(m)
                // eco para quem mandou + broadcast para os outros
                session.emit("/", "chat_nova", mensagemJson(m))
                broadcast(ns, "chat_nova", mensagemJson(m), exceto = session)
            }

            "chat_apagar", "chat_apagada" -> {
                val id = texto(args as? JsonObject, "id") ?: return
                if (store.apagarMensagem(id)) {
                    val aviso = buildJsonObject { put("id", id) }
                    session.emit(ns, "chat_apagada", aviso)
                    broadcast(ns, "chat_apagada", aviso, exceto = session)
                }
            }

            // ----------------------------------------------------------- tasks
            //
            // O ecosystem PentestLink nao fala uma lingua so, e cada lado
            // discorda do outro:
            //   app  (ConexaoChat.kt) emite task_nova / task_apagada
            //   py   (servidor.py)   escuta task_criar / task_apagar
            // Aceitamos os dois nomes e os dois campos (titulo/texto) em vez de
            // escolher um e quebrar metade dos clientes.
            "task_listar" -> listaTasks(session)

            "task_nova", "task_criar" -> {
                val o = args as? JsonObject ?: return
                val titulo = texto(o, "titulo") ?: texto(o, "texto") ?: return
                if (titulo.isBlank()) return
                val t = Task(
                    id = texto(o, "id") ?: UUID.randomUUID().toString(),
                    texto = titulo,
                    feito = o["feito"]?.let { (it as? JsonPrimitive)?.content?.toBoolean() } ?: false,
                    criado = agora(),
                )
                store.inserirTask(t)
                session.emit(ns, "task_nova", taskJson(t))
                broadcast(ns, "task_nova", taskJson(t), exceto = session)
            }

            "task_atualizada" -> {
                val o = args as? JsonObject ?: return
                val id = texto(o, "id") ?: return
                // upsert: o app marca task que ele acabou de criar em outro
                // lugar, e antes isso caia no vazio em silencio (return sem
                // responder), deixando a UI parada.
                val atual = store.task(id)
                val novo = (atual ?: Task(id = id, texto = "", criado = agora())).copy(
                    texto = texto(o, "titulo") ?: texto(o, "texto") ?: atual?.texto.orEmpty(),
                    feito = o["feito"]?.let { (it as? JsonPrimitive)?.content?.toBoolean() } ?: atual?.feito ?: false,
                )
                store.atualizarTask(novo)
                session.emit(ns, "task_atualizada", taskJson(novo))
                broadcast(ns, "task_atualizada", taskJson(novo), exceto = session)
            }

            "task_apagada", "task_apagar" -> {
                val id = texto(args as? JsonObject, "id") ?: return
                if (store.apagarTask(id)) {
                    val aviso = buildJsonObject { put("id", id) }
                    session.emit(ns, "task_apagada", aviso)
                    broadcast(ns, "task_apagada", aviso, exceto = session)
                }
            }

            // -------------------------------------------------------- sistema
            // O Arch se anuncia como cliente; guardamos para responder a quem conecta depois.
            "metricas_notebook" -> {
                val m = (args as? JsonObject) ?: return
                metricasArch = m
                broadcast(ns, "metricas_notebook", m, exceto = session)
            }
            "info_arch" -> {
                val m = (args as? JsonObject) ?: return
                infoArch = m
                archSessionId = session.engineSid
                // Extrai catalogo do Arch se vier junto
                (m["catalogo"] as? JsonObject)?.let { atualizarCatalogoArch(it) }
                broadcast(ns, "info_sistema", m, exceto = session)
            }
            // O app pede a info de quem manda no notebook (Arch). Sem dado do Arch,
            // cai na info do proprio host (o hub no celular) em vez de mandar vazio.
            "info_sistema" -> session.emit(ns, "info_sistema", infoArch ?: infoSistema())
            "listar_comandos" -> {
                val android = catalogoComandos()
                val merged = buildJsonObject {
                    // Mescla: comandos do Arch sobrescrevem ou adicionam categorias
                    for ((k, v) in android) put(k, v)
                    for ((k, v) in catalogoArch) put(k, v)
                }
                session.emit(ns, "lista_comandos", buildJsonObject { put("comandos", merged) })
            }
            "relatorio_listar" -> listaRelatorios(session)
            "bateria_celular" -> broadcast(ns, "bateria_celular", (args as? JsonObject) ?: buildJsonObject {}, exceto = session)
            "clipboard_celular" -> Unit
            "gps_celular" -> broadcast(ns, "gps_celular", (args as? JsonObject) ?: buildJsonObject {}, exceto = session)
            "notificacao_celular" -> broadcast(ns, "notificacao_nova", (args as? JsonObject) ?: buildJsonObject {})
            "tocar_celular" -> broadcast(ns, "tocar_celular", (args as? JsonObject) ?: buildJsonObject {})

            // -------------------------------------------------------- comandos do Arch
            // O app manda executar_comando {nome}; encaminha pro Arch (session que enviou info_arch).
            "executar_comando" -> {
                val o = args as? JsonObject ?: return
                val nome = texto(o, "nome").orEmpty()
                if (nome.isBlank()) return
                archSessionId?.let { sid ->
                    sessoes.find { it.engineSid == sid }?.emit(ns, "executar_comando", buildJsonObject { put("nome", nome) })
                }
            }

            // ------------------------------------------------------- IA (/ia)
            // O app conecta no namespace /ia e manda chat_ia_perguntar; a
            // resposta volta em chat_ia_resposta. Sem chave configurada
            // responde ok=false em vez de derrubar a conexao.
            //
            // Igual ao relay do Oracle: a IA tem memoria (perfil de longo prazo
            // + historico por sessao) e pode emitir um tool_call para executar
            // scan/comando/info. Nesse caso o Hub encaminha a acao para a
            // bridge do Arch e a resposta final chega depois (continuarAcao).
            "chat_ia_perguntar" -> chatIaPerguntar(session, ns, args as? JsonObject)

            // A bridge do Arch responde a uma acao; o MESMO nome de evento vai
            // e volta (idêntico ao Oracle), distinguido pela presença de
            // "resultado". Sem ele, é o eco de uma emissão nossa -> ignora.
            "ia_executar_scan", "ia_executar_comando", "ia_obter_info" -> {
                val o = args as? JsonObject ?: return
                val sid = texto(o, "sessao_id")
                val resultado = o["resultado"] as? JsonObject
                if (!sid.isNullOrBlank() && resultado != null && resultado.isNotEmpty()) {
                    continuarAcao(sid, resultado, texto(o, "pergunta_original").orEmpty())
                }
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------------- IA

    private fun chatIaPerguntar(session: Session, ns: String, o: JsonObject?) {
        if (o == null) return
        val pergunta = texto(o, "pergunta").orEmpty().trim()
        val sessao = texto(o, "sessao_id").orEmpty().trim().ifBlank { session.engineSid }
        val usuario = texto(o, "usuario").orEmpty().trim().ifBlank { USUARIO_PADRAO }

        val resp: JsonObject? = when {
            ia?.configurada != true -> buildJsonObject {
                put("ok", false)
                put("resposta", "IA nao configurada no hub (sem chave Groq).")
            }
            pergunta.isBlank() -> buildJsonObject {
                put("ok", false)
                put("resposta", "Envie uma pergunta.")
            }
            pergunta.length > 4000 -> buildJsonObject {
                put("ok", false)
                put("resposta", "Pergunta muito longa (maximo 4000 caracteres).")
            }
            else -> {
                val hist = historicoDe(sessao)
                ia.perguntar(pergunta, hist.toList(), sistemaIa(usuario)).fold(
                    onSuccess = { bruto ->
                        val (conteudo, toolCall) = IaToolCall.extrair(bruto)
                        if (toolCall != null) {
                            // Nao responde ainda: a resposta final vem quando a
                            // acao terminar (continuarAcao), como no Oracle.
                            perguntasPendentes[sessao] = Pendente(pergunta, usuario)
                            val r = executarToolCall(toolCall, sessao, pergunta)
                            if (okDe(r)) null
                            else {
                                perguntasPendentes.remove(sessao)
                                buildJsonObject {
                                    put("ok", false)
                                    put("resposta", "Erro ao executar acao: ${texto(r, "erro") ?: "erro"}")
                                    put("sessao_id", sessao)
                                }
                            }
                        } else {
                            hist.add(buildJsonObject { put("role", "user"); put("content", pergunta) })
                            hist.add(buildJsonObject { put("role", "assistant"); put("content", conteudo) })
                            salvarHistoricoDe(sessao, hist)
                            talvezExtrair(usuario, sessao, pergunta)
                            buildJsonObject {
                                put("ok", true); put("resposta", conteudo)
                                put("sessao_id", sessao); put("usuario", usuario)
                            }
                        }
                    },
                    onFailure = { e ->
                        buildJsonObject {
                            put("ok", false)
                            put("resposta", "Falha na IA: ${e.message ?: "erro"}")
                            put("sessao_id", sessao)
                        }
                    },
                )
            }
        }
        resp?.let { session.emit(ns, "chat_ia_resposta", it) }
    }

    /** Continua a conversa com o resultado da acao (server-side, como o Oracle). */
    private fun continuarAcao(sessao: String, resultado: JsonObject, perguntaOriginal: String) {
        val pend = perguntasPendentes.remove(sessao) ?: return
        val usuario = pend.usuario
        val pergunta = perguntaOriginal.ifBlank { pend.pergunta }
        val json = resultado.toString()
        val hist = historicoDe(sessao)
        val follow = "Resultado da acao: $json\nAgora responda ao usuario."
        val resp = try {
            ia?.perguntar(follow, hist.toList(), sistemaIa(usuario))?.fold(
                onSuccess = { bruto ->
                    // O modelo as vezes responde a continuacao com OUTRO
                    // tool_call; descartamos (como o Oracle faz) para nao
                    // vazar JSON cru na resposta.
                    val (limpo, _) = IaToolCall.extrair(bruto)
                    val txt = limpo.ifBlank { "Acao executada." }
                    hist.add(buildJsonObject { put("role", "user"); put("content", follow) })
                    hist.add(buildJsonObject { put("role", "assistant"); put("content", txt) })
                    salvarHistoricoDe(sessao, hist)
                    talvezExtrair(usuario, sessao, pergunta)
                    buildJsonObject {
                        put("ok", true); put("resposta", txt)
                        put("sessao_id", sessao); put("usuario", usuario)
                    }
                },
                onFailure = { e ->
                    buildJsonObject {
                        put("ok", false); put("resposta", "Falha ao processar resultado: ${e.message ?: "erro"}")
                        put("sessao_id", sessao)
                    }
                },
            )
        } catch (e: Exception) {
            buildJsonObject { put("ok", false); put("resposta", "Falha ao processar resultado: ${e.message ?: "erro"}") }
        }
        resp?.let { broadcast("/ia", "chat_ia_resposta", it) }
    }

    /** Instrucao de sistema: persona + catalogo + instrucoes/perfil do usuario. */
    private fun sistemaIa(usuario: String): String {
        val sb = StringBuilder(Instrucoes.montar())
        val u = memoria?.usuario(usuario)
        if (u != null && u.instrucoes.isNotBlank()) {
            sb.append("\n\n[Instrucoes permanentes do usuario (obedeca sempre)]:\n").append(u.instrucoes)
        }
        if (u != null && u.perfil.isNotEmpty()) {
            sb.append("\n\n[Perfil do usuario - fatos conhecidos:]\n")
            sb.append(u.perfil.joinToString("\n") { "- $it" })
        }
        return sb.toString()
    }

    private fun historicoDe(sessao: String): MutableList<JsonObject> =
        memoria?.historico(sessao) ?: historicoMemoria.computeIfAbsent(sessao) { mutableListOf() }

    private fun salvarHistoricoDe(sessao: String, hist: List<JsonObject>) {
        val cortado = if (hist.size > MemoriaIa.MAX_HISTORICO) hist.takeLast(MemoriaIa.MAX_HISTORICO) else hist
        if (memoria != null) memoria.salvarHistorico(sessao, cortado)
        else historicoMemoria[sessao] = cortado.toMutableList()
    }

    /**
     * Extrai fatos duraveis em background, a cada N perguntas do usuario
     * (como o `_talvez_extrair_async` do Oracle). Nao trava a resposta.
     */
    private fun talvezExtrair(usuario: String, sessao: String, pergunta: String) {
        val ia = this.ia ?: return
        val mem = memoria ?: return
        if (!ia.configurada || pergunta.isBlank()) return
        val n = contadorExtracao.merge(usuario, 1) { a, _ -> a + 1 } ?: 1
        if (n % N_PERGUNTAS_EXTRACAO != 0 && n != 1) return
        val perguntas = historicoDe(sessao)
            .filter { (it["role"] as? JsonPrimitive)?.content == "user" }
            .mapNotNull { (it["content"] as? JsonPrimitive)?.content }
            .takeLast(12)
            .toMutableList()
        if (pergunta.isNotBlank()) perguntas.add(pergunta)
        Thread {
            val fatos = ia.extrairFatos(perguntas)
            if (fatos.isNotEmpty()) mem.fundirPerfil(usuario, fatos)
        }.apply { isDaemon = true }.start()
    }

    /**
     * Executa uma acao pedida pela IA. O Hub nao executa nada: encaminha o
     * evento para a bridge do Arch (que roda no notebook) e espera o
     * resultado, igual ao `_executar_tool_call` do relay Oracle.
     */
    private fun executarToolCall(toolCall: JsonObject, sessao: String, pergunta: String): JsonObject {
        val acao = texto(toolCall, "acao")
        val params = toolCall["params"] as? JsonObject ?: buildJsonObject {}
        return when (acao) {
            "executar_scan" -> {
                val scan = texto(params, "scan")
                if (scan.isNullOrBlank()) return erroAcao("scan nao especificado")
                broadcast(
                    "/", "ia_executar_scan", buildJsonObject {
                        put("scan", scan)
                        put("alvo", texto(params, "alvo") ?: "")
                        put("sessao_id", sessao)
                        put("pergunta_original", pergunta)
                    }
                )
                buildJsonObject { put("ok", true); put("status", "scan_enviado_para_execucao"); put("scan", scan) }
            }
            "executar_comando_notebook" -> {
                val comando = texto(params, "comando")
                if (comando.isNullOrBlank()) return erroAcao("comando nao especificado")
                broadcast(
                    "/", "ia_executar_comando", buildJsonObject {
                        put("comando", comando)
                        put("sessao_id", sessao)
                        put("pergunta_original", pergunta)
                    }
                )
                buildJsonObject { put("ok", true); put("status", "comando_enviado_para_execucao"); put("comando", comando) }
            }
            "obter_info_sistema" -> {
                broadcast(
                    "/", "ia_obter_info", buildJsonObject {
                        put("sessao_id", sessao)
                        put("pergunta_original", pergunta)
                    }
                )
                buildJsonObject { put("ok", true); put("status", "info_solicitada") }
            }
            else -> erroAcao("acao desconhecida: $acao")
        }
    }

    private fun erroAcao(msg: String) = buildJsonObject { put("ok", false); put("erro", msg) }

    private fun okDe(o: JsonObject): Boolean =
        (o["ok"] as? JsonPrimitive)?.content?.toBoolean() ?: false

    private fun listaChat(session: Session) {
        val arr: JsonArray = buildJsonArray { store.mensagens().forEach { add(mensagemJson(it)) } }
        session.emit("/", "chat_lista", buildJsonObject { put("mensagens", arr) })
    }

    private fun listaTasks(session: Session) {
        val arr: JsonArray = buildJsonArray { store.tasks().forEach { add(taskJson(it)) } }
        session.emit("/", "task_lista", buildJsonObject { put("tasks", arr) })
    }

    private fun listaRelatorios(session: Session) {
        val arr: JsonArray = buildJsonArray { store.relatorios().forEach { add(relatorioJson(it)) } }
        session.emit("/", "relatorio_lista", buildJsonObject { put("relatorios", arr) })
    }

    // -------------------------------------------------------------- saida

    /** Le um campo string do payload, tolerando null/vazio. */
    private fun texto(o: JsonObject?, chave: String): String? =
        (o?.get(chave) as? JsonPrimitive)?.content

    fun broadcast(namespace: String, event: String, data: JsonElement, exceto: Session? = null) {
        for (s in sessoes.toList()) {
            if (s === exceto) continue
            s.emit(namespace, event, data)
        }
    }

    /**
     * "iso" em texto ISO-8601 UTC, e nao o numero cru: put(Double) sai em
     * notacao cientifica (1.791136737178E9) e quebra quem faz
     * Instant.parse/OffsetDateTime.parse. O relay Python ja mandava texto,
     * entao e esse o contrato que o PentestLink e o RelatoriosClient leem.
     */
    fun iso(epochSegundos: Double): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT.format(
            java.time.Instant.ofEpochMilli((epochSegundos * 1000).toLong())
        )

    fun mensagemJson(m: Mensagem) = buildJsonObject {
        put("id", m.id)
        put("texto", m.texto)
        m.autor?.let { put("autor", it) }
        put("tipo", m.tipo)
        put("iso", iso(m.ts))
        put("ts", m.ts.toLong() * 1000)
        m.extra?.let { put("extra", it) }
    }

    fun taskJson(t: Task) = buildJsonObject {
        put("id", t.id)
        // O app (TelaHub.kt:652) le "titulo" e "ts"; o servidor Python usa
        // "titulo" tambem. "texto"/"criado" sao o apelido antigo deste repo.
        // Mandamos os dois: qualquer cliente dos dois jeitos le certo.
        put("titulo", t.texto)
        put("texto", t.texto)
        put("feito", t.feito)
        put("ts", (t.criado * 1000).toLong())
        put("criado", t.criado)
        t.extra?.let { put("extra", it) }
    }

    fun relatorioJson(r: Relatorio) = buildJsonObject {
        put("nome", r.nome)
        put("bytes", r.bytes)
        put("iso", iso(r.ts))
        put("ts", (r.ts * 1000).toLong())
        put("caminho", r.caminho)
        put("documentos", buildJsonArray { r.documentos.forEach { add(JsonPrimitive(it)) } })
    }
}
