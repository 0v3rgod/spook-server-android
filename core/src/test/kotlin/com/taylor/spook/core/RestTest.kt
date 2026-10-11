package com.taylor.spook.core

import com.taylor.spook.core.server.Hub
import com.taylor.spook.core.server.HubServer
import com.taylor.spook.core.store.SqliteStore
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * O REST nao pode ser um segundo banco de dados. Estes testes usam o MESMO
 * Hub/Store que o socket, entao cada linha escrita por REST tem que aparecer no
 * socket (e vice-versa) sem nenhum codigo de "espelhar" no meio.
 */
class RestTest {

    private val token = "token-de-teste-123"
    private lateinit var store: SqliteStore
    private lateinit var server: HubServer
    private lateinit var hub: Hub
    private var porta = 0

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

    // ------------------------------------------------------------- transporte

    // HttpURLConnection nao sabe PATCH ("Invalid HTTP method"), e o REST
    // tem PATCH de proposito: e o atalho de "marcar" que o app usa.
    private val http = HttpClient.newHttpClient()

    private fun chamar(
        metodo: String,
        caminho: String,
        corpo: String? = null,
        bearer: String? = token,
    ): Pair<Int, String> {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$porta$caminho"))
            .timeout(java.time.Duration.ofSeconds(10))
        if (bearer != null) req.header("Authorization", "Bearer $bearer")
        if (corpo != null) {
            req.header("Content-Type", "application/json; charset=utf-8")
            req.method(metodo, HttpRequest.BodyPublishers.ofString(corpo))
        } else {
            req.method(metodo, HttpRequest.BodyPublishers.noBody())
        }
        val r = http.send(req.build(), HttpResponse.BodyHandlers.ofString())
        return r.statusCode() to r.body()
    }

    private fun conectarSocket(): Socket {
        val opts = IO.Options().apply {
            auth = mapOf("token" to token)
            reconnection = false
        }
        val s = IO.socket("http://127.0.0.1:$porta", opts)
        val ok = CountDownLatch(1)
        s.on(Socket.EVENT_CONNECT) { ok.countDown() }
        s.on(Socket.EVENT_CONNECT_ERROR) { ok.countDown() }
        s.connect()
        assertTrue("socket nao conectou", ok.await(10, TimeUnit.SECONDS))
        return s
    }

    // ----------------------------------------------------------------- token

    @Test
    fun `sem token o REST e 401`() {
        assertEquals(401, chamar("GET", "/api/chat", bearer = null).first)
        assertEquals(401, chamar("GET", "/api/tasks", bearer = "token-errado").first)
        // /api/info entrega IPs, bateria e memoria: tambem e privado
        assertEquals(401, chamar("GET", "/api/info", bearer = null).first)
        assertEquals(401, chamar("GET", "/api/backup", bearer = "token-errado").first)
        // e o healthz e a unica rota sem token -- e o que o uptime check olha
        assertEquals(200, chamar("GET", "/healthz", bearer = null).first)
    }

    @Test
    fun `token na query nao autentica`() {
        assertEquals(401, chamar("GET", "/api/tasks?token=$token", bearer = null).first)
    }

    // ------------------------------------------------------------------ chat

    @Test
    fun `POST chat pelo REST aparece no socket`() {
        val s = conectarSocket()
        val chegou = CountDownLatch(1)
        val autor = AtomicReference<String>()
        s.on("chat_nova") { a ->
            val o = a?.firstOrNull() as? org.json.JSONObject
            autor.set(o?.optString("autor"))
            chegou.countDown()
        }

        val (codigo, texto) = chamar("POST", "/api/chat", """{"texto":"oi do curl","autor":"curl"}""")
        assertEquals(200, codigo)
        assertTrue("a resposta devolveu $texto", texto.contains("oi do curl"))
        assertTrue("chat_nova nao chegou no socket", chegou.await(5, TimeUnit.SECONDS))
        assertEquals("curl", autor.get())
        assertEquals(1, store.mensagens().size)
        s.close()
    }

    @Test
    fun `GET chat lista o que esta no banco`() {
        chamar("POST", "/api/chat", """{"texto":"primeira"}""")
        val (codigo, texto) = chamar("GET", "/api/chat")
        assertEquals(200, codigo)
        assertTrue(texto.contains("primeira"))
        // ts em ms, porque e o que o app le
        assertTrue("ts tem que sair em ms", Regex("\"ts\"\\s*:\\s*\\d{13}").containsMatchIn(texto))
    }

    @Test
    fun `DELETE chat remove do banco e avisa o socket`() {
        val (_, criada) = chamar("POST", "/api/chat", """{"texto":"para apagar"}""")
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(criada)!!.groupValues[1]

        val s = conectarSocket()
        val aviso = CountDownLatch(1)
        s.on("chat_apagada") { aviso.countDown() }

        assertEquals(200, chamar("DELETE", "/api/chat/$id").first)
        assertTrue("chat_apagada nao chegou", aviso.await(5, TimeUnit.SECONDS))
        assertEquals(0, store.mensagens().size)

        assertEquals("apagar duas vezes e 404", 404, chamar("DELETE", "/api/chat/$id").first)
        s.close()
    }

    @Test
    fun `POST chat sem texto e 400`() {
        assertEquals(400, chamar("POST", "/api/chat", """{"autor":"curl"}""").first)
        assertEquals(400, chamar("POST", "/api/chat", """{"texto":"   "}""").first)
        assertEquals(400, chamar("POST", "/api/chat", "nao e json").first)
        assertEquals(0, store.mensagens().size)
    }

