package com.taylor.spook.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taylor.spook.core.model.Task
import com.taylor.spook.server.Banco
import com.taylor.spook.server.Servidor
import com.taylor.spook.server.ui.tema.Cor
import kotlinx.coroutines.launch
import java.util.UUID

private val Transparente = Color.Transparent

@Composable
fun AbaTasks(servidor: Servidor) {
    var lista by remember { mutableStateOf<List<Task>>(emptyList()) }
    var texto by remember { mutableStateOf("") }
    val escopo = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            lista = Banco.tasks()
            kotlinx.coroutines.delay(1500)
        }
    }

    val abertas = lista.count { !it.feito }

    // Lista com weight e o campo DEPOIS dela nao funciona no Compose BOM
    // 2026.02.01: a lista recebia 0px de altura (o a11y mostrava os itens, o
    // pixels nao). Entao o campo fica sobreposto no rodape (Box + align) e a
    // lista, being o ultimo filho do Column de dentro, fica com o peso todo.
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = 62.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Rotulo("TASKS", Cor.Cyan, 13.sp, negrito = true)
                Spacer(Modifier.width(8.dp))
                Rotulo("$abertas abertas / ${lista.size}", Cor.Cinza, 11.sp)
            }

            if (lista.isEmpty()) {
                Vazio("nenhuma task\ncrie abaixo ou pelo socket (task_nova)")
            } else {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(lista, key = { it.id }) { t ->
                        ItemTask(
                            t = t,
                            aoAlternar = {
                                escopo.launch {
                                    servidor.alternarTask(t.id)
                                    lista = Banco.tasks()
                                }
                            },
                            aoApagar = {
                                escopo.launch {
                                    servidor.apagarTask(t.id)
                                    lista = Banco.tasks()
                                }
                            },
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Campo(
                valor = texto,
                dica = "nova task",
                aoMudar = { texto = it },
                acao = {
                    Botao("ADD", Cor.Cyan, habilitado = texto.isNotBlank()) {
                        val nova = Task(
                            id = UUID.randomUUID().toString(),
                            texto = texto.trim(),
                            criado = System.currentTimeMillis() / 1000.0,
                        )
                        escopo.launch {
                            // Uma task so: inserir no Banco E depois chamar
                            // addTask(texto) criava duas, com ids diferentes.
                            servidor.addTask(nova)
                            texto = ""
                            lista = Banco.tasks()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ItemTask(t: Task, aoAlternar: () -> Unit, aoApagar: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Cor.FundoCartao)
            .border(
                1.dp,
                if (t.feito) Cor.Borda else Cor.Cyan.copy(alpha = 0.5f),
                RoundedCornerShape(10.dp)
            )
            .clickable { aoAlternar() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (t.feito) Cor.Verde else Transparente)
                .border(
                    1.dp,
                    if (t.feito) Cor.Verde else Cor.Cinza,
                    RoundedCornerShape(4.dp)
                )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            t.texto,
            color = if (t.feito) Cor.Cinza else Cor.Branco,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textDecoration = if (t.feito) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f),
        )
        Rotulo("X", Cor.Vermelho, 13.sp, negrito = true, aoTocar = aoApagar)
    }
}
