package com.taylor.spook.core

import com.taylor.spook.core.server.Hub
import com.taylor.spook.core.server.HubServer
import com.taylor.spook.core.store.SqliteStore
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import io.socket.engineio.client.transports.Polling
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * O teste que importa:_connecta com o MESMO jar que o app Android usa
 * (io.socket:socket.io-client:2.1.0 -> engine.io-client:2.1.0, EIO=4).
 * Se este teste passa, o celular fala com o SPOOK SERVER sem mudar uma linha do app.
 */
class InteropAppClientTest {

    private val token = "token-de-teste-123"
    private lateinit var store: SqliteStore
    private lateinit var server: HubServer

    private var porta = 0
    private lateinit var hub: Hub

    @Before
    fun subir() {
        store = SqliteStore(SqliteStore.IN_MEMORY)
        hub = Hub(store = store, tokenValido = { it == token })
        server = HubServer(hub, port = 0)
        server.iniciar()
        porta = server.portaEfetiva()
        assertTrue("servidor nao subiu na porta $porta", porta > 0)
    }

    @After
    fun descer() {
        server.parar()
        store.close()
    }

    private fun logarJul() {
        // o socket.io-client 2.1.0 loga via java.util.logging, nao via slf4j.
        // Só o io.socket*: mexer na raiz dispara o log de certificados do JDK
        // e esconde o que importa.
        val h = java.util.logging.ConsoleHandler()
        h.level = java.util.logging.Level.ALL
        h.formatter = java.util.logging.SimpleFormatter()
        listOf("io.socket", "io.socket.client", "io.socket.engineio", "engine.IO", "socket.IO")
            .forEach { nome ->
                java.util.logging.Logger.getLogger(nome).apply {
                    level = java.util.logging.Level.ALL
                    handlers.forEach { it.level = java.util.logging.Level.ALL }
                    addHandler(h)
                    useParentHandlers = false
                }
            }
    }

    private fun conectar(): Socket {
        logarJul()
        val opts = IO.Options().apply {
            auth = mapOf("token" to token)
            // default do app: polling primeiro, upgrade para websocket depois
            reconnection = false
        }
        val url = "http://127.0.0.1:$porta"
        val s = IO.socket(url, opts)
        val conectado = CountDownLatch(1)
        val erro = AtomicReference<String>()
        s.io().on("upgrade") { println("  >> upgrade para websocket OK") }
        s.on(Socket.EVENT_CONNECT) { conectado.countDown() }
        s.on(Socket.EVENT_CONNECT_ERROR) { a ->
            erro.set(a?.joinToString(" | ") { it.toString() })
            conectado.countDown()
        }
        s.connect()
        assertTrue(
            "cliente do app nao conectou. erro=${erro.get()} sid=${s.id()} url=$url",
            conectado.await(10, TimeUnit.SECONDS),
        )
        assertTrue("conectou mas com erro: ${erro.get()}", erro.get() == null)
        return s
    }

