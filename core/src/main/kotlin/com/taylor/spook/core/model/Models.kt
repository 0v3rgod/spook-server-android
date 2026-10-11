package com.taylor.spook.core.model

import kotlinx.serialization.json.JsonObject

data class Mensagem(
    val id: String,
    val texto: String,
    val autor: String? = null,
    val tipo: String = "texto",
    val ts: Double,
    val extra: JsonObject? = null,
)

data class Task(
    val id: String,
    val texto: String,
    val feito: Boolean = false,
    val criado: Double,
    val extra: JsonObject? = null,
)

data class Relatorio(
    val nome: String,
    val bytes: Long,
    val ts: Double,
    val caminho: String,
    val documentos: List<String> = emptyList(),
)
