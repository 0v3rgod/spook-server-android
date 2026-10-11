package com.taylor.spook.server.ui.tema

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Esquema = darkColorScheme(
    primary = Cor.Magenta,
    onPrimary = Cor.Fundo,
    secondary = Cor.Cyan,
    onSecondary = Cor.Fundo,
    tertiary = Cor.Amarelo,
    background = Cor.Fundo,
    onBackground = Cor.Branco,
    surface = Cor.FundoCartao,
    onSurface = Cor.Branco,
    surfaceVariant = Cor.FundoCartao,
    onSurfaceVariant = Cor.Cinza,
    outline = Cor.Borda,
    error = Cor.Vermelho,
)

/** O app e sempre escuro: ele e um servidor, nao um app de papel. */
@Composable
fun TemaSpook(conteudo: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val janela = (view.context as? Activity)?.window ?: return@SideEffect
            janela.statusBarColor = Cor.Fundo.toArgb()
            janela.navigationBarColor = Cor.Fundo.toArgb()
            WindowCompat.getInsetsController(janela, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = Esquema, content = conteudo)
}
