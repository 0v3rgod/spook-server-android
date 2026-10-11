package com.taylor.spook.core.store

import com.taylor.spook.core.model.Mensagem
import com.taylor.spook.core.model.Relatorio
import com.taylor.spook.core.model.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Store em SQLite (WAL). Roda igual no JVM (testes, Arch) e no Android.
 *
 * Um arquivo por mensagem/task era o que o Drive fazia; aqui tudo vira linha.
 * Isso mata de uma vez a corrida de read-modify-write do `tasks.json`, que era
 * a causa do bug "marcar uma task fazia as outras sumirem".
 */
class SqliteStore(path: String) : Store {

    private val json = Json { ignoreUnknownKeys = true }
    private val conn: Connection

    init {
        if (path != IN_MEMORY) {
            File(path).parentFile?.mkdirs()
        }
        DriverManager.getDriver("jdbc:sqlite:$path")
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.autoCommit = true
        if (path != IN_MEMORY) {
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("PRAGMA busy_timeout=5000")
            }
        }
        migrar()
    }

    private fun migrar() {
        conn.createStatement().use { st ->
            st.executeUpdate(
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
            st.executeUpdate("CREATE INDEX IF NOT EXISTS chat_ts ON chat(ts)")
            st.executeUpdate(
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
            st.executeUpdate(
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
    }

    private fun JsonObject?.serializado(): String? =
        this?.let { json.encodeToString(JsonObject.serializer(), it) }

    private fun lerExtra(bruto: String?): JsonObject? =
        bruto?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }

    // ------------------------------------------------------------------ chat

    override fun mensagens(): List<Mensagem> =
        conn.prepareStatement("SELECT id, texto, autor, tipo, ts, extra FROM chat ORDER BY ts ASC").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Mensagem(
                                id = rs.getString("id"),
                                texto = rs.getString("texto"),
                                autor = rs.getString("autor"),
                                tipo = rs.getString("tipo"),
                                ts = rs.getDouble("ts"),
                                extra = lerExtra(rs.getString("extra")),
                            )
                        )
                    }
                }
            }
        }

    override fun mensagem(id: String): Mensagem? = mensagens().firstOrNull { it.id == id }

    override fun inserirMensagem(m: Mensagem) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO chat (id, texto, autor, tipo, ts, extra) VALUES (?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, m.id)
            ps.setString(2, m.texto)
            ps.setString(3, m.autor)
            ps.setString(4, m.tipo)
            ps.setDouble(5, m.ts)
            ps.setString(6, m.extra.serializado())
            ps.executeUpdate()
        }
    }

    override fun apagarMensagem(id: String): Boolean =
        conn.prepareStatement("DELETE FROM chat WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate() > 0
        }

    // ----------------------------------------------------------------- tasks

    override fun tasks(): List<Task> =
        conn.prepareStatement("SELECT id, texto, feito, criado, extra FROM tasks ORDER BY criado ASC").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Task(
                                id = rs.getString("id"),
                                texto = rs.getString("texto"),
                                feito = rs.getInt("feito") == 1,
                                criado = rs.getDouble("criado"),
                                extra = lerExtra(rs.getString("extra")),
                            )
                        )
                    }
                }
            }
        }

    override fun task(id: String): Task? = tasks().firstOrNull { it.id == id }

    override fun inserirTask(t: Task) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO tasks (id, texto, feito, criado, extra) VALUES (?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, t.id)
            ps.setString(2, t.texto)
            ps.setInt(3, if (t.feito) 1 else 0)
            ps.setDouble(4, t.criado)
            ps.setString(5, t.extra.serializado())
            ps.executeUpdate()
        }
    }

    override fun atualizarTask(t: Task) = inserirTask(t)

    override fun apagarTask(id: String): Boolean =
        conn.prepareStatement("DELETE FROM tasks WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate() > 0
        }

    // ------------------------------------------------------------ relatorios

    override fun relatorios(): List<Relatorio> =
        conn.prepareStatement("SELECT nome, bytes, ts, caminho, documentos FROM relatorios ORDER BY ts DESC").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Relatorio(
                                nome = rs.getString("nome"),
                                bytes = rs.getLong("bytes"),
                                ts = rs.getDouble("ts"),
                                caminho = rs.getString("caminho"),
                                documentos = rs.getString("documentos")
                                    .split(',')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() },
                            )
                        )
                    }
                }
            }
        }

    override fun registrarRelatorio(r: Relatorio) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO relatorios (nome, bytes, ts, caminho, documentos) VALUES (?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, r.nome)
            ps.setLong(2, r.bytes)
            ps.setDouble(3, r.ts)
            ps.setString(4, r.caminho)
            ps.setString(5, r.documentos.joinToString(","))
            ps.executeUpdate()
        }
    }

    override fun apagarRelatorio(nome: String): Boolean =
        conn.prepareStatement("DELETE FROM relatorios WHERE nome = ?").use { ps ->
            ps.setString(1, nome)
            ps.executeUpdate() > 0
        }

    override fun close() = conn.close()

    companion object {
        const val IN_MEMORY = ":memory:"
    }
}
