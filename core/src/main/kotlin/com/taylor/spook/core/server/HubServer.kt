package com.taylor.spook.core.server

import com.taylor.spook.core.model.Mensagem
import com.taylor.spook.core.model.Relatorio
import com.taylor.spook.core.model.Task
import com.taylor.spook.core.protocol.EngineIoPacket
import com.taylor.spook.core.protocol.EngineIoType
import com.taylor.spook.core.protocol.SocketIoPacket
import com.taylor.spook.core.protocol.decodePackets
import com.taylor.spook.core.protocol.encodePackets
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Transporte: Engine.IO v4 sobre HTTP long-polling, com upgrade para WebSocket.
 *
 * Handshake identico ao do python-socketio:
 *   GET /socket.io/?EIO=4&transport=polling
 *   -> 0{"sid":..,"upgrades":["websocket"],"pingInterval":..,"pingTimeout":..,"maxPayload":..}
 */
class HubServer(
    private val hub: Hub,
    private val port: Int,
    private val host: String = "0.0.0.0",
    private val pingIntervalMs: Long = 25_000,
    private val pingTimeoutMs: Long = 20_000,
    private val maxPayload: Long = 1_000_000,
) {
    private val log = LoggerFactory.getLogger("spook.server")
    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    /**
     * Heartbeat do Engine.IO v4: quem pinga e' o SERVIDOR ("2" a cada
     * pingInterval, o cliente responde "3"). O HubServer respondia o ping do
     * cliente, mas nunca mandava o dele -- e como o handshake anuncia
     * pingInterval + pingTimeout, todo cliente esperava 45s, abortava com
     * "Server has stopped communicating" e reconectava. No celular isso dava
     * uma sessao nova a cada ~40s (app, bridge do Arch e overlay em loop).
     */
    private val heartbeat: java.util.concurrent.ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "spook-heartbeat").apply { isDaemon = true }
        }

    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /** porta real (importa quando o constructo usa port=0 para pegar porta livre) */
    fun portaEfetiva(): Int {
        val e = engine?.engine ?: return port
        return runBlocking { e.resolvedConnectors() }.firstOrNull()?.port ?: port
    }

    fun iniciar(): HubServer {
        log.info("SPOOK SERVER ouvindo em {}:{} (EIO=4)", host, port)
        engine = embeddedServer(CIO, port = port, host = host) {
            install(WebSockets)
            routing {
                get("/") {
                    call.respondText(PainelWeb.html, ContentType.Text.Html)
                }
                get("/healthz") {
                    call.respondText("""{"ok":true,"servidor":"spook-server"}""", ContentType.Application.Json)
                }
                // O webSocket PRECISA vir antes do get: os dois casam em GET e o
                // Ktor fica com o primeiro. Com o get na frente, o upgrade era
                // atendido como long-poll comum e o cliente fechava o socket.
                webSocket("/socket.io/") {
                    upgrade(call.request.queryParameters["sid"]?.let { sessions[it] })
                }
                get("/socket.io/") { call.handshakeOuLongPoll() }
                post("/socket.io/") { call.receberPolling() }

                // Mesmo handshake tambem sob /ia/socket.io/. O app monta
                // IO.socket("http://host:5000/ia") e dependendo da versao do
                // cliente ele deriva o path da URL: o relay so servia
                // /socket.io/, o que deixava o namespace /ia dependendo de
                // cliente. Servir os dois deixa qualquer variante funcionar.
                webSocket("/ia/socket.io/") {
                    upgrade(call.request.queryParameters["sid"]?.let { sessions[it] })
                }
                get("/ia/socket.io/") { call.handshakeOuLongPoll() }
                post("/ia/socket.io/") { call.receberPolling() }

                // ------------------------------------------------------------ REST
                //
                // Mesmo Hub, mesma fonte da verdade: o REST nao e um caminho
                // paralelo, so outra porta de entrada. Por isso os dois lados
                // enxergam a mudanca na hora (o app escuta chat_nova, o curl
                // le em task_listar).
                get("/api/chat") { call.listarChat(hub) }
                post("/api/chat") { call.enviarChat(hub) }
                delete("/api/chat/{id}") { call.apagarChat(hub, call.parameters["id"]) }
                get("/api/tasks") { call.listarTasks(hub) }
                post("/api/tasks") { call.criarTask(hub) }
                patch("/api/tasks/{id}") { call.marcarTask(hub, call.parameters["id"]) }
                delete("/api/tasks/{id}") { call.apagarTask(hub, call.parameters["id"]) }
                get("/api/relatorios") { call.listarRelatorios(hub) }
                post("/api/relatorios") { call.registrarRelatorio(hub) }
                delete("/api/relatorios") { call.apagarRelatorios(hub) }
                // Le o arquivo do relatorio. O app pede a base sem extensao
                // (o nome no Drive era sem .pdf/.docx), por isso o fallback
                // tenta as extensoes conhecidas.
                get("/api/relatorios/{nome}") { call.baixarRelatorio(hub, call.parameters["nome"].orEmpty()) }
                // ---- media (espelho do que o relay Oracle servia) ----
                // POST multipart: recebe o arquivo do Termux/app, grava na
                // pasta de midia do hub e cria a mensagem de chat. Com
                // pasta=relatorios o arquivo entra na lista de relatorios.
                post("/api/upload") { call.upload(hub) }
                get("/api/media") { call.listarMedia(hub) }
                get("/api/media/{nome}") { call.baixarMedia(hub, call.parameters["nome"].orEmpty()) }

                // ---- rotas compativeis com o relay Oracle (sem /api) ----
                // O app PentestLink, o overlay e o fallback do Termux falam os
                // caminhos do relay; servindo os dois o cliente so troca o IP.
                post("/upload") { call.upload(hub) }
                get("/media") { call.listarMedia(hub) }
                get("/media/{nome}") { call.baixarMedia(hub, call.parameters["nome"].orEmpty()) }
                get("/relatorios") { call.listarRelatorios(hub) }
                delete("/relatorios") { call.apagarRelatorios(hub) }
                get("/relatorios/{nome}") { call.baixarRelatorio(hub, call.parameters["nome"].orEmpty()) }
                // respond(JsonObject) sem ContentNegotiation responde 406: nao ha
                // conversor instalado. Serializamos na mao.
                // Backup sem Drive: tudo que o hub tem, em um JSON. E o
                // caminho de "guardar no celular" -- nao depende de Google,
                // de notebook, nem de rclone.
                get("/api/backup") { call.backup(hub) }
                // /api/info tambem exige token: devolve IPs, bateria, memoria e
                // consumo do aparelho. So o /healthz e publico.
                get("/api/info") {
                    // /api/info tambem exige token: devolve IPs, bateria,
                    // memoria e consumo. So o /healthz e publico.
                    if (!call.autorizado(hub)) return@get
                    call.respondText(hub.resumoSistema().toString(), ContentType.Application.Json)
                }
                // Login por senha do painel web: devolve token de pareamento.
                // So funciona se houver senha configurada no Android.
                post("/api/auth/login") { call.loginSenhaWeb(hub) }
                // Definir/alterar senha do painel web: exige token de pareamento.
                post("/api/auth/senha") { call.definirSenhaWeb(hub) }
            }
        }.also { it.start(wait = false) }
        heartbeat.scheduleAtFixedRate(::bater, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS)
        return this
    }

    fun parar() {
        heartbeat.shutdownNow()
        sessions.values.toList().forEach { fechar(it.engineSid) }
        engine?.stop(500, 500)
    }

    /**
     * Um tick do heartbeat. Manda PING em toda sessao viva e derruba a que
     * ficou muda -- mas so a de polling: o websocket fecha o fluxo na hora em
     * que o socket morre (o `fechar` do upgrade cuida disso), enquanto no
     * polling um cliente que sumiu deixa o long-poll pendurado e nada avisa.
     */
    private fun bater() {
        val agora = System.currentTimeMillis()
        for (s in sessions.values.toList()) {
            if (!s.upgraded && agora - s.ultimaAtividade > pingIntervalMs + pingTimeoutMs) {
                log.info(
                    "[TIMEOUT] {} em polling sem sinal ha {}s",
                    s.engineSid,
                    (agora - s.ultimaAtividade) / 1000,
                )
                fechar(s.engineSid)
            } else {
                s.send(EngineIoPacket(EngineIoType.PING))
            }
        }
    }

