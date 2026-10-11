package com.taylor.spook.core

import com.taylor.spook.core.protocol.EngineIoPacket
import com.taylor.spook.core.protocol.EngineIoType
import com.taylor.spook.core.protocol.RECORD_SEPARATOR
import com.taylor.spook.core.protocol.SocketIoPacket
import com.taylor.spook.core.protocol.SocketIoType
import com.taylor.spook.core.protocol.decodePackets
import com.taylor.spook.core.protocol.encodePackets
import com.taylor.spook.core.server.Hub
import com.taylor.spook.core.server.Session
import com.taylor.spook.core.store.SqliteStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O cliente real (org.json do jar do app) e estrito: JSON quebrado vira
 * JSONException e a conexao morre sem mensagem util. Estes testes existem
 * porque o handshake foi escrito a mao e errou.
 */
class ProtocolTest {

    @Test
    fun `handshake open e json valido para o org json`() {
        val sid = "kZbH3lPqW9xA1bC2"
        val json = buildJsonObject {
            put("sid", sid)
            put("upgrades", buildJsonArray { add("websocket") })
            put("pingInterval", 25000L)
            put("pingTimeout", 20000L)
            put("maxPayload", 1000000L)
        }
        val texto = Json.encodeToString(JsonElement.serializer(), json)
        val bruto = EngineIoPacket(EngineIoType.OPEN, texto).encode()

        assertTrue("handshake tem que comecar com 0{: $bruto", bruto.startsWith("0{"))

        val lido = EngineIoPacket.decode(bruto)!!
        assertEquals(EngineIoType.OPEN, lido.type)
        val obj = JSONObject(lido.data!!)
        assertEquals(sid, obj.getString("sid"))
        assertEquals("websocket", obj.getJSONArray("upgrades").getString(0))
        assertEquals(25000L, obj.getLong("pingInterval"))
    }

    /** Bug real: encode() prefixava o '4' do EngineIo e toEngineIo() de novo. */
    @Test
    fun `event dentro de engineio message nao duplica o 4`() {
        val pkt = SocketIoPacket.event("/", "chat_nova", buildJsonObject { put("texto", "oi") })
        val eio = pkt.toEngineIo()
        assertEquals(EngineIoType.MESSAGE, eio.type)
        assertEquals("""42["chat_nova",{"texto":"oi"}]""", eio.encode())
        assertEquals(pkt, SocketIoPacket.decode(eio.data!!))
        assertEquals(pkt.encode(), EngineIoPacket.decode(eio.encode())!!.data)
    }

    @Test
    fun `evento com namespace e lido de volta`() {
        val pkt = SocketIoPacket.event("/ia", "chat_ia_resposta", buildJsonObject { put("texto", "oi") })
        assertEquals("""2/ia,["chat_ia_resposta",{"texto":"oi"}]""", pkt.encode())
        val lido = SocketIoPacket.decode(pkt.encode())!!
        assertEquals("/ia", lido.namespace)
        assertEquals("chat_ia_resposta", lido.eventName)
        assertEquals("oi", lido.eventArgs!!.jsonObject["texto"]!!.jsonPrimitive.content)
    }

    @Test
    fun `separador de registro e o mesmo do engine io`() {
        assertEquals('\u001E', RECORD_SEPARATOR)
        val lista = listOf(
            EngineIoPacket(EngineIoType.MESSAGE, """40"""),
            EngineIoPacket(EngineIoType.NOOP),
        )
        assertEquals(lista, decodePackets(encodePackets(lista)))
    }

