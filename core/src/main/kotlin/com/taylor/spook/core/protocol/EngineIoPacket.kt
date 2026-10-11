package com.taylor.spook.core.protocol

/**
 * Engine.IO v4 (EIO=4) - a camada inferior.
 *
 * O cliente do app (io.socket:socket.io-client:2.1.0 -> engine.io-client:2.1.0)
 * fala EIO=4, confirmado por decompile do jar. Tipos de pacote:
 *
 *   0 OPEN    2 PING    4 MESSAGE   6 NOOP      (servidor -> cliente)
 *   1 CLOSE   3 PONG    5 UPGRADE               (cliente -> servidor, 1/3 nos dois sentidos)
 *
 * NO POLLING os pacotes sao unidos por RECORD SEPARATOR (0x1E).
 */
const val RECORD_SEPARATOR: Char = '\u001E'

enum class EngineIoType(val code: Char) {
    OPEN('0'),
    CLOSE('1'),
    PING('2'),
    PONG('3'),
    MESSAGE('4'),
    UPGRADE('5'),
    NOOP('6');

    companion object {
        fun from(code: Char): EngineIoType? = entries.firstOrNull { it.code == code }
    }
}

data class EngineIoPacket(val type: EngineIoType, val data: String? = null) {

    fun encode(): String = buildString {
        append(type.code)
        data?.let { append(it) }
    }

    companion object {
        fun decode(raw: String): EngineIoPacket? {
            if (raw.isEmpty()) return null
            val type = EngineIoType.from(raw[0]) ?: return null
            val data = raw.substring(1).ifEmpty { null }
            return EngineIoPacket(type, data)
        }
    }
}

fun encodePackets(packets: List<EngineIoPacket>): String =
    packets.joinToString(RECORD_SEPARATOR.toString()) { it.encode() }

fun decodePackets(body: String): List<EngineIoPacket> =
    body.split(RECORD_SEPARATOR).mapNotNull { EngineIoPacket.decode(it) }
