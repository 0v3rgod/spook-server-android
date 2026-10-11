package com.taylor.spook.server

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Log do servidor em dois lugares: Logcat (TAG SPOOK) e um buffer de ring
 * que a tela mostra. O :core loga via slf4j, que no Android fica sem provider
 * (o logback e JVM e foi excluido do APK) -- entao quem instrumenta o
 * ciclo de vida do hub e este objeto.
 */
object Registro {

    const val TAG = "SPOOK"
    private const val LIMITE = 400

    data class Linha(val quando: Long, val nivel: String, val texto: String)

    private val _linhas = MutableStateFlow<List<Linha>>(emptyList())
    val linhas: StateFlow<List<Linha>> = _linhas

    fun i(texto: String) = add("I", texto)
    fun d(texto: String) = add("D", texto)
    fun e(texto: String) = add("E", texto)

    private fun add(nivel: String, texto: String) {
        val prioridade = when (nivel) {
            "E" -> Log.ERROR
            "W" -> Log.WARN
            else -> Log.INFO
        }
        Log.println(prioridade, TAG, texto)
        _linhas.value = (_linhas.value + Linha(System.currentTimeMillis(), nivel, texto))
            .takeLast(LIMITE)
    }

    fun limpar() {
        _linhas.value = emptyList()
    }
}
