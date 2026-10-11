package com.taylor.spook.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Socket.IO v5 (protocol 5) - a camada superior, que viaja dentro de um
 * EngineIoPacket MESSAGE (tipo 4).
 *
 *   0 CONNECT   2 EVENT   4 CONNECT_ERROR   6 BINARY_ACK
 *   1 DISCONNECT 3 ACK     5 BINARY_EVENT
 *
 * Exemplo do app emitindo um comando:
 *   42["chat_enviar",{"texto":"oi"}]
 * Conectando com token de pareamento:
 *   40{"token":"abc"}
 */
enum class SocketIoType(val code: Char) {
    CONNECT('0'),
    DISCONNECT('1'),
    EVENT('2'),
    ACK('3'),
    CONNECT_ERROR('4'),
    BINARY_EVENT('5'),
    BINARY_ACK('6');

    companion object {
        fun from(code: Char): SocketIoType? = entries.firstOrNull { it.code == code }
    }
}

data class SocketIoPacket(
    val type: SocketIoType,
    val namespace: String = "/",
    val id: Int? = null,
    val payload: JsonElement? = null,
) {
    /** 42["evento",{...}] -> tipo EVENT, argumentos ["evento",{...}] */
    val eventName: String?
        get() = (payload as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonPrimitive)?.content }
            ?.takeIf { it != "null" }

    /** Primeiro argumento depois do nome do evento (o que o app manda). */
    val eventArgs: JsonElement?
        get() = (payload as? kotlinx.serialization.json.JsonArray)?.getOrNull(1)

/**
 * Só a parte Socket.IO: "2/chat_nova,{...}".
 * Nao prefixa o '4' do EngineIo MESSAGE - quem envelopa e toEngineIo().
 * O namespace e prefixado e SEMPRE seguido de virgula (spec v5):
 * "2/ia,["evento",...]" e "0/ia,{"sid":...}". Sem a virgula o parser
 * padrao (app, overlay, python-socketio) nao consegue ler o namespace e a
 * conexao daquele namespace aborta.
 */
    fun encode(): String = buildString {
        append(type.code)
        if (namespace != "/") append(namespace).append(',')
        id?.let { append(it) }
        payload?.let { append(protocolJson.encodeToString(JsonElement.serializer(), it)) }
    }

    fun toEngineIo(): EngineIoPacket = EngineIoPacket(EngineIoType.MESSAGE, encode())

    companion object {
        private val protocolJson = Json { ignoreUnknownKeys = true }

        /**
         * Faz o parse de "40{...}", "42[\"ev\",{}]", "43id[...]" ou "40/ia,{...}".
         * Devolve null para lixo (o cliente pode mandar coisa inesperada).
         */
        /**
         * Parse de "0", "0/ia,{...}", "2,['ev',{}]", "21/ia,['ev',{}]" (2 + ackId).
         * Recebe SO a parte Socket.IO - quem tira o '4' do EngineIo MESSAGE e o
         * chamador, via EngineIoPacket.data.
         */
        fun decode(raw: String): SocketIoPacket? {
            if (raw.isEmpty()) return null
            val type = SocketIoType.from(raw[0]) ?: return null
            var i = 1

            // no v5 o ack id vem ANTES do namespace: 2<ackId>/ns,[...]
            var ackId: Int? = null
            val digitos = StringBuilder()
            while (i < raw.length && raw[i].isDigit()) {
                digitos.append(raw[i]); i++
            }
            if (digitos.isNotEmpty()) ackId = digitos.toString().toIntOrNull()

            var namespace = "/"
            var inicioPayload = -1
            if (i < raw.length && raw[i] == '/') {
                // namespace vai ate a virgula, ao '{' ou ate o fim
                var fim = i + 1
                while (fim < raw.length && raw[fim] != ',' && raw[fim] != '{' && raw[fim] != '[') fim++
                if (fim < raw.length && raw[fim] == ',') fim++
                namespace = raw.substring(i, fim).removeSuffix(",")
                inicioPayload = fim
            } else if (i < raw.length) {
                // sem ack id nem namespace o payload comeca direto; a virgula
                // e tolerada por causa de pacotes antigos
                inicioPayload = if (raw[i] == ',') i + 1 else i
            }

            var payload: JsonElement? = null
            if (inicioPayload > 0) {
                val json = raw.substring(inicioPayload)
                if (json.isNotBlank()) {
                    payload = runCatching { protocolJson.parseToJsonElement(json) }.getOrNull() ?: return null
                }
            }
            return SocketIoPacket(type, namespace, ackId, payload)
        }

        fun event(namespace: String, name: String, vararg args: JsonElement): SocketIoPacket =
            SocketIoPacket(
                type = SocketIoType.EVENT,
                namespace = namespace,
                payload = kotlinx.serialization.json.JsonArray(
                    buildList {
                        add(JsonPrimitive(name))
                        addAll(args)
                    }
                ),
            )

        fun event(namespace: String, name: String, data: JsonObject): SocketIoPacket =
            event(namespace, name, data as JsonElement)

        fun connectAck(namespace: String, socketSid: String): SocketIoPacket =
            SocketIoPacket(
                type = SocketIoType.CONNECT,
                namespace = namespace,
                payload = JsonObject(mapOf("sid" to JsonPrimitive(socketSid))),
            )

        fun connectError(namespace: String, message: String): SocketIoPacket =
            SocketIoPacket(
                type = SocketIoType.CONNECT_ERROR,
                namespace = namespace,
                payload = JsonObject(mapOf("message" to JsonPrimitive(message))),
            )
    }
}
