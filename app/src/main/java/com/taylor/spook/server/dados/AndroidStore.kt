package com.taylor.spook.server.dados

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.taylor.spook.core.model.Mensagem
import com.taylor.spook.core.model.Relatorio
import com.taylor.spook.core.model.Task
import com.taylor.spook.core.store.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A fonte da verdade do SPOOK SERVER no Android.
 *
 * O :core tem um SqliteStore (JDBC) para o JVM/testes; aqui a mesma interface
 * `Store` em cima do SQLite do proprio Android, porque o driver JDBC nao
 * existe na plataforma. O schema e identico -- os dois bancos leem um do outro.
 *
 * Tudo e sincrono (SQLite nao likes main thread); o servidor chama por dentro
 * de Dispatchers.IO.
 */
class AndroidStore(context: Context) : Store {

    private val json = Json { ignoreUnknownKeys = true }
    private val helper = Ajuda(context.applicationContext)

    private class Ajuda(ctx: Context) :
        SQLiteOpenHelper(ctx, BANCO, null, VERSAO) {

        init {
            // WAL pela API do helper: enableWriteAheadLogging() NAO pode ser
            // chamado de dentro de onConfigure (o Android recusa). O parceiro
            // lendo enquanto o chat insere sem "database is locked" e o motivo.
            setWriteAheadLoggingEnabled(true)
        }

        // SEM onConfigure de proposito: PRAGMA como "synchronous" e
        // "busy_timeout" devolvem linha, e execSQL no Android recusa statement
        // que devolve dados ("Queries can be performed using ... rawQuery only").
        // No JDBC o st.execute() tolerava; aqui quebrava o boot do hub.
        // WAL vem do setWriteAheadLoggingEnabled e o busy timeout e interno do
        // pool de conexoes do Android -- nao ha setter publico.

        override fun onCreate(db: SQLiteDatabase) {
            // mesmas DDL do SqliteStore do :core
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat (
                    id    TEXT PRIMARY KEY,
                    texto TEXT NOT NULL,
                    autor TEXT,
                    tipo  TEXT NOT NULL DEFAULT 'texto',
                    ts    REAL NOT NULL,
                    extra TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS chat_ts ON chat(ts)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id     TEXT PRIMARY KEY,
                    texto  TEXT NOT NULL,
                    feito  INTEGER NOT NULL DEFAULT 0,
                    criado REAL NOT NULL,
                    extra  TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS relatorios (
                    nome       TEXT PRIMARY KEY,
                    bytes      INTEGER NOT NULL DEFAULT 0,
                    ts         REAL NOT NULL,
                    caminho    TEXT NOT NULL,
                    documentos TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {
            // prototipo: sem migracao ainda. O schema so cresce.
        }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // -------------------------------------------------------------- auxiliares

    private fun JsonObject?.serializado(): String? =
        this?.let { json.encodeToString(JsonObject.serializer(), it) }

    private fun lerExtra(bruto: String?): JsonObject? =
        bruto?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }

    private inline fun <T> ler(sql: String, onde: (android.database.Cursor) -> T): T =
        db.rawQuery(sql, null).use { c -> onde(c) }

    // ------------------------------------------------------------------- chat

    override fun mensagens(): List<Mensagem> = ler(
        "SELECT id, texto, autor, tipo, ts, extra FROM chat ORDER BY ts ASC"
    ) { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Mensagem(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        texto = c.getString(c.getColumnIndexOrThrow("texto")),
                        autor = c.getString(c.getColumnIndexOrThrow("autor")),
                        tipo = c.getString(c.getColumnIndexOrThrow("tipo")),
                        ts = c.getDouble(c.getColumnIndexOrThrow("ts")),
                        extra = lerExtra(c.getString(c.getColumnIndexOrThrow("extra"))),
                    )
                )
            }
        }
    }

    override fun mensagem(id: String): Mensagem? = mensagens().firstOrNull { it.id == id }