private fun abrirPacket(sid: String): EngineIoPacket {
        // JSON montado com a biblioteca: manual ja errou uma vez e o cliente
        // morre com JSONException em vez de decir o que houve.
        val json = buildJsonObject {
            put("sid", sid)
            put("upgrades", buildJsonArray { add("websocket") })
            put("pingInterval", pingIntervalMs)
            put("pingTimeout", pingTimeoutMs)
            put("maxPayload", maxPayload)
        }
        return EngineIoPacket(EngineIoType.OPEN, Json.encodeToString(JsonElement.serializer(), json))
    }

    private fun novoSid(): String =
        // URL-safe: o engine.io-client nao faz encode do sid na query, entao um
        // '/' ou '+' do base64 comum quebraria a URL
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(15).also { random.nextBytes(it) })

    private suspend fun ApplicationCall.responder(pkts: List<EngineIoPacket>) {
        val texto = encodePackets(pkts)
        log.debug("[GET ] sid={} bytes={}", request.queryParameters["sid"], texto.length)
        respondText(texto, ContentType.Text.Plain)
    }

    // ---------------------------------------------------------------- polling

    private suspend fun ApplicationCall.handshakeOuLongPoll() {
        val sid = request.queryParameters["sid"]

        if (sid == null) {
            val novo = novoSid()
            val s = Session(novo, request.local.remoteHost)
            sessions[novo] = s
            hub.sessoes.add(s)
            s.markHandshakeSent()
            // resposta direta, sem enfileirar: enfileirar devolveria o OPEN
            // de novo no proximo long-poll
            responder(listOf(abrirPacket(novo)))
            log.info("[CONECTADO] handshake {} de {}", novo, s.remote)
            return
        }

        val s = sessions[sid]
        if (s == null) {
            responder(listOf(EngineIoPacket(EngineIoType.CLOSE)))
            return
        }
        // O long-poll em si e' sinal de vida: um cliente em polling que morreu
        // para de pedir, e e' assim que o heartbeat descobre (nao ha pacote).
        s.marcarAtividade()

        // long-poll: ate 20s por pacote, senao devolve NOOP para o cliente voltar
        val lote = withTimeoutOrNull(20_000) {
            buildList {
                add(s.pending.receive())
                while (true) add(s.pending.tryReceive().getOrNull() ?: break)
            }
        }
        responder(lote ?: listOf(EngineIoPacket(EngineIoType.NOOP)))
    }

    private suspend fun ApplicationCall.receberPolling() {
        val sid = request.queryParameters["sid"]
        val corpo = receiveText()
        log.debug("[POST] sid={} bytes={}", sid, corpo.length)
        if (sid == null || corpo.isEmpty()) {
            respondText("", ContentType.Text.Plain)
            return
        }
        sessions[sid]?.let { s ->
            s.marcarAtividade()
            for (pkt in decodePackets(corpo)) {
                when (pkt.type) {
                    EngineIoType.MESSAGE -> SocketIoPacket.decode(pkt.data ?: "")?.let { hub.entrada(s, it) }
                    EngineIoType.PING -> s.send(EngineIoPacket(EngineIoType.PONG))
                    EngineIoType.CLOSE -> fechar(sid)
                    else -> Unit
                }
            }
        }
        respondText("", ContentType.Text.Plain)
    }

    private fun fechar(sid: String) {
        sessions.remove(sid)?.let {
            hub.sessoes.remove(it)
            it.close()
            log.info("[DESCONECTADO] {}", sid)
        }
    }

    // -------------------------------------------------------------- websocket

    /**
     * Upgrade do Engine.IO v4:
     *   servidor -> 2 (probe) | cliente -> 3 (probe pong) | cliente -> 5 (upgrade) | servidor -> 6 (noop)
     */
    private suspend fun DefaultWebSocketServerSession.upgrade(s: Session?) {
        val direto = s == null
        val sess: Session = s ?: run {
            val id = novoSid()
            Session(id, call.request.local.remoteHost).also { nova ->
                sessions[id] = nova
                hub.sessoes.add(nova)
            }
        }

        suspend fun mandar(p: EngineIoPacket) {
            val texto = p.encode()
            log.debug("[WS ] sid={} bytes={}", sess.engineSid, texto.length)
            send(Frame.Text(texto))
        }

        log.debug("[WS ] conectado sid={} direto={}", sess.engineSid, direto)
        if (direto) {
            // transports=websocket: sem polling antes, entao o OPEN vai no WS
            mandar(abrirPacket(sess.engineSid))
        }
        // Nao manda "2probe" aqui: o engine.io-client registra o listener de
        // probe com once("packet"), entao qualquer pacote nosso antes do probe
        // dele consome o once e o upgrade morre com "probe transport failed".
        // Quem inicia o probe e o cliente; nosso papel e responder "3probe".

        // O writer do Hub passa a alimentar uma fila, drenada por uma coroutine,
        // porque o writer do Session nao pode ser suspend.
        val outbound = Channel<EngineIoPacket>(128)
        val writer = launch { for (p in outbound) mandar(p) }
        if (direto) sess.switchToWebSocket { p -> outbound.trySend(p) }

        for (frame in incoming) {
            val texto = (frame as? Frame.Text)?.readText() ?: continue
            log.debug("[WS ] sid={} bytes={}", sess.engineSid, texto.length)
            sess.marcarAtividade()
            for (pkt in decodePackets(texto)) {
                when (pkt.type) {
                    // "2probe" -> "3probe": o PONG precisa ecoar o data, senao o
                    // engine.io-client nao casa o probe e mata o socket.
                    // O mesmo caminho serve pro heartbeat, que nao tem data.
EngineIoType.PING -> {
                        mandar(EngineIoPacket(EngineIoType.PONG, pkt.data))
                        if (pkt.data == "probe") {
                            // O cliente so manda o "5" (upgrade) DEPOIS que o poll
                            // em voo termina ("we are currently polling - waiting
                            // to pause"). Sem este NOOP o upgrade fica preso ate
                            // o poll estourar o timeout - e no celular isso
                            // significa polling por 20s e radio acordado.
                            sess.send(EngineIoPacket(EngineIoType.NOOP))
                        }
                    }

                    // upgrade confirmado: aqui o transporte mesmo vira websocket
                    EngineIoType.UPGRADE -> {
                        // o que ficou preso no long-poll precisa ir junto, senao
                        // o evento some na virada de transporte
                        while (true) {
                            @Suppress("UNCHECKED_CAST")
                            val p = sess.pending.tryReceive().getOrNull() ?: break
                            outbound.trySend(p)
                        }
                        sess.switchToWebSocket { p -> outbound.trySend(p) }
                        mandar(EngineIoPacket(EngineIoType.NOOP))
                        log.info("[UPGRADE] {} agora em websocket", sess.engineSid)
                    }

                    EngineIoType.MESSAGE ->
                        SocketIoPacket.decode(pkt.data ?: "")?.let { hub.entrada(sess, it) }

                    EngineIoType.CLOSE -> {
                        writer.cancel()
                        fechar(sess.engineSid)
                        return
                    }

                    else -> Unit
                }
            }
        }
        writer.cancel()
        log.debug("[WS ] fim do fluxo de {}", sess.engineSid)
        fechar(sess.engineSid)
    }
}

