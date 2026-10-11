package com.taylor.spook.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * O hub e um servidor: ele precisa voltar sozinho depois de reiniciar o
 * celular, senao o parceiro so recupera o acesso manualmente. O interruptor
 * esta em Ajustes.autostart (default ligado).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> {
                if (!Ajustes.autostart(context)) {
                    Registro.i("boot recebido, mas autostart esta desligado")
                    return
                }
                Registro.i("boot recebido: relancando o hub")
                Servidor.iniciarService(context)
            }
        }
    }
}