    override fun inserirMensagem(m: Mensagem) {
        db.compileStatement(
            "INSERT OR REPLACE INTO chat (id, texto, autor, tipo, ts, extra) VALUES (?,?,?,?,?,?)"
        ).use { st ->
            st.bindString(1, m.id)
            st.bindString(2, m.texto)
            m.autor?.let { st.bindString(3, it) } ?: st.bindNull(3)
            st.bindString(4, m.tipo)
            st.bindDouble(5, m.ts)
            m.extra.serializado()?.let { st.bindString(6, it) } ?: st.bindNull(6)
            st.executeInsert()
        }
    }

    override fun apagarMensagem(id: String): Boolean =
        db.compileStatement("DELETE FROM chat WHERE id = ?").use { st ->
            st.bindString(1, id)
            st.executeUpdateDelete() > 0
        }

    // ------------------------------------------------------------------ tasks

    override fun tasks(): List<Task> = ler(
        "SELECT id, texto, feito, criado, extra FROM tasks ORDER BY criado ASC"
    ) { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Task(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        texto = c.getString(c.getColumnIndexOrThrow("texto")),
                        feito = c.getInt(c.getColumnIndexOrThrow("feito")) == 1,
                        criado = c.getDouble(c.getColumnIndexOrThrow("criado")),
                        extra = lerExtra(c.getString(c.getColumnIndexOrThrow("extra"))),
                    )
                )
            }
        }
    }

    override fun task(id: String): Task? = tasks().firstOrNull { it.id == id }

    override fun inserirTask(t: Task) = gravarTask(t)

    override fun atualizarTask(t: Task) = gravarTask(t)

    private fun gravarTask(t: Task) {
        db.compileStatement(
            "INSERT OR REPLACE INTO tasks (id, texto, feito, criado, extra) VALUES (?,?,?,?,?)"
        ).use { st ->
            st.bindString(1, t.id)
            st.bindString(2, t.texto)
            st.bindLong(3, if (t.feito) 1 else 0)
            st.bindDouble(4, t.criado)
            t.extra.serializado()?.let { st.bindString(5, it) } ?: st.bindNull(5)
            st.executeInsert()
        }
    }

    override fun apagarTask(id: String): Boolean =
        db.compileStatement("DELETE FROM tasks WHERE id = ?").use { st ->
            st.bindString(1, id)
            st.executeUpdateDelete() > 0
        }

    // ------------------------------------------------------------- relatorios

    override fun relatorios(): List<Relatorio> = ler(
        "SELECT nome, bytes, ts, caminho, documentos FROM relatorios ORDER BY ts DESC"
    ) { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Relatorio(
                        nome = c.getString(c.getColumnIndexOrThrow("nome")),
                        bytes = c.getLong(c.getColumnIndexOrThrow("bytes")),
                        ts = c.getDouble(c.getColumnIndexOrThrow("ts")),
                        caminho = c.getString(c.getColumnIndexOrThrow("caminho")),
                        documentos = c.getString(c.getColumnIndexOrThrow("documentos"))
                            .split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    )
                )
            }
        }
    }

    override fun registrarRelatorio(r: Relatorio) {
        db.compileStatement(
            "INSERT OR REPLACE INTO relatorios (nome, bytes, ts, caminho, documentos) VALUES (?,?,?,?,?)"
        ).use { st ->
            st.bindString(1, r.nome)
            st.bindLong(2, r.bytes)
            st.bindDouble(3, r.ts)
            st.bindString(4, r.caminho)
            st.bindString(5, r.documentos.joinToString(","))
            st.executeInsert()
        }
    }

    override fun apagarRelatorio(nome: String): Boolean =
        db.compileStatement("DELETE FROM relatorios WHERE nome = ?").use { st ->
            st.bindString(1, nome)
            st.executeUpdateDelete() > 0
        }

    override fun close() {
        helper.close()
    }

    private companion object {
        const val BANCO = "spook.db"
        const val VERSAO = 1
    }
}
