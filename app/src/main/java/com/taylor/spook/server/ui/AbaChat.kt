package com.taylor.spook.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taylor.spook.core.model.Mensagem
import com.taylor.spook.server.Banco
import com.taylor.spook.server.Registro
import com.taylor.spook.server.Servidor
import com.taylor.spook.server.ui.tema.Cor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AbaChat(servidor: Servidor) {
    var lista by remember { mutableStateOf<List<Mensagem>>(emptyList()) }
    var texto by remember { mutableStateOf("") }
    val escopo = rememberCoroutineScope()
    val rolagem = rememberLazyListState()

    suspend fun recarregar() {
        lista = Banco.mensagens()
    }

    LaunchedEffect(Unit) {
        recarregar()
        // o app PentestLink pode escrever pelo socket a qualquer momento
        while (true) {
            kotlinx.coroutines.delay(1500)
            recarregar()
        }
    }

    LaunchedEffect(lista.size) {
        if (lista.isNotEmpty()) rolagem.animateScrollToItem(lista.size - 1)
    }

    // Campo sobreposto no rodape (Box + align) e a lista como ultimo filho do
    // Column de dentro: lista com weight seguida de irmao nao renderiza neste
    // Compose BOM 2026.02.01 (0px de altura).
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = 76.dp)
        ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Rotulo("CHAT", Cor.Magenta, 13.sp, negrito = true)
            Spacer(Modifier.width(8.dp))
            Rotulo("${lista.size} msg", Cor.Cinza, 11.sp)
            Spacer(Modifier.weight(1f))
            Rotulo(
                if (servidor.estado.value.rodando) "ao vivo" else "offline",
                if (servidor.estado.value.rodando) Cor.Verde else Cor.Cinza, 11.sp
            )
        }

        if (lista.isEmpty()) {
            Vazio("nenhuma mensagem ainda\no parceiro pode mandar por socket ou REST")
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = rolagem,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(lista, key = { it.id }) { m ->
                    Bolha(m, aoApagar = {
                        escopo.launch {
                            // So pelo Servidor: ele apaga E anuncia. Apagar
                            // direto no Banco tirava a linha antes do
                            // broadcast, o Servidor nao achava mais nada e o
                            // chat_apagada nunca chegava nos outros clientes.
                            servidor.apagarMensagem(m.id)
                            recarregar()
                        }
                    })
                }
            }
        }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Campo(
                valor = texto,
                dica = "mensagem",
                aoMudar = { texto = it },
                acao = {
                    Botao(
                        texto = "ENVIAR",
                        cor = Cor.Magenta,
                        habilitado = texto.isNotBlank() && servidor.estado.value.rodando,
                    ) {
                        val corpo = texto
                        texto = ""
                        escopo.launch {
                            servidor.mandarMensagem(corpo, autor = "servidor")
                            recarregar()
                        }
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Rotulo(
                if (servidor.estado.value.rodando) "vai para o Drive/espelho quando existir"
                else "hub parado: so fica no banco local",
                Cor.Cinza, 10.sp
            )
        }
    }
}

@Composable
private fun Bolha(m: Mensagem, aoApagar: () -> Unit) {
    val hora = remember(m.ts) { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date((m.ts * 1000).toLong())) }
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Cor.FundoCartao)
                .border(1.dp, Cor.Borda, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.autor ?: "servidor",
                        color = if (m.autor == null) Cor.Cyan else Cor.Magenta,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Rotulo(hora, Cor.Cinza, 10.sp)
                    Spacer(Modifier.weight(1f))
                    if (m.tipo != "texto") {
                        Rotulo(m.tipo, Cor.Amarelo, 10.sp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Rotulo("APAGAR", Cor.Vermelho, 10.sp, negrito = true, aoTocar = aoApagar)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    m.texto,
                    color = Cor.Branco,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