// ============================================================================
// REST
//
// Nao e um caminho paralelo: toda escrita aqui passa pelo mesmo Store e e
// anunciada no socket, entao app e curl nunca veem estados diferentes.
// ============================================================================

    private fun ApplicationCall.bearer(): String? =
        request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

private fun String.jsonQ(): String = buildString {
    append('"')
    for (c in this@jsonQ) append(
        when (c) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (c.code < 0x20) "\\u%04x".format(c.code) else c.toString()
        }
    )
    append('"')
}

internal suspend fun ApplicationCall.erro(code: Int, msg: String) =
    respondText(
        """{"erro":${msg.jsonQ()}}""",
        ContentType.Application.Json,
        status = HttpStatusCode.fromValue(code),
    )

internal suspend fun ApplicationCall.autorizado(hub: Hub): Boolean =
    if (hub.tokenOk(bearer())) true
    else {
        erro(401, "token invalido ou ausente")
        false
    }

/**
 * Le o corpo como objeto JSON, sem estourar se o cliente mandou lixo.
 *
 * O corpo e lido do canal cru, e nao com receiveText(): o receiveText()
 * recusa Content-Type application/json quando nao ha ContentNegotiation
 * instalada e devolve 406. Um endpoint REST nao pode depender do header do
 * cliente -- curl, fetch e o app mandam coisas diferentes.
 */
