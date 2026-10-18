package com.taylor.spook.server

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.taylor.spook.core.model.Mensagem
import com.taylor.spook.core.model.Task
import com.taylor.spook.core.server.Hub
import com.taylor.spook.core.server.Ia
import com.taylor.spook.core.server.MemoriaIa
import com.taylor.spook.core.server.HubServer
import com.taylor.spook.server.dados.AndroidStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * O SPOOK SERVER do ponto de vista do app: liga/desliga o HubServer, guarda o
 * token de pareamento e publica o estado que a tela desenha.
 *
 * O :core nao sabe nada de Android -- e por isso que o mesmo Hub roda no JVM
 * (nos 14 testes) e aqui dentro do foreground service.
 */
class Servidor(private val ctx: Context) {

    data class Estado(
        val rodando: Boolean = false,
        val porta: Int = PORTA_PADRAO,
        val token: String = "",
        val ips: List<String> = emptyList(),
        val erro: String? = null,
        val sessoes: Int = 0,
        val mensagens: Int = 0,
        val tasks: Int = 0,
        val desde: Long? = null,
    )

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado

    private val trava = Mutex()
    private var store: AndroidStore? = null
    private var hub: Hub? = null
    private var servidor: HubServer? = null
    private val prefs get() = ctx.getSharedPreferences("spook", Context.MODE_PRIVATE)

    /**
     * Chave/modelo da IA para o namespace /ia. Ficam nas prefs do hub (a tela
     * de Ajustes grava); se ainda nao houver nada, a IA responde "nao
     * configurada" em vez de derrubar o socket.
     */
    private val chaveGroq: String get() = Ajustes.chaveIa(ctx)
    private val modeloGroq: String get() = Ajustes.modeloIa(ctx)

