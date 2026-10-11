package com.taylor.spook.server

import android.content.Context
import com.taylor.spook.core.model.Mensagem
import com.taylor.spook.core.model.Task
import com.taylor.spook.server.dados.AndroidStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * O banco vive fora do Servidor de proposito: a tela precisa ler e escrever
 * com o hub PARADO (marcar task offline), e o Servidor precisa do mesmo banco
 * quando sobe. Duas instancias do SQLiteOpenHelper no mesmo arquivo e
 * exatamente o tipo de coisa que da "database is locked".
 */
object Banco {

    @Volatile
    private var instancia: AndroidStore? = null

    /** Definido no SpookApp.onCreate; permite os atalhos sem passar Context. */
    lateinit var contexto: Context
        private set

    fun iniciar(ctx: Context) {
        contexto = ctx.applicationContext
    }

    fun abrir(ctx: Context): AndroidStore = instancia ?: synchronized(this) {
        instancia ?: AndroidStore(ctx.applicationContext).also { instancia = it }
    }

    // ------------------------------------------------- atalhos para a tela

    suspend fun mensagens(): List<Mensagem> =
        withContext(Dispatchers.IO) { abrir(contexto).mensagens() }

    suspend fun apagarMensagem(id: String): Boolean =
        withContext(Dispatchers.IO) { abrir(contexto).apagarMensagem(id) }

    suspend fun tasks(): List<Task> =
        withContext(Dispatchers.IO) { abrir(contexto).tasks() }

    suspend fun inserirTask(t: Task): Unit =
        withContext(Dispatchers.IO) { abrir(contexto).inserirTask(t) }

    suspend fun apagarTask(id: String): Boolean =
        withContext(Dispatchers.IO) { abrir(contexto).apagarTask(id) }
}
