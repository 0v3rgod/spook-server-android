package com.taylor.spook.core.server

import com.taylor.spook.core.protocol.EngineIoPacket
import com.taylor.spook.core.protocol.SocketIoPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement

/**
 * Uma conexao. Cuida do estado do Engine.IO (sid), dos namespaces Socket.IO
 * ja autenticados e do writer atual (polling -> websocket apos o upgrade).
 */
class Session(val engineSid: String, val remote: String) {

    /** nomespaced socket ids por namespace ("/", "/ia") */
    private val namespaces = ConcurrentHashMap<String, String>()
    private val socketSeq = AtomicInteger(0)

    /** buffer usado enquanto o transporte ainda e polling */
    val pending = Channel<EngineIoPacket>(CAPACIDADE_FILA)

    /** destino atual dos pacotes. Vira o websocket depois do probe/upgrade. */
    @Volatile
    var writer: (EngineIoPacket) -> Unit = { packet -> pending.trySend(packet) }

    @Volatile
    var handshakeSent = false
        private set

    @Volatile
    var upgraded = false
        private set

    @Volatile
    var token: String? = null
        private set

    fun send(packet: EngineIoPacket) = writer(packet)

    fun markHandshakeSent() {
        handshakeSent = true
    }

    fun switchToWebSocket(send: (EngineIoPacket) -> Unit) {
        upgraded = true
        writer = send
    }

    fun authenticate(tok: String) {
        token = tok
    }

    fun socketSid(namespace: String): String = namespaces.getOrPut(namespace) {
        "s${socketSeq.incrementAndGet()}.${engineSid.takeLast(6)}"
    }

    fun isConnectedTo(namespace: String): Boolean = namespaces.containsKey(namespace)

    fun isAuthenticated(namespace: String): Boolean = token != null && isConnectedTo(namespace)

    fun disconnectNamespace(namespace: String) {
        namespaces.remove(namespace)
    }

    fun emit(namespace: String, event: String, data: JsonElement) {
        if (!isConnectedTo(namespace)) return
        send(SocketIoPacket.event(namespace, event, data).toEngineIo())
    }

    fun close() {
        pending.close()
    }

    private companion object {
        const val CAPACIDADE_FILA = 128
    }
}