internal suspend fun ApplicationCall.corpo(): JsonObject? = runCatching {
    Json.parseToJsonElement(receiveChannel().receberTudo()).jsonObject
}.getOrNull()

/** Esvazia o canal num String (Ktor 3 nao tem toByteArray em ByteReadChannel). */
private suspend fun ByteReadChannel.receberTudo(): String = readRemaining().readText()

private fun ApplicationCall.campo(o: JsonObject?, vararg nomes: String): String? =
    nomes.firstNotNullOfOrNull { (o?.get(it) as? JsonPrimitive)?.content?.takeIf { c -> c.isNotBlank() } }

private suspend fun ApplicationCall.verificarSenhaWeb(hub: Hub, senha: String): String? {
    return hub.verificarSenhaWeb(senha)
}

private suspend fun ApplicationCall.listarChat(hub: Hub) {
    if (!autorizado(hub)) return
    val arr = buildJsonArray { hub.store.mensagens().forEach { add(hub.mensagemJson(it)) } }
    respondText(buildJsonObject { put("mensagens", arr) }.toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.enviarChat(hub: Hub) {
    if (!autorizado(hub)) return
    val o = corpo() ?: return erro(400, "corpo invalido")
    val texto = campo(o, "texto", "mensagem") ?: return erro(400, "campo texto obrigatorio")
    val m = Mensagem(
        id = campo(o, "id") ?: UUID.randomUUID().toString(),
        texto = texto,
        autor = campo(o, "autor") ?: "rest",
        tipo = campo(o, "tipo") ?: "texto",
        ts = System.currentTimeMillis() / 1000.0,
    )
    hub.store.inserirMensagem(m)
    hub.broadcast("/", "chat_nova", hub.mensagemJson(m))
    respondText(hub.mensagemJson(m).toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.apagarChat(hub: Hub, id: String?) {
    if (!autorizado(hub)) return
    if (id.isNullOrBlank() || !hub.store.apagarMensagem(id)) return erro(404, "mensagem nao existe")
    hub.broadcast("/", "chat_apagada", buildJsonObject { put("id", id) })
    respondText("""{"ok":true}""", ContentType.Application.Json)
}

private suspend fun ApplicationCall.listarTasks(hub: Hub) {
    if (!autorizado(hub)) return
    val arr = buildJsonArray { hub.store.tasks().forEach { add(hub.taskJson(it)) } }
    respondText(buildJsonObject { put("tasks", arr) }.toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.criarTask(hub: Hub) {
    if (!autorizado(hub)) return
    val o = corpo() ?: return erro(400, "corpo invalido")
    // "titulo" e o dialeto do app (TelaHub.kt), "texto" o do servidor Python.
    val titulo = campo(o, "titulo", "texto") ?: return erro(400, "campo titulo obrigatorio")
    val t = Task(
        id = campo(o, "id") ?: UUID.randomUUID().toString(),
        texto = titulo,
        feito = (o["feito"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
        criado = System.currentTimeMillis() / 1000.0,
    )
    hub.store.inserirTask(t)
    hub.broadcast("/", "task_nova", hub.taskJson(t))
    respondText(hub.taskJson(t).toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.marcarTask(hub: Hub, id: String?) {
    if (!autorizado(hub)) return
    val atual = id?.let { hub.store.task(it) } ?: return erro(404, "task nao existe")
    // Sem body, o PATCH e o atalho de "marcar": o app faz isso com um toque
    // no checkbox e nao viaja um JSON para isso.
    val nova = atual.copy(
        feito = (corpo()?.get("feito") as? JsonPrimitive)?.content?.toBoolean() ?: !atual.feito,
    )
    hub.store.atualizarTask(nova)
    hub.broadcast("/", "task_atualizada", hub.taskJson(nova))
    respondText(hub.taskJson(nova).toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.apagarTask(hub: Hub, id: String?) {
    if (!autorizado(hub)) return
    if (id.isNullOrBlank() || !hub.store.apagarTask(id)) return erro(404, "task nao exists")
    hub.broadcast("/", "task_apagada", buildJsonObject { put("id", id) })
    respondText("""{"ok":true}""", ContentType.Application.Json)
}

private suspend fun ApplicationCall.listarRelatorios(hub: Hub) {
    if (!autorizado(hub)) return
    val arr = buildJsonArray { hub.store.relatorios().forEach { add(hub.relatorioJson(it)) } }
    respondText(buildJsonObject { put("relatorios", arr) }.toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.registrarRelatorio(hub: Hub) {
    if (!autorizado(hub)) return
    val o = corpo() ?: return erro(400, "corpo invalido")
    val nome = campo(o, "nome") ?: return erro(400, "nome obrigatorio")
    val bytes = (campo(o, "bytes")?.toLongOrNull()) ?: 0L
    val ts = (campo(o, "ts")?.toDoubleOrNull()) ?: (System.currentTimeMillis() / 1000.0)
    val caminho = campo(o, "caminho") ?: ""
    val docsJson = o["documentos"] as? kotlinx.serialization.json.JsonArray
    val docs = docsJson?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?.filterNotNull() ?: emptyList()
    
    val r = Relatorio(
        nome = nome,
        bytes = bytes,
        ts = ts,
        caminho = caminho,
        documentos = docs
    )
    hub.store.registrarRelatorio(r)
    hub.broadcast("/", "relatorio_novo", hub.relatorioJson(r))
    respondText(hub.relatorioJson(r).toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.backup(hub: Hub) {
    if (!autorizado(hub)) return
    val ms = hub.store.mensagens()
    val ts = hub.store.tasks()
    val doc = buildJsonObject {
        put("formato", "spook-backup/1")
        put("gerado_em", hub.iso(System.currentTimeMillis() / 1000.0))
        put("servidor", "SPOOK SERVER")
        put("contagens", buildJsonObject {
            put("mensagens", ms.size)
            put("tasks", ts.size)
        })
        put("mensagens", buildJsonArray { ms.forEach { add(hub.mensagemJson(it)) } })
        put("tasks", buildJsonArray { ts.forEach { add(hub.taskJson(it)) } })
    }
    // filename na resposta para o curl -o cair com nome util
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=spook-backup.json")
    respondText(doc.toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.loginSenhaWeb(hub: Hub) {
    val o = corpo() ?: return erro(400, "corpo invalido")
    val senha = campo(o, "senha") ?: return erro(400, "senha obrigatoria")
    val token = hub.verificarSenhaWeb(senha) ?: return erro(401, "senha invalida")
    respondText(buildJsonObject { put("token", token) }.toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.definirSenhaWeb(hub: Hub) {
    if (!autorizado(hub)) return
    val o = corpo() ?: return erro(400, "corpo invalido")
    val senha = campo(o, "senha") ?: ""
    if (senha.isNotBlank()) hub.definirSenhaWeb(senha)
    else hub.definirSenhaWeb("") // string vazia = desabilita
    respondText("""{"ok":true}""", ContentType.Application.Json)
}
