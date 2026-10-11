package com.taylor.spook.server.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taylor.spook.server.Ajustes
import com.taylor.spook.server.Registro
import com.taylor.spook.server.Servidor
import com.taylor.spook.server.ui.tema.Cor
import kotlinx.coroutines.launch

@Composable
fun AbaAjustes(servidor: Servidor) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val estado by servidor.estado.collectAsState()

    val escopo = rememberCoroutineScope()
    var caminhoBackup by remember { mutableStateOf<String?>(null) }
    var porta by remember { mutableStateOf(Ajustes.porta(ctx).toString()) }
    var mostrarToken by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Cartao("CONEXAO") {
            Column {
                Campo(
                    valor = porta,
                    dica = "porta (reinicia o hub)",
                    aoMudar = { entrada ->
                        porta = entrada.filter { it.isDigit() }.take(5)
                    },
                    acao = {
                        Botao("SALVAR", Cor.Magenta, habilitado = porta.isNotBlank()) {
                            val n = porta.toIntOrNull()
                            if (n != null && n in 1..65535) {
                                Ajustes.salvarPorta(ctx, n)
                                Registro.i("porta salva: $n")
                                escopo.launch {
                                    if (estado.rodando) servidor.reiniciar()
                                }
                            } else {
                                Registro.e("porta invalida: $porta")
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Rotulo(
                    "se estiver ligado, o hub reinicia ja na nova porta",
                    Cor.Cinza, 10.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Cartao("AUTOSTART") {
            Column {
                FileiraEspacada {
                    Botao(
                        "INICIAR COM O BOOT",
                        if (Ajustes.autostart(ctx)) Cor.Verde else Cor.Cinza,
                        Modifier.weight(1f)
                    ) { Ajustes.setAutostart(ctx, true); Registro.i("autostart ligado") }
                    Botao("DESLIGAR", Cor.Vermelho, Modifier.weight(1f)) {
                        Ajustes.setAutostart(ctx, false); Registro.i("autostart desligado")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Rotulo(
                    if (Ajustes.autostart(ctx)) "o hub volta sozinho depois de reiniciar"
                    else "so liga quando voce abrir o app",
                    Cor.Cinza, 10.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Cartao("MANTER ACORDADO") {
            Column {
                FileiraEspacada {
                    Botao(
                        "LIGAR",
                        if (Ajustes.manterAcordado(ctx)) Cor.Verde else Cor.Cinza,
                        Modifier.weight(1f),
                    ) {
                        Ajustes.setManterAcordado(ctx, true)
                        Registro.i("wakelock sera aplicado ao reiniciar o hub")
                    }
                    Botao("DESLIGAR", Cor.Vermelho, Modifier.weight(1f)) {
                        Ajustes.setManterAcordado(ctx, false)
                        Registro.i("wakelock sera removido ao reiniciar o hub")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Rotulo(
                    if (Ajustes.manterAcordado(ctx))
                        "CPU fica acordada; reinicie o hub para aplicar"
                    else
                        "economiza bateria; use so se a tela apagada derrubar o hub",
                    Cor.Cinza, 10.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Cartao("TOKEN DE PAREAMENTO") {
            Column {
                Rotulo(
                    if (mostrarToken) estado.token
                    else estado.token.take(8) + "…" + estado.token.takeLast(4),
                    Cor.Magenta, 13.sp
                )
                Spacer(Modifier.height(8.dp))
                FileiraEspacada {
                    Botao(
                        if (mostrarToken) "OCULTAR" else "MOSTRAR",
                        Cor.Cyan, Modifier.weight(1f)
                    ) { mostrarToken = !mostrarToken }
                    Botao("COPIAR", Cor.Cyan, Modifier.weight(1f)) {
                        copiar(ctx, estado.token)
                        Registro.i("token copiado")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Rotulo(
                    "quem pareia precisa deste valor em auth={token:...}",
                    Cor.Cinza, 10.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Cartao("BACKUP") {
            Column {
                Rotulo(
                    "grava um JSON com mensagens e tasks em getExternalFilesDir(\"backup\")",
                    Cor.Cinza, 10.sp
                )
                Spacer(Modifier.height(8.dp))
                Botao("GERAR BACKUP", Cor.Verde, Modifier.fillMaxWidth()) {
                    escopo.launch {
                        val caminho = servidor.gerarBackup(ctx)
                        caminhoBackup = caminho ?: "falhou (veja o log)"
                    }
                }
                caminhoBackup?.let {
                    Spacer(Modifier.height(8.dp))
                    Rotulo(it, Cor.Verde, 10.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Cartao("ENDERECOS") {
            Column {
                if (estado.rodando) {
                    estado.ips.forEach { ip ->
                        Rotulo("http://$ip:${estado.porta}", Cor.Branco, 12.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Rotulo("EIO=4 · /socket.io/ · /healthz", Cor.Cinza, 10.sp)
                } else {
                    Rotulo("hub parado", Cor.Cinza, 12.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Cartao("SISTEMA") {
            Column {
                val info = servidor.infoLegivel()
                info.forEach { (k, v) -> Linha(k, v) }
            }
        }

        Spacer(Modifier.height(12.dp))

        Cartao("SENHA DO PAINEL WEB") {
            Column {
                var estadoSenha by remember { mutableStateOf("") }
                var mensagem by remember { mutableStateOf("") }
                Rotulo(
                    "senha para logar no painel web (http://IP:porta/)",
                    Cor.Cinza, 10.sp
                )
                Spacer(Modifier.height(8.dp))
                Campo(
                    valor = estadoSenha,
                    dica = "nova senha (vazio = desabilita login por senha)",
                    aoMudar = { estadoSenha = it },
                    acao = {
                        Botao("DEFINIR", Cor.Verde, Modifier.weight(1f), habilitado = estadoSenha.isNotBlank()) {
                            escopo.launch {
                                val token = servidor.token
                                try {
                                    val resposta = com.taylor.spook.server.Rede.postJson(
                                        "http://127.0.0.1:${estado.porta}/api/auth/senha",
                                        mapOf("Authorization" to "Bearer $token"),
                                        mapOf("senha" to estadoSenha)
                                    )
                                    mensagem = if (resposta.contains("ok")) "senha definida" else "erro: $resposta"
                                } catch (e: Exception) {
                                    mensagem = "erro: ${e.message}"
                                }
                            }
                        }
                        Botao("REMOVER", Cor.Vermelho, Modifier.weight(1f)) {
                            escopo.launch {
                                val token = servidor.token
                                try {
                                    val resposta = com.taylor.spook.server.Rede.postJson(
                                        "http://127.0.0.1:${estado.porta}/api/auth/senha",
                                        mapOf("Authorization" to "Bearer $token"),
                                        mapOf("senha" to "")
                                    )
                                    mensagem = if (resposta.contains("ok")) "senha removida" else "erro: $resposta"
                                } catch (e: Exception) {
                                    mensagem = "erro: ${e.message}"
                                }
                            }
                        }
                    },
                )
                mensagem?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Rotulo(it, if (it.startsWith("senha")) Cor.Verde else Cor.Vermelho, 10.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Cartao("ACOPLAR CORPO") {
            Column {
                Rotulo(
                    "a tela mostra o estado, mas quem manda evento e o parceiro",
                    Cor.Cinza, 11.sp
                )
                Spacer(Modifier.height(4.dp))
                Rotulo("contrato = ConexaoChat.kt / ConexaoNotebook.kt", Cor.Cinza, 10.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

private fun copiar(ctx: Context, texto: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("spook-token", texto))
}
