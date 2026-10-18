package com.taylor.spook.server.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.taylor.spook.server.Registro
import com.taylor.spook.server.Servidor
import com.taylor.spook.server.SpookService
import com.taylor.spook.server.ui.tema.Cor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fmtHora = SimpleDateFormat("HH:mm", Locale.getDefault())
private val fmtDia = SimpleDateFormat("EEEE, d 'de' MMMM", Locale.getDefault())

/**
 * INICIO: e' a tela inicial do Redmi quando o app esta definido como launcher
 * (categoria HOME no manifest). Junta as duas coisas que o aparelho precisa ter
 * a mao: o estado do hub -- que roda aqui dentro -- e a grade com todos os apps
 * instalados. Sem a grade, virar launcher trancaria o celular: nada alem do
 * proprio SPOOK teria porta de entrada.
 */
@Composable
fun AbaInicio(servidor: Servidor) {
    val ctx = LocalContext.current
    val estado by servidor.estado.collectAsState()
    val escopo = rememberCoroutineScope()

    var agora by remember { mutableStateOf(System.currentTimeMillis()) }
    var bateria by remember { mutableStateOf(Bateria(-1, false)) }
    var busca by remember { mutableStateOf("") }
    var recarga by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AppLancavel>>(emptyList()) }

    // O relogio e' a unica coisa que muda sozinha na tela.
    LaunchedEffect(Unit) {
        while (true) {
            agora = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Bateria a cada 15s: o sticky intent e' IPC, nao vale rodar a cada segundo.
    LaunchedEffect(Unit) {
        while (true) {
            bateria = lerBateria(ctx)
            delay(15_000)
        }
    }

    // Grade de apps: entra na aba (Unit), muda o contador de recarga (botao ↻).
    LaunchedEffect(recarga) {
        apps = carregarApps(ctx)
        Registro.i("home: ${apps.size} apps na grade")
    }

    val filtrados = remember(busca, apps) {
        val q = busca.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { it.rotulo.lowercase().contains(q) || it.pacote.contains(q) }
    }

    // Tudo num unico LazyVerticalGrid: um LazyColumn dentro de outro nao rola
    // (a grade precisa de altura fixa), e aqui os blocos de cima sao so itens
    // que ocupam a linha inteira.
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { CabecalhoInicio(estado, agora, bateria) }
        item(span = { GridItemSpan(maxLineSpan) }) { StatusHub(estado, agora) }
        item(span = { GridItemSpan(maxLineSpan) }) { AcoesInicio(ctx, estado, escopo) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Campo(busca, "buscar app", { busca = it }) }
                Spacer(Modifier.width(8.dp))
                Botao("↻", Cor.Cyan, Modifier.width(52.dp)) { recarga++ }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Rotulo(
                if (filtrados.isEmpty()) "nenhum app encontrado" else "${filtrados.size} apps",
                Cor.Cinza, 10.sp, negrito = true,
            )
        }
        items(filtrados) { app -> CelulaApp(app) { abrirApp(ctx, app) } }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun CabecalhoInicio(estado: Servidor.Estado, agora: Long, bateria: Bateria) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cor.FundoCartao)
                    .border(
                        1.dp,
                        if (estado.rodando) Cor.Magenta else Cor.Borda,
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("S", color = Cor.Magenta, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "SPOOK SERVER",
                    color = Cor.Branco,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                Text(
                    android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                    color = Cor.Cinza,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${bateria.pct}%" + if (bateria.carregando) " ⚡" else "",
                color = if (bateria.pct in 0..20) Cor.Vermelho else Cor.Cinza,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            fmtHora.format(Date(agora)),
            color = Cor.Cyan,
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
        )
        Text(fmtDia.format(Date(agora)), color = Cor.Cinza, fontSize = 11.sp)
    }
}

