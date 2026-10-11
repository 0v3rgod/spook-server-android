package com.taylor.spook.server.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.taylor.spook.server.Ajustes
import com.taylor.spook.server.Registro
import com.taylor.spook.server.Servidor
import com.taylor.spook.server.SpookService
import com.taylor.spook.server.ui.tema.Cor
import com.taylor.spook.server.ui.tema.TemaSpook
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TemaSpook { Tela() } }
    }
}

private enum class Aba(val titulo: String) {
    PAINEL("PAINEL"), CHAT("CHAT"), TASKS("TASKS"), AJUSTES("AJUSTES")
}

@Composable
private fun Tela() {
    val ctx = LocalContext.current
    val servidor = remember(ctx) { Servidor.de(ctx) }
    val escopo = rememberCoroutineScope()
    val estado by servidor.estado.collectAsState()
    var aba by remember { mutableStateOf(Aba.PAINEL) }

    LaunchedEffect(estado.rodando) {
        while (estado.rodando) {
            delay(1000)
            servidor.atualizarContagens()
        }
    }

    // Android 13+: sem POST_NOTIFICATIONS o foreground service roda, mas a
    // notificacao fica invisivel -- e ela e o "o servidor esta no ar".
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Registro.i("notificacao ${if (it) "permitida" else "negada"}")
    }
    // Abrir o app ja liga o hub. Antes o usuario tinha que apertar INICIAR
    // HUB toda vez que o MIUI matava o processo -- e o force-stop desliga o
    // BootReceiver junto, entao nao voltava sozinho. Aqui o autostart e o
    // mesmo switch de AJUSTES; com ele desligado, so o botao manual liga.
    LaunchedEffect(Unit) {
        if (Ajustes.autostart(ctx) && !servidor.estado.value.rodando) {
            Registro.i("abriu o app e o hub estava parado: ligando")
            Servidor.iniciarService(ctx)
        }
    }

    LaunchedEffect(Unit) {
        val tem = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!tem) {
            Registro.i("pedindo POST_NOTIFICATIONS")
            pedir.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Barra no rodape via Column + weight no conteudo. NADA de align:
// Modifier.align colapsa a Row em 1px no Compose BOM 2026.02.01 (a barra
// encostava no fundo da tela sem altura, e as tabs saiam 0x0).
    Column(
        Modifier
            .fillMaxSize()
            .background(Cor.Fundo)
            .padding(top = 28.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (aba) {
                Aba.PAINEL -> Painel(ctx, estado, servidor) {
                    escopo.launch { servidor.mandarMensagem("ping do painel") }
                }
                Aba.CHAT -> AbaChat(servidor)
                Aba.TASKS -> AbaTasks(servidor)
                Aba.AJUSTES -> AbaAjustes(servidor)
            }
        }
        Barra(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            atual = aba,
            aoMudar = { aba = it },
        )
    }
}

@Composable
private fun Barra(mod: Modifier, atual: Aba, aoMudar: (Aba) -> Unit) {
    Row(
        mod
            .fillMaxWidth()
            // requiredHeight, e nao height: quando a barra vem depois de um
            // irmao que consome tudo, o Compose passa maxHeight ~0 e o
            // height() respeita essa constraint (dava 1px). requiredHeight
            // ignora a constraint do pai.
            .requiredHeight(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Cor.FundoCartao)
            .border(1.dp, Cor.Borda, RoundedCornerShape(14.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Aba.entries.forEach { a ->
            val ativo = a == atual
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (ativo) Cor.Magenta.copy(alpha = 0.18f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (ativo) Cor.Magenta else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { aoMudar(a) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    a.titulo,
                    color = if (ativo) Cor.Magenta else Cor.Cinza,
                    fontSize = 10.sp,
                    fontWeight = if (ativo) FontWeight.Black else FontWeight.Normal,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun Painel(ctx: Context, estado: Servidor.Estado, servidor: Servidor, aoPing: () -> Unit) {
    val linhas by Registro.linhas.collectAsState()
    val textoLog = linhas.takeLast(60).asReversed().map {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.quando)) + "  " + it.texto
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Cabecalho(estado)
        Spacer(Modifier.height(14.dp))

        Cartao("ENDERECO DE PAREAMENTO") {
            Column {
                if (!estado.rodando) {
                    Rotulo("hub parado", Cor.Cinza, 12.sp)
                } else {
                    if (estado.ips.isEmpty()) Rotulo("so loopback (sem Wi-Fi)", Cor.Cinza, 12.sp)
                    estado.ips.forEach { ip ->
                        Rotulo("http://$ip:${estado.porta}", Cor.Branco, 13.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Rotulo("EIO=4 · /socket.io/ · /healthz", Cor.Cinza, 10.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Cartao("TOKEN") { Rotulo(estado.token, Cor.Magenta, 13.sp) }

        Spacer(Modifier.height(10.dp))
        Cartao("CONTAGENS") {
            Column {
                Linha("sessoes", estado.sessoes.toString())
                Linha("mensagens", estado.mensagens.toString())
                Linha("tasks", estado.tasks.toString())
                estado.erro?.let { Spacer(Modifier.height(6.dp)); Linha("erro", it, Cor.Vermelho) }
            }
        }

        Spacer(Modifier.height(10.dp))
        FileiraEspacada {
            if (estado.rodando) {
                Botao("PARAR", Cor.Vermelho, Modifier.weight(1f)) {
                    ctx.stopService(Intent(ctx, SpookService::class.java))
                }
            } else {
                Botao("INICIAR HUB", Cor.Magenta, Modifier.weight(1f)) {
                    Servidor.iniciarService(ctx)
                }
            }
            Botao("PING", Cor.Cyan, Modifier.weight(1f), aoTocar = aoPing)
        }

        Spacer(Modifier.height(12.dp))
        Rotulo("LOG", Cor.Cinza, 11.sp, negrito = true)
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Cor.FundoCartao)
                .border(1.dp, Cor.Borda, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            items(textoLog) { t ->
                Text(
                    t,
                    color = Cor.Branco,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun Cabecalho(est: Servidor.Estado) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Cor.FundoCartao)
                .border(1.dp, if (est.rodando) Cor.Magenta else Cor.Borda, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("S", color = Cor.Magenta, fontSize = 22.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "SPOOK SERVER", color = Cor.Branco, fontSize = 18.sp,
                fontWeight = FontWeight.Black, letterSpacing = 1.sp
            )
            Text(
                "v${Servidor.VERSAO} · Engine.IO v4",
                color = Cor.Cinza, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.weight(1f))
        val cor = when {
            est.erro != null -> Cor.Vermelho
            est.rodando -> Cor.Verde
            else -> Cor.Amarelo
        }
        Text(
            if (est.rodando) "ON" else "OFF",
            color = cor, fontSize = 16.sp, fontWeight = FontWeight.Black,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, cor, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
