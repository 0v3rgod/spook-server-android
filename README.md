# SPOOK SERVER

Servidor local que roda **dentro** do Android. O celular e o servidor e o banco:
SQLite no aparelho, sem Google Drive, sem rclone e sem notebook no meio. O
Arch, o Poco e o Oracle so entram como **clientes** (Socket.IO ou REST), pelo
Tailscale.

```
:core   Kotlin/JVM puro - Engine.IO v4 + Socket.IO v5, Hub, Store (SQLite), HubServer.
        Zero dependencias de Android. Roda no Arch e nos testes.
:app    Casca Android - foreground service, boot, SQLite do Android, UI Compose.
        Depende do :core.
```

## Por que dois modulos

O `:core` nao importa nada de Android, entao o mesmo codigo que roda dentro do
foreground service roda no Arch, nos testes, com o mesmo comportamento. E o
contrato do app (Socket.IO) fica testado contra o **jar real** que o celular
usa (`io.socket:socket.io-client:2.1.0`), nao contra um mock.

## O que depende do que

| Precisa | Nao funciona no Android | Substituido por |
|---|---|---|
| `org.xerial:sqlite-jdbc` | sim | `AndroidStore` (`android.database.sqlite`) |
| `ch.qos.logback-classic` | nao e binding valido | `Registro` (Logcat + buffer p/ a tela) |

As duas sao excluidas do classpath do `:app` (ver `app/build.gradle.kts`), senao
o D8 tentaria dexar `java.sql`.

Os dois bancos tem **o mesmo schema** -- um arquivo do :core abre no app e vice versa.

## Compilar

```bash
./gradlew :core:test          # 34 testes (protocolo + interoperabilidade real + REST)
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

| Suite | Testes | O que trava |
|---|---|---|
| `ProtocolTest` | 7 | framing Engine.IO v4 / Socket.IO v5 |
| `InteropAppClientTest` | 10 | cliente com o **jar real do app** (`socket.io-client:2.1.0`) contra o Hub |
| `RestTest` | 17 | REST nao pode virar um segundo banco: toda escrita passa pelo Store e e anunciada no socket |

## Instalar

Instalar direto:

```bash
adb -s e60ee42c install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Se a MIUI voltar a barrar (`INSTALL_FAILED_USER_RESTRICTED`), o caminho e
`adb push` para `/sdcard/Download/` e tocar no arquivo no proprio celular.

Depois de instalar: abrir o app e tocar **INICIAR HUB**. O servico e
foreground, entao o Android nao mata; `BootReceiver` relanca depois de
reiniciar (se o autostart estiver ligado em AJUSTES).

## Icone

O launcher desenha a mascara (circulo/quadrado arredondado) -- o app ja aparece
arredondado sem nenhum formato nosso. As camadas sao geradas da arte:

```bash
python3 tools/gerar_icones.py "$HOME/Downloads/Servidor Neon em Espiral Energética.png"
```

O script mede a cor das bordas da arte (`#000009`) e usa como `background`, e
reduz a spiral para os 72dp centrais (a safe zone) no `foreground`, para o
mascote do launcher nao cortar nada.

Medido: o conteudo brilhante tem raio maximo de 33,6dp contra os 36dp da
mascara circular -- **0% do conteudo cai fora**.

## Como usar

1. Instalar e abrir o app. Com autostart ligado, o hub inicia sozinho; o painel
   mostra os IPs (Wi-Fi e Tailscale), a porta e o **token**.
2. O parceiro conecta em `http://<ip>:5000` com
   `auth = { "token": "<token>" }`. No REST, use somente
   `Authorization: Bearer <token>`; nunca coloque o segredo na URL.

Do Arch, com o Tailscale do celular ligado:

```bash
curl http://100.79.55.68:5000/healthz
curl -H "Authorization: Bearer $TOKEN" http://100.79.55.68:5000/api/chat
curl -H "Authorization: Bearer $TOKEN" -o backup.json http://100.79.55.68:5000/api/backup
```

## REST

| Metodo | Rota | O que faz |
|---|---|---|
| GET | `/healthz` | vivo, sem token |
| GET | `/api/chat` | lista as mensagens |
| POST | `/api/chat` | envia (`{"texto": ...}`) e anuncia `chat_nova` |
| DELETE | `/api/chat/{id}` | apaga e anuncia `chat_apagada` |
| GET | `/api/tasks` | lista as tasks |
| POST | `/api/tasks` | cria (`{"titulo": ...}` ou `{"texto": ...}`) e anuncia `task_nova` |
| PATCH | `/api/tasks/{id}` | alterna `feito` (ou forca com `{"feito": false}`) |
| DELETE | `/api/tasks/{id}` | apaga e anuncia `task_apagada` |
| GET | `/api/relatorios` | lista relatorios |
| GET | `/api/info` | info do aparelho + contagens |
| GET | `/api/backup` | tudo em um JSON (`spook-backup/1`) |

O REST nao e um caminho paralelo: usa o mesmo `Store` e o mesmo `Hub.broadcast`,
entao quem esta no socket ve na hora o que veio pelo `curl` (e vice versa).

## Backup sem Drive

O botao **GERAR BACKUP** em AJUSTES grava
`getExternalFilesDir("backup")`, que o `adb pull` pega sem root:

```bash
adb -s e60ee42c pull /sdcard/Android/data/com.taylor.spook.server/files/backup/ .
```

## Dialeto

O app `PentestLink` e o servidor Python nao falam exatamente igual, entao o Hub
aceita os dois e **manda os dois campos**:

| Conceito | App (`PentestLink`) | Python | O Hub manda |
|---|---|---|---|
| criar task | `task_nova` `{titulo}` | `task_criar` `{titulo}` | `task_nova` com `titulo` **e** `texto` |
| marcar | `task_atualizada` `{id, feito}` | -- | idem (faz upsert se nao existir) |
| apagar | `task_apagada` | `task_apagar` | ambos aceitos |
| hora | `ts` em **ms** | `criado` em segundos | `ts` ms **e** `iso` ISO-8601 UTC |

`iso` sai como texto, nunca como numero: `put(Double)` escreveria `1.79E9` e
quebraria `Instant.parse`/`OffsetDateTime.parse` de quem lê.

## Estado

Funciona e testado no `redmi-note-11` (Android 13): foreground service, boot,
banco local com o schema do :core, Engine.IO v4 em `0.0.0.0:5000`, as quatro
abas (PAINEL/CHAT/TASKS/AJUSTES), REST completo e backup.

Verificado ponta a ponta do Arch pelo Tailscale: socket.io (mesmo jar do app)
conectando, `chat_listar`/`task_listar`, `chat_nova` e `task_atualizada`
recebidos quando a acao sai da UI do celular, REST nos dois sentidos e
`/api/backup`.

Falta: painel Web, IA, scans/comandos Android e envio de relatorio (o
`relatorios` hoje so lista).
