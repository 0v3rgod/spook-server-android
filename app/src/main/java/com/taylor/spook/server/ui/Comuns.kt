package com.taylor.spook.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taylor.spook.server.ui.tema.Cor

/** Cartao com titulo em versalete, o unico "container" da UI. */
@Composable
fun Cartao(
    titulo: String,
    borda: Color = Cor.Borda,
    conteudo: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Cor.FundoCartao)
            .border(1.dp, borda, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            titulo,
            color = Cor.Cinza,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(8.dp))
        conteudo()
    }
}

@Composable
fun Linha(chave: String, valor: String, corValor: Color = Cor.Branco) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            chave,
            color = Cor.Cinza,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(96.dp),
        )
        Text(valor, color = corValor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun Botao(
    texto: String,
    cor: Color,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    aoTocar: () -> Unit,
) {
    Button(
        onClick = aoTocar,
        enabled = habilitado,
        modifier = modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = cor.copy(alpha = if (habilitado) 0.16f else 0.06f),
            contentColor = if (habilitado) cor else Cor.Cinza,
        ),
    ) {
        Text(texto, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun Campo(
    valor: String,
    dica: String,
    aoMudar: (String) -> Unit,
    acao: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = valor,
            onValueChange = aoMudar,
            label = { Text(dica, fontSize = 12.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace, fontSize = 13.sp
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cor.Magenta,
                unfocusedBorderColor = Cor.Borda,
                focusedLabelColor = Cor.Magenta,
                unfocusedLabelColor = Cor.Cinza,
                focusedTextColor = Cor.Branco,
                unfocusedTextColor = Cor.Branco,
                cursorColor = Cor.Magenta,
            ),
            modifier = Modifier.weight(1f),
        )
        acao?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

@Composable
fun Rotulo(
    texto: String,
    cor: Color = Cor.Cinza,
    tamanho: TextUnit = 11.sp,
    negrito: Boolean = false,
    aoTocar: (() -> Unit)? = null,
) {
    Text(
        texto,
        color = cor,
        fontSize = tamanho,
        fontWeight = if (negrito) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = if (negrito) 2.sp else 0.sp,
        modifier = if (aoTocar != null) Modifier.clickable { aoTocar() } else Modifier,
    )
}

@Composable
fun Vazio(texto: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Rotulo(texto, Cor.Cinza, 12.sp)
    }
}

@Composable
fun ItemClicavel(
    aoTocar: () -> Unit,
    conteudo: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable { aoTocar() }) { conteudo() }
}

@Composable
fun FileiraEspacada(conteudo: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { conteudo() }
}