    // ----------------------------------------------------------------- tasks

    @Test
    fun `POST task aceita titulo e texto`() {
        // "titulo" e o dialeto do app, "texto" o do servidor Python: o REST
        // precisa falar com os dois sem depender de qual cliente chamou.
        assertEquals(200, chamar("POST", "/api/tasks", """{"titulo":"com titulo"}""").first)
        assertEquals(200, chamar("POST", "/api/tasks", """{"texto":"com texto"}""").first)
        assertEquals(listOf("com texto", "com titulo"), store.tasks().map { it.texto }.sorted())
    }

    @Test
    fun `POST task pelo REST aparece no socket como task_nova`() {
        val s = conectarSocket()
        val chegou = CountDownLatch(1)
        s.on("task_nova") { chegou.countDown() }
        assertEquals(200, chamar("POST", "/api/tasks", """{"titulo":"do curl"}""").first)
        assertTrue("task_nova nao chegou", chegou.await(5, TimeUnit.SECONDS))
        s.close()
    }

    @Test
    fun `PATCH task alterna o feito quando vem sem corpo`() {
        val (_, criada) = chamar("POST", "/api/tasks", """{"titulo":"marcar"}""")
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(criada)!!.groupValues[1]
        assertEquals(false, store.task(id)!!.feito)

        assertEquals(200, chamar("PATCH", "/api/tasks/$id").first)
        assertEquals(true, store.task(id)!!.feito)

        // agora forcando o valor, que e o que o checkbox do app manda
        assertEquals(200, chamar("PATCH", "/api/tasks/$id", """{"feito":false}""").first)
        assertEquals(false, store.task(id)!!.feito)
    }

    @Test
    fun `task inexistente no PATCH e 404`() {
        assertEquals(404, chamar("PATCH", "/api/tasks/nao-existe").first)
    }

    @Test
    fun `DELETE task remove do banco e avisa o socket`() {
        val (_, criada) = chamar("POST", "/api/tasks", """{"titulo":"x"}""")
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(criada)!!.groupValues[1]

        val s = conectarSocket()
        val aviso = CountDownLatch(1)
        s.on("task_apagada") { aviso.countDown() }

        assertEquals(200, chamar("DELETE", "/api/tasks/$id").first)
        assertTrue("task_apagada nao chegou", aviso.await(5, TimeUnit.SECONDS))
        assertEquals(0, store.tasks().size)
        assertEquals(404, chamar("DELETE", "/api/tasks/$id").first)
        s.close()
    }

    @Test
    fun `GET tasks sai com titulo e ts em ms`() {
        chamar("POST", "/api/tasks", """{"titulo":"contrato"}""")
        val (_, texto) = chamar("GET", "/api/tasks")
        assertTrue("falta titulo", texto.contains("\"titulo\":\"contrato\""))
        assertTrue("falta texto (apelido antigo)", texto.contains("\"texto\":\"contrato\""))
        assertTrue("ts tem que sair em ms", Regex("\"ts\"\\s*:\\s*\\d{13}").containsMatchIn(texto))
    }

    // ------------------------------------------------------------- info/misc

    @Test
    fun `iso sai em texto ISO-8601 e nao em notacao cientifica`() {
        val (_, texto) = chamar("POST", "/api/chat", """{"texto":"quando"}""")
        val iso = Regex("\"iso\":\"([^\"]+)\"").find(texto)!!.groupValues[1]
        // 1.79E9 nao quebra Instant.parse; este e o contrato do relay Python
        assertTrue("iso nao parece ISO-8601: $iso", iso.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*Z")))
        java.time.Instant.parse(iso)
    }

    @Test
    fun `api info traz as contagens`() {
        chamar("POST", "/api/chat", """{"texto":"uma"}""")
        chamar("POST", "/api/tasks", """{"titulo":"uma"}""")
        val (codigo, texto) = chamar("GET", "/api/info")
        assertEquals(200, codigo)
        assertTrue(texto.contains("\"mensagens\":1"))
        assertTrue(texto.contains("\"tasks\":1"))
    }

    @Test
    fun `healthz nao exige token`() {
        // o par precisa saber se o hub esta vivo antes de ter o token
        assertEquals(200, chamar("GET", "/healthz", bearer = null).first)
    }

    @Test
    fun `backup traz tudo o que o hub tem`() {
        chamar("POST", "/api/chat", """{"texto":"para o backup"}""")
        chamar("POST", "/api/tasks", """{"titulo":"task do backup"}""")

        val (codigo, texto) = chamar("GET", "/api/backup")
        assertEquals(200, codigo)
        val j = kotlinx.serialization.json.Json.parseToJsonElement(texto).jsonObject
        assertEquals("spook-backup/1", (j["formato"] as JsonPrimitive).content)
        assertEquals(1, (j["mensagens"] as JsonArray).size)
        assertEquals(1, (j["tasks"] as JsonArray).size)
        assertEquals(
            1,
            (j["contagem"] ?: j["contagens"])!!.jsonObject["mensagens"]!!.jsonPrimitive.int,
        )
        // sem Drive, o backup tem que ser ISO-8601: e o unico carimbo de tempo
        // legivel por maquinas sem o rclone por perto
        java.time.Instant.parse((j["gerado_em"] as JsonPrimitive).content)
    }

    @Test
    fun `relatorios sai vazio em vez de 404`() {
        val (codigo, texto) = chamar("GET", "/api/relatorios")
        assertEquals(200, codigo)
        assertTrue(texto.contains("relatorios"))
    }
}