@Composable
private fun StatusHub(estado: Servidor.Estado, agora: Long) {
    Cartao("HUB", borda = if (estado.rodando) Cor.Magenta.copy(alpha = 0.55f) else Cor.Borda) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cor = when {
                    estado.erro != null -> Cor.Vermelho
                    estado.rodando -> Cor.Verde
                    else -> Cor.Amarelo
                }
                Text(
                    if (estado.rodando) "NO AR" else "PARADO",
                    color = cor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                if (estado.rodando) {
                    Spacer(Modifier.width(10.dp))
                    val segundos = estado.desde?.let { (agora - it) / 1000 } ?: 0
                    Text(
                        "ha ${duracao(segundos)}",
                        color = Cor.Cinza,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Linha(
                "endereco",
                estado.ips.firstOrNull()?.let { "$it:${estado.porta}" } ?: "-",
            )
            Linha("sessoes", estado.sessoes.toString())
            Linha("mensagens", estado.mensagens.toString())
            Linha("tasks", estado.tasks.toString())
            estado.erro?.let { Linha("erro", it, Cor.Vermelho) }
        }
    }
}

@Composable
private fun AcoesInicio(ctx: Context, estado: Servidor.Estado, escopo: CoroutineScope) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao("PAINEL WEB", Cor.Cyan, Modifier.weight(1f)) { abrirPainel(ctx, estado) }
            Botao("TERMUX", Cor.Verde, Modifier.weight(1f)) { abrirPacote(ctx, "com.termux") }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao("CONFIG", Cor.Amarelo, Modifier.weight(1f)) { abrirConfiguracoes(ctx) }
            Botao(
                if (estado.rodando) "REINICIAR" else "INICIAR HUB",
                Cor.Magenta,
                Modifier.weight(1f),
            ) {
                if (!estado.rodando) {
                    Servidor.iniciarService(ctx)
                } else {
                    // parar e subir de novo: o onDestroy do service fecha o
                    // socket, entao sem a pausa o novo bind pega a porta velha.
                    escopo.launch {
                        ctx.stopService(Intent(ctx, SpookService::class.java))
                        delay(900)
                        Servidor.iniciarService(ctx)
                    }
                }
            }
        }
    }
}

@Composable
private fun CelulaApp(app: AppLancavel, aoTocar: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { aoTocar() }
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = app.icone,
            contentDescription = app.rotulo,
            modifier = Modifier.size(46.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            app.rotulo,
            color = Cor.Branco,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ----------------------------------------------------------------- dados

private data class AppLancavel(
    val rotulo: String,
    val pacote: String,
    val atividade: String,
    val icone: ImageBitmap,
)

private data class Bateria(val pct: Int, val carregando: Boolean)

/** Todo app com activity LAUNCHER, menos o proprio SPOOK (ele E' a home). */
@Suppress("DEPRECATION")
private fun carregarApps(ctx: Context): List<AppLancavel> {
    val pm = ctx.packageManager
    val intencao = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intencao, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != ctx.packageName }
            .mapNotNull { r ->
                runCatching {
                    AppLancavel(
                        rotulo = r.loadLabel(pm).toString(),
                        pacote = r.activityInfo.packageName,
                        atividade = r.activityInfo.name,
                        icone = r.loadIcon(pm).toBitmap(96, 96).asImageBitmap(),
                    )
                }.getOrNull()
            }
            .sortedBy { it.rotulo.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}

private fun abrirApp(ctx: Context, app: AppLancavel) {
    val i = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(app.pacote, app.atividade))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    runCatching { ctx.startActivity(i) }
        .onFailure { Registro.e("nao abri ${app.pacote}: ${it.message}") }
}

private fun abrirPainel(ctx: Context, estado: Servidor.Estado) {
    val ip = estado.ips.firstOrNull() ?: "127.0.0.1"
    val url = "http://$ip:${estado.porta}/"
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Registro.e("sem navegador pra $url: ${it.message}") }
}

private fun abrirPacote(ctx: Context, pacote: String) {
    runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(pacote)
            ?: error("$pacote nao instalado")
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Registro.e("nao abri $pacote: ${it.message}") }
}

private fun abrirConfiguracoes(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Registro.e("nao abri configuracoes: ${it.message}") }
}

private fun lerBateria(ctx: Context): Bateria {
    // BatteryManager.BATTERY_STATUS e @hide; o jeito publico de ler estado de
    // carga e o sticky broadcast ACTION_BATTERY_CHANGED.
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return Bateria(-1, false)
    val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val pct = if (escala > 0) nivel * 100 / escala else -1
    val carregando = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING,
        BatteryManager.BATTERY_STATUS_FULL,
        -> true

        else -> false
    }
    return Bateria(pct, carregando)
}

private fun duracao(segundos: Long): String {
    val d = segundos / 86400
    val h = (segundos % 86400) / 3600
    val m = (segundos % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}
