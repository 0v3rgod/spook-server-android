package com.taylor.spook.core.store

import com.taylor.spook.core.model.Mensagem
import com.taylor.spook.core.model.Relatorio
import com.taylor.spook.core.model.Task

/**
 * Fonte da verdade do SPOOK SERVER. No celular isto e SQLite local; o Drive
 * (ou o relay/Arch) sao apenas espelhos opcionais, nunca o master.
 */
interface Store : AutoCloseable {

    // ---- chat (1 arquivo/1 registro por mensagem, como o Drive espelhava) ----
    fun mensagens(): List<Mensagem>
    fun mensagem(id: String): Mensagem?
    fun inserirMensagem(m: Mensagem)
    fun apagarMensagem(id: String): Boolean

    // ---- tasks ----
    fun tasks(): List<Task>
    fun task(id: String): Task?
    fun inserirTask(t: Task)
    fun atualizarTask(t: Task)
    fun apagarTask(id: String): Boolean

    // ---- relatorios ----
    fun relatorios(): List<Relatorio>
    fun registrarRelatorio(r: Relatorio)
    fun apagarRelatorio(nome: String): Boolean
}