    @Test
    fun `connect ack e connect error`() {
        assertEquals("0", SocketIoPacket(SocketIoType.CONNECT).encode())
        assertEquals("""0/ia,{"sid":"abc"}""", SocketIoPacket.connectAck("/ia", "abc").encode())
        assertEquals("""4{"message":"nope"}""", SocketIoPacket.connectError("/", "nope").encode())

        val err = SocketIoPacket.decode("""4{"message":"nope"}""")!!
        assertEquals(SocketIoType.CONNECT_ERROR, err.type)
        assertEquals("nope", err.payload!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    /** O formato exato que o app emite: 42["chat_enviar",{...}] */
    @Test
    fun `formato igual ao que o app emite`() {
        val bruto = SocketIoPacket.decode("""2["chat_enviar",{"texto":"oi"}]""")!!
        assertEquals(SocketIoType.EVENT, bruto.type)
        assertEquals("chat_enviar", bruto.eventName)
        assertEquals("oi", bruto.eventArgs!!.jsonObject["texto"]!!.jsonPrimitive.content)

        val ack = SocketIoPacket.decode("""0{"sid":"abc"}""")!!
        assertEquals(SocketIoType.CONNECT, ack.type)
        assertEquals("abc", ack.payload!!.jsonObject["sid"]!!.jsonPrimitive.content)

        val erro = SocketIoPacket.decode("""4{"message":"token invalido"}""")!!
        assertEquals(SocketIoType.CONNECT_ERROR, erro.type)
        assertEquals("token invalido", erro.payload!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `evento sem CONNECT autenticado nao altera o banco`() {
        val store = SqliteStore(SqliteStore.IN_MEMORY)
        try {
            val hub = Hub(store = store, tokenValido = { it == "segredo" })
            val session = Session("sid-sem-auth", "127.0.0.1")
            hub.entrada(
                session,
                SocketIoPacket.event("/", "chat_enviar", buildJsonObject { put("texto", "intruso") }),
            )
            hub.entrada(
                session,
                SocketIoPacket.event("/", "task_nova", buildJsonObject { put("titulo", "intruso") }),
            )

            assertEquals(0, store.mensagens().size)
            assertEquals(0, store.tasks().size)
            val erro = session.pending.tryReceive().getOrNull()
                ?: throw AssertionError("evento sem auth deveria receber erro")
            assertEquals(SocketIoType.CONNECT_ERROR, SocketIoPacket.decode(erro.data!!)?.type)
        } finally {
            store.close()
        }
    }

    @Test
    fun `lixo nao derruba o decode`() {
        assertEquals(null, SocketIoPacket.decode(""))
        assertEquals(null, SocketIoPacket.decode("9lixo"))
        assertEquals(null, EngineIoPacket.decode(""))
    }

    /**
     * Bug real: o app perguntava `info_sistema` pro hub (que responde com a
     * info dele mesmo, no celular) e nao recebia metricas do Arch, entao o
     * card ficava RAM 0% / CPU 0.0% / Battery N/A mesmo com o Arch ligado.
     */
    @Test
    fun `metricas do Arch chegam no app e nos reconectados`() {
        val store = SqliteStore(SqliteStore.IN_MEMORY)
        try {
            val hub = Hub(
                store = store,
                tokenValido = { it == "segredo" },
                infoSistema = { buildJsonObject { put("host", "hub-celular") } },
            )

            // Arch publica como cliente
            val arch = Session("sid-arch", "100.79.55.68")
            hub.entrada(arch, SocketIoPacket(SocketIoType.CONNECT, "/", payload = buildJsonObject { put("token", "segredo") }))
            arch.pending.tryReceive() // CONNECT ack
            hub.entrada(
                arch,
                SocketIoPacket.event(
                    "/",
                    "metricas_notebook",
                    buildJsonObject {
                        put("ram_percent", 42.0)
                        put("cpu_percent", 7.5)
                    },
                ),
            )
            hub.entrada(
                arch,
                SocketIoPacket.event("/", "info_arch", buildJsonObject { put("host", "arch") }),
            )

            // Celular conecta depois: tem que receber o estado sem esperar o proximo tick
            val celular = Session("sid-celular", "192.168.0.108")
            hub.entrada(celular, SocketIoPacket(SocketIoType.CONNECT, "/", payload = buildJsonObject { put("token", "segredo") }))
            var achouMetricas = false
            var achouInfo = false
            while (true) {
                val p = celular.pending.tryReceive().getOrNull() ?: break
                val pkt = SocketIoPacket.decode(p.data!!) ?: continue
                when (pkt.eventName) {
                    "metricas_notebook" -> {
                        assertEquals(42.0, pkt.eventArgs!!.jsonObject["ram_percent"]!!.jsonPrimitive.content.toDouble(), 0.01)
                        achouMetricas = true
                    }
                    "info_sistema" -> {
                        assertEquals("arch", pkt.eventArgs!!.jsonObject["host"]!!.jsonPrimitive.content)
                        achouInfo = true
                    }
                }
            }
            assertTrue("celular deveria receber metricas_notebook do Arch", achouMetricas)
            assertTrue("celular deveria receber info_sistema do Arch", achouInfo)
        } finally {
            store.close()
        }
    }
}
