package com.taylor.spook.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.taylor.spook.server.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * O que mantem o hub vivo: foreground service com notificacao fixa.
 *
 * Sem isto o Android mata o processo assim que a activity sai, e um servidor
 * que so roda com o app aberto nao serve para nada.
 */
class SpookService : Service() {

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var servo: Job? = null
    private lateinit var servidor: Servidor
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        servidor = Servidor.de(this)
        criarCanal()
        pegarWakeLock()
    }

    /**
     * So se o usuario pedir. O foreground service ja mantem o processo vivo;
     * o wakelock e para tela apagada com uso pesado, e come bateria a toa.
     */
    private fun pegarWakeLock() {
        if (!Ajustes.manterAcordado(this)) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SPOOK::servidor").apply {
            setReferenceCounted(false)
            acquire()
        }
        Registro.i("wakelock parcial ativo")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_PARAR) {
            pararTudo()
            return START_NOT_STICKY
        }

        // tem que ser nos primeiros 5s, senao o Android mata o service
        comecarForeground(Servidor.Estado())

        if (servo == null) {
            servo = escopo.launch {
                servidor.iniciar()
                servidor.estado.collect { atualizarNotificacao(it) }
            }
        }
        return START_STICKY
    }

    private fun comecarForeground(est: Servidor.Estado) {
        val n = notificar(est)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_NOTIFICACAO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_NOTIFICACAO, n)
        }
    }

    private fun atualizarNotificacao(est: Servidor.Estado) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(ID_NOTIFICACAO, notificar(est))
        }
    }

    private fun notificar(est: Servidor.Estado): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val parar = PendingIntent.getService(
            this, 1, Intent(this, SpookService::class.java).setAction(ACAO_PARAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val titulo = when {
            est.rodando -> "SPOOK // ON · ${est.porta}"
            est.erro != null -> "SPOOK // ERRO"
            else -> "SPOOK // subindo"
        }
        val linha = when {
            est.rodando -> "${est.ips.firstOrNull() ?: "0.0.0.0"}:${est.porta} · " +
                "${est.sessoes} sessao(oes) · ${est.mensagens} msg · ${est.tasks} task"
            est.erro != null -> est.erro
            else -> getString(R.string.notif_parado)
        }

        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat_spook)
            .setContentTitle(titulo)
            .setContentText(linha)
            .setContentIntent(abrir)
            .addAction(R.drawable.ic_stat_spook, "Parar", parar)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun criarCanal() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "SPOOK SERVER", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Estado do hub local"
                    setShowBadge(false)
                }
        )
    }

    private fun pararTudo() {
        Registro.i("service recebeu PARAR")
        servo?.cancel()
        servo = null
        // escopo proprio: dar cancel() no escopo de dentro dele mesmo ja mata a
        // corrotina antes de desligar o socket
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { servidor.desligar() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (servo?.isActive == true) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { servidor.desligar() } }
        }
        escopo.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CANAL = "spook_server"
        const val ID_NOTIFICACAO = 1001
        const val ACAO_PARAR = "com.taylor.spook.server.PARAR"
    }
}