    /**
     * Sem upgrade o celular fica preso em long-poll, que acorda o radio o tempo
     * todo. A assercao e feita pelo lado do servidor de proposito: o
     * socket.io-client 2.1.0 nao emite "upgrade" no Manager de forma confiavel,
     * mas o Session.upgraded e a verdade sobre o transporte.
     */
    @Test
    fun `faz upgrade de polling para websocket`() {
        val s = conectar()
        val sessao = hub.sessoes.firstOrNull()
            ?: throw AssertionError("servidor nao registrou a sessao")

        val subiu = CountDownLatch(1)
        val vigia = Thread {
            val limite = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < limite) {
                if (sessao.upgraded) {
                    subiu.countDown(); return@Thread
                }
                Thread.sleep(50)
            }
        }
        vigia.start()
        assertTrue("a sessao nunca virou websocket", subiu.await(31, TimeUnit.SECONDS))
        s.disconnect(); s.close()
    }

    @Test
    fun `app conecta e autentica`() {
        val s = conectar()
        assertTrue(s.connected())
        assertNotNull("sem socket id", s.id())
        s.disconnect()
        s.close()
    }

    @Test
    fun `token invalido recebe connect_error`() {
        val opts = IO.Options().apply {
            auth = mapOf("token" to "token-errado")
            reconnection = false
        }
        val s = IO.socket("http://127.0.0.1:$porta", opts)
        val erro = CountDownLatch(1)
        s.on(Socket.EVENT_CONNECT_ERROR) { erro.countDown() }
        s.connect()
        assertTrue("servidor deveria recusar o token", erro.await(10, TimeUnit.SECONDS))
        s.close()
    }

    @Test
    fun `chat_listar devolve chat_lista`() {
        store.inserirMensagem(
            com.taylor.spook.core.model.Mensagem(id = "m1", texto = "oi do teste", ts = 1_700_000_000.0)
        )
        val s = conectar()
        val recebida = AtomicReference<JSONObject>()
        val latch = CountDownLatch(1)
        s.on("chat_lista") { args ->
            recebida.set(args?.firstOrNull() as? JSONObject)
            latch.countDown()
        }
        s.emit("chat_listar", JSONObject())
        assertTrue("chat_lista nao chegou", latch.await(10, TimeUnit.SECONDS))

        val mensagens = recebida.get().getJSONArray("mensagens")
        assertEquals(1, mensagens.length())
        assertEquals("oi do teste", mensagens.getJSONObject(0).getString("texto"))
        s.disconnect(); s.close()
    }

    @Test
    fun `chat_enviar ecoa e persiste`() {
        val s = conectar()
        val eco = CountDownLatch(1)
        val recebida = AtomicReference<JSONObject>()
        s.on("chat_nova") { args ->
            recebida.set(args?.firstOrNull() as? JSONObject)
            eco.countDown()
        }
        val id = java.util.UUID.randomUUID().toString()
        s.emit("chat_enviar", JSONObject().put("id", id).put("texto", "mensagem nova"))
        assertTrue("chat_nova nao chegou", eco.await(10, TimeUnit.SECONDS))
        assertEquals(id, recebida.get().getString("id"))
        assertNotNull("mensagem nao foi persistida", store.mensagem(id))
        s.disconnect(); s.close()
    }

    @Test
    fun `task_criar cria e task_listar devolve`() {
        val s = conectar()
        val criada = CountDownLatch(1)
        s.on("task_nova") { criada.countDown() }
        val id = "task-1"
        s.emit("task_criar", JSONObject().put("id", id).put("texto", "comprar cabo"))
        assertTrue("task_nova nao chegou", criada.await(10, TimeUnit.SECONDS))

        val lista = AtomicReference<JSONObject>()
        val latch = CountDownLatch(1)
        s.on("task_lista") { args ->
            lista.set(args?.firstOrNull() as? JSONObject); latch.countDown()
        }
        s.emit("task_listar", JSONObject())
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(1, lista.get().getJSONArray("tasks").length())
        s.disconnect(); s.close()
    }

    // ------------------------------------------------- o dialeto do app real
    //
    // O app PentestLink (ConexaoChat.kt) emite task_nova/task_apagada com o
    // campo "titulo"; o servidor Python escuta task_criar/task_apagar. Os dois
    // jeitos tem que funcionar, e task_lista tem que devolver "titulo" e "ts"
    // porque e isso que o TelaHub.kt le.

    @Test
    fun `task_nova com titulo e o dialeto do app`() {
        val s = conectar()
        val criada = AtomicReference<JSONObject>()
        val latch = CountDownLatch(1)
        s.on("task_nova") { args ->
            criada.set(args?.firstOrNull() as? JSONObject); latch.countDown()
        }
        s.emit("task_nova", JSONObject().put("titulo", "rebootar o arch"))
        assertTrue("task_nova nao chegou", latch.await(10, TimeUnit.SECONDS))

        val t = criada.get()
        assertEquals("rebootar o arch", t.getString("titulo"))
        // o alias antigo continua no payload
        assertEquals("rebootar o arch", t.getString("texto"))
        // ts em milissegundos, como o app le (TelaHub.kt:655)
        assertTrue("ts deveria ser epoch em ms", t.getLong("ts") > 1_600_000_000_000L)
        assertNotNull("nao persistiu", store.task(t.getString("id")))
        s.disconnect(); s.close()
    }

    @Test
    fun `task_atualizada em task inexistente faz upsert`() {
        val s = conectar()
        val atualizada = CountDownLatch(1)
        val recebida = AtomicReference<JSONObject>()
        s.on("task_atualizada") { args ->
            recebida.set(args?.firstOrNull() as? JSONObject); atualizada.countDown()
        }
        // o app marca task que ele acabou de criar em outro lugar; antes isso
        // caia em return silencioso e a UI ficava parada
        s.emit("task_atualizada", JSONObject().put("id", "task-9x").put("feito", true))
        assertTrue("task_atualizada nao respondeu", atualizada.await(10, TimeUnit.SECONDS))

        val t = recebida.get()
        assertTrue("feito deveria ser true", t.getBoolean("feito"))
        assertNotNull("upsert nao gravou", store.task("task-9x"))
        s.disconnect(); s.close()
    }

    @Test
    fun `task_apagada e o dialeto do app`() {
        store.inserirTask(
            com.taylor.spook.core.model.Task(id = "t-x", texto = "sai fora", criado = 1_700_000_000.0)
        )
        val s = conectar()
        val apagada = CountDownLatch(1)
        s.on("task_apagada") { apagada.countDown() }
        s.emit("task_apagada", JSONObject().put("id", "t-x"))
        assertTrue("task_apagada nao chegou", apagada.await(10, TimeUnit.SECONDS))
        assertEquals(null, store.task("t-x"))
        s.disconnect(); s.close()
    }

    @Test
    fun `info_sistema responde`() {
        val hub = Hub(
            store = store,
            tokenValido = { it == token },
            infoSistema = { buildJsonObject { put("host", "redmi"); put("bateria", 77) } },
        )
        this.hub = hub
        server.parar()
        server = HubServer(hub, port = 0).also { it.iniciar() }
        porta = server.portaEfetiva()

        val s = conectar()
        val info = AtomicReference<JSONObject>()
        val latch = CountDownLatch(1)
        s.on("info_sistema") { args -> info.set(args?.firstOrNull() as? JSONObject); latch.countDown() }
        s.emit("info_sistema", JSONObject())
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals("redmi", info.get().getString("host"))
        s.disconnect(); s.close()
    }
}
