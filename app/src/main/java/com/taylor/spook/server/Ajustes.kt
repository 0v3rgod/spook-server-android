package com.taylor.spook.server

import android.content.Context

/**
 * Configuracao do hub, toda em SharedPreferences ("spook").
 *
 * Fica separado do banco de proposito: o banco e dado (fonte da verdade), isto
 * e Setting (como o servidor liga).
 */
object Ajustes {

    private const val PREFS = "spook"
    private const val K_PORTA = "porta"
    private const val K_AUTOSTART = "autostart"
    private const val K_ACORDADO = "acordado"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ porta

    fun porta(ctx: Context): Int = prefs(ctx).getInt(K_PORTA, Servidor.PORTA_PADRAO)

    fun salvarPorta(ctx: Context, porta: Int) {
        prefs(ctx).edit().putInt(K_PORTA, porta).apply()
    }

    // ------------------------------------------------------------- autostart
    //
    // Default true: um servidor que nao volta sozinho depois de reiniciar o
    // celular obriga o usuario a abrir o app toda vez -- e o oposto do que o
    // SPOOK SERVER promete.

    fun autostart(ctx: Context): Boolean = prefs(ctx).getBoolean(K_AUTOSTART, true)

    fun setAutostart(ctx: Context, ligado: Boolean) {
        prefs(ctx).edit().putBoolean(K_AUTOSTART, ligado).apply()
    }

    // ------------------------------------------------------- wakelock parcial
    //
    // Default false: mantem a CPU acordada o tempo todo e come bateria. O
    // foreground service ja mantem o processo vivo; isto e so para o caso de
    // tela apagada E uso pesado.

    fun manterAcordado(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ACORDADO, false)

    fun setManterAcordado(ctx: Context, ligado: Boolean) {
        prefs(ctx).edit().putBoolean(K_ACORDADO, ligado).apply()
    }

    // -------------------------------------------------------------------- IA
    //
    // Chave e modelo do Groq para o namespace /ia. Guardados aqui (e nao no
    // codigo) porque a chave muda: se vazar no binario, todo APK distributions
    // passa a expor. Vazio = IA responde "nao configurada", sem derrubar o hub.

    private const val K_IA_CHAVE = "ia_chave"
    private const val K_IA_MODELO = "ia_modelo"
    const val MODELO_IA_PADRAO = "openai/gpt-oss-20b"

    fun chaveIa(ctx: Context): String = prefs(ctx).getString(K_IA_CHAVE, "") ?: ""

    fun modeloIa(ctx: Context): String =
        prefs(ctx).getString(K_IA_MODELO, MODELO_IA_PADRAO)
            ?.takeIf { it.isNotBlank() } ?: MODELO_IA_PADRAO

    fun salvarIa(ctx: Context, chave: String, modelo: String) {
        prefs(ctx).edit()
            .putString(K_IA_CHAVE, chave.trim())
            .putString(K_IA_MODELO, modelo.trim().ifBlank { MODELO_IA_PADRAO })
            .apply()
    }
}