    /**
     * Token de pareamento: 32 hex, gerado uma vez e guardado em SharedPreferences
     * para o parceiro nao perder o pareamento quando o service reinicia (ele so
     * conhece este valor).
     */
    val token: String
        get() = prefs.getString("token", null) ?: ByteArray(16)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString("token", it).apply() }

    /** Hash da senha do painel web (PBKDF2-HMAC-SHA256, 100k iters, salt 16 bytes). */
    var senhaHash: String?
        get() = prefs.getString("senha_hash", null)
        set(value) {
            prefs.edit().putString("senha_hash", value).apply()
        }

    /** Verifica senha contra hash armazenado. */
    fun verificarSenha(senha: String): Boolean {
        val hash = senhaHash ?: return false
        val partes = hash.split(":")
        if (partes.size != 3) return false
        val iters = partes[0].toIntOrNull() ?: return false
        val salt = hexToBytes(partes[1])
        val hashBytes = hexToBytes(partes[2])
        val calc = gerarHash(senha, salt, iters)
        return MessageDigest.isEqual(calc, hashBytes)
    }

    /** Define nova senha: gera salt aleatorio e guarda hash.
     * Senha vazia = remove a senha (desabilita login por senha). */
    fun definirSenha(senha: String) {
        if (senha.isBlank()) {
            prefs.edit().remove("senha_hash").apply()
            return
        }
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val iters = 100_000
        val hash = gerarHash(senha, salt, iters)
        senhaHash = "$iters:${bytesToHex(salt)}:${bytesToHex(hash)}"
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in 0 until out.size) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun gerarHash(senha: String, salt: ByteArray, iters: Int): ByteArray {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(senha.toCharArray(), salt, iters, 256)
        return factory.generateSecret(spec).encoded
    }

    val porta: Int get() = prefs.getInt("porta", PORTA_PADRAO)

    // ------------------------------------------------------------------ ligar

    suspend fun iniciar(portaDesejada: Int = porta): Unit = trava.withLock {
        if (servidor != null) {
            Registro.i("ja estava ligado; ignorando")
            return@withLock
        }
        try {
            withContext(Dispatchers.IO) {
                Registro.i("abrindo banco local (fonte da verdade)")
                val s = Banco.abrir(ctx)
                store = s

                val mensagens = s.mensagens().size
                val tasks = s.tasks().size
                Registro.i("banco pronto: $mensagens mensagens, $tasks tasks")

                val h = Hub(
                    store = s,
                    tokenValido = { it == token },
                    // Lambdas que reavalia: o snapshot fixo frozenava bateria e
                    // uptime no valor do startup (uptime_s saia sempre 0).
                    infoSistema = { infoSistema() },
                    verificarSenha = { senha -> if (verificarSenha(senha)) token else null },
                    definirSenha = { senha -> definirSenha(senha) },
                    ia = Ia(chaveGroq, modeloGroq),
                    memoria = MemoriaIa(File(ctx.filesDir, "memoria_ia"), Json { ignoreUnknownKeys = true }),
                )
                hub = h

                Registro.i("subindo Engine.IO v4 em 0.0.0.0:$portaDesejada")
                val srv = HubServer(hub = h, port = portaDesejada, host = "0.0.0.0").iniciar()
                servidor = srv

                val efetiva = srv.portaEfetiva()
                val ips = ipsLocais()
                Registro.i("ouvindo em ${ips.joinToString(", ")}:$efetiva (EIO=4)")
                Registro.i("pareamento: token ${token.take(8)}... (32 hex)")

                prefs.edit().putInt("porta", efetiva).apply()

                _estado.value = Estado(
                    rodando = true,
                    porta = efetiva,
                    token = token,
                    ips = ips,
                    sessoes = h.sessoes.size,
                    mensagens = mensagens,
                    tasks = tasks,
                    desde = System.currentTimeMillis(),
                )
            }
        } catch (e: Throwable) {
            val erro = "${e::class.java.simpleName}: ${e.message}"
            Registro.e("falha ao subir: $erro")
            _estado.value = _estado.value.copy(rodando = false, erro = erro)
        }
    }

    suspend fun desligar(): Unit = trava.withLock {
        withContext(Dispatchers.IO) {
            try {
                servidor?.parar()
                Registro.i("engine.io parado")
            } catch (e: Throwable) {
                Registro.e("erro ao parar: ${e.message}")
            }
            runCatching { hub?.sessoes?.clear() }
            hub = null
            store = null
            servidor = null
            _estado.value = Estado(token = token)
            Registro.i("SPOOK SERVER desligado")
        }
    }

    suspend fun reiniciar() {
        desligar()
        iniciar()
    }

    // ----------------------------------------------------------------- estado

    /** Chamado pelo service a cada segundo, para a tela nao ficar parada. */
    suspend fun atualizarContagens() {
        val h = hub ?: return
        val s = store ?: return
        val (m, t) = withContext(Dispatchers.IO) { s.mensagens().size to s.tasks().size }
        _estado.value = _estado.value.copy(
            sessoes = h.sessoes.size,
            mensagens = m,
            tasks = t,
        )
    }

    /** Escreve no chat local e ecoa para quem estiver conectado. */
    suspend fun mandarMensagem(texto: String, autor: String = "servidor"): Boolean {
        val h = hub ?: return false
        val m = Mensagem(
            id = UUID.randomUUID().toString(),
            texto = texto,
            autor = autor,
            ts = System.currentTimeMillis() / 1000.0,
        )
        withContext(Dispatchers.IO) {
            store?.inserirMensagem(m)
            h.broadcast(namespace = "/", event = "chat_nova", data = serializado(m))
        }
        Registro.i("chat: mensagem de '$autor' gravada")
        return true
    }

    suspend fun addTask(texto: String): Boolean =
        addTask(
            Task(
                id = UUID.randomUUID().toString(),
                texto = texto,
                criado = System.currentTimeMillis() / 1000.0,
            )
        )

    /**
     * Grava a task que o chamador ja montou. Sem esse overload a tela inseria
     * a task no Banco E depois criava outra via addTask(texto): o usuario via
     * duas tarefas identicais, com ids diferentes.
     */
    suspend fun addTask(t: Task): Boolean {
        withContext(Dispatchers.IO) {
            store?.inserirTask(t)
            hub?.broadcast(namespace = "/", event = "task_nova", data = serializado(t))
        }
        Registro.i("task criada: ${t.texto.take(40)}")
        return true
    }

    /**
     * Apagar/marcarfeito pela tela local: escreve no banco E avisa quem esta
     * conectado, senao o outro lado so descobre na proxima task_listar.
     */
    suspend fun apagarMensagem(id: String): Boolean {
        val ok = withContext(Dispatchers.IO) { store?.apagarMensagem(id) == true }
        if (ok) {
            val aviso = buildJsonObject { put("id", id) }
            hub?.broadcast(namespace = "/", event = "chat_apagada", data = aviso)
        }
        return ok
    }

    suspend fun alternarTask(id: String): Boolean {
        val atual = withContext(Dispatchers.IO) { store?.task(id) } ?: return false
        val nova = atual.copy(feito = !atual.feito)
        withContext(Dispatchers.IO) { store?.atualizarTask(nova) }
        hub?.broadcast(namespace = "/", event = "task_atualizada", data = serializado(nova))
        Registro.i("task ${if (nova.feito) "concluida" else "reaberta"}: ${nova.texto.take(30)}")
        return true
    }

    suspend fun apagarTask(id: String): Boolean {
        val ok = withContext(Dispatchers.IO) { store?.apagarTask(id) == true }
        if (ok) {
            val aviso = buildJsonObject { put("id", id) }
            hub?.broadcast(namespace = "/", event = "task_apagada", data = aviso)
        }
        return ok
    }

    // ------------------------------------------------------- info do sistema

    fun infoSistema(): JsonObject = buildJsonObject {
        put("host", "SPOOK SERVER (Android ${Build.VERSION.RELEASE})")
        put("modelo", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("so", Build.VERSION.RELEASE)
        put("android_sdk", Build.VERSION.SDK_INT)
        put("app", VERSAO)
        put("bateria_pct", bateriaPct())
        put("carregando", carregando())
        put("rede", tipoRede())
        put("ips", buildJsonArray { ipsLocais().forEach { add(it) } })
        put("fuso", java.util.TimeZone.getDefault().id)
        put("uptime_s", _estado.value.desde?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L)
    }

    fun ipsLocais(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList() }
            .filterNot { it.isLoopbackAddress }
            .filterNot { it.hostAddress.orEmpty().startsWith("127.") }
            .filterNot { it.hostAddress.orEmpty().contains(":") } // sem IPv6 ainda
            .mapNotNull { it.hostAddress }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private fun bateriaPct(): Int {
        // BatteryManager.BATTERY_STATUS e @hide; o jeito publico de ler estado
        // de carga e o sticky broadcast ACTION_BATTERY_CHANGED.
        val i = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (escala > 0) nivel * 100 / escala else -1
    }

    private fun carregando(): Boolean {
        val i = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        return when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            -> true
            else -> false
        }
    }

    private fun tipoRede(): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "?"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "celular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "outra"
        }
    }

    /**
     * Backup local sem depender de Drive: monta o MESMO JSON do /api/backup,
     * direto do store e com os mesmos serializadores do Hub, e grava em
     * getExternalFilesDir("backup") -- que o adb pull pega sem root. Em
     * getFilesDir (privado) ninguem copia sem run-as.
     */
    suspend fun gerarBackup(ctx: Context): String? = withContext(Dispatchers.IO) {
        val h = hub ?: return@withContext null
        val ms = store?.mensagens().orEmpty()
        val ts = store?.tasks().orEmpty()
        val agora = System.currentTimeMillis() / 1000.0
        val doc = buildJsonObject {
            put("formato", "spook-backup/1")
            put("gerado_em", h.iso(agora))
            put("servidor", "SPOOK SERVER")
            put("contagens", buildJsonObject {
                put("mensagens", ms.size)
                put("tasks", ts.size)
            })
            put("mensagens", buildJsonArray { ms.forEach { add(h.mensagemJson(it)) } })
            put("tasks", buildJsonArray { ts.forEach { add(h.taskJson(it)) } })
        }.toString()

        val dir = ctx.getExternalFilesDir("backup") ?: return@withContext null
        dir.mkdirs()
        val arq = java.io.File(dir, "spook-backup-${System.currentTimeMillis()}.json")
        arq.writeText(doc)
        Registro.i("backup gravado: ${arq.name} (${arq.length()} bytes)")
        arq.absolutePath
    }

    /** Mesma coisa que infoSistema(), mas em pares para a tela listar. */
    fun infoLegivel(): List<Pair<String, String>> {
        val j = infoSistema()
        fun s(k: String) = (j[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
        return listOf(
            "host" to s("host"),
            "modelo" to s("modelo"),
            "android" to s("so"),
            "sdk" to s("android_sdk"),
            "versao" to s("app"),
            "bateria" to "${s("bateria_pct")}%",
            "carregando" to s("carregando"),
            "rede" to s("rede"),
            "fuso" to s("fuso"),
            "porta" to _estado.value.porta.toString(),
            "token" to token.take(8) + "...",
        )
    }

    /**
     * Reaproveita o serializador do Hub em vez de reescrever: antes o app
     * mandava ts em segundos (1.79E9, notacao cientifica) e sem "iso", enquanto
     * o REST mandava ts em ms. O mesmo cliente via socket e via REST lia
     * formatos diferentes.
     */
    fun serializado(m: Mensagem): JsonObject =
        hub?.mensagemJson(m) ?: buildJsonObject {
            put("id", m.id)
            put("texto", m.texto)
            put("autor", m.autor ?: "")
            put("tipo", m.tipo)
            put("iso", isoTexto(m.ts))
            put("ts", (m.ts * 1000).toLong())
        }

    /** ISO-8601 UTC em texto: e o que o PentestLink e o RelatoriosClient leem. */
    private fun isoTexto(epochSegundos: Double): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT.format(
            java.time.Instant.ofEpochMilli((epochSegundos * 1000).toLong())
        )

    fun serializado(t: Task): JsonObject = hub?.taskJson(t) ?: buildJsonObject {
        // mesmo formato da taskJson do Hub: o app le "titulo" e "ts"
        put("id", t.id)
        put("titulo", t.texto)
        put("texto", t.texto)
        put("feito", t.feito)
        put("iso", isoTexto(t.criado))
        put("ts", (t.criado * 1000).toLong())
    }

    companion object {
        const val VERSAO = "0.3.1-launcher"
        const val PORTA_PADRAO = 5000

        @Volatile
        private var instancia: Servidor? = null

        /** Um servidor por processo; a activity e o service compartilham. */
        fun de(ctx: Context): Servidor = instancia ?: synchronized(this) {
            instancia ?: Servidor(ctx.applicationContext).also { instancia = it }
        }

        fun iniciarService(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SpookService::class.java))
        }
    }
}
