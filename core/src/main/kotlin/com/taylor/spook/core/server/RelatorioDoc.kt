package com.taylor.spook.core.server

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Gera o .docx dos relatorios de scan no proprio celular (portado do
 * `relatorio_doc.py` do Oracle). O pacote-base (16 partes, com docProps,
 * settings, fontTable, theme e styles) e o que o pandoc produz e vai embutido
 * em `resources/spook/base.docx`; aqui so substituimos `word/document.xml`
 * pelo corpo colorido e a fonte default do `styles.xml`.
 *
 * Escrever as 5 partes a mao passava no pandoc mas o Google Docs recusava
 * abrir -- por isso a base do pandoc e obrigatoria. Como o drive saiu de cena,
 * o pacote so precisa abrir no Word/LibreOffice/Google Docs manual.
 *
 * Sem pandoc no Android: por isso o pacote viaja embutido.
 */
object RelatorioDoc {

    // paleta escurecida para fundo claro (mesma do relatorio_doc.py)
    private val D = mapOf(
        "titulo" to "A67900",
        "aberto" to "0B8A3C",
        "fechado" to "1F6FB2",
        "versao" to "C25A0A",
        "erro" to "D32F2F",
        "aviso" to "B07400",
        "texto" to "1A1F24",
        "apagado" to "12664A",
        "numero" to "8A8F98",
    )
    private const val FONTE = "JetBrains Mono"

    private val RE_PORTA = Regex("^(\\S+)/(tcp|udp)\\s+(\\S+)")
    private val RE_ERRO = Regex(
        "\\b(error|failed|failure|refused|denied|timeout|timed out|unreachable|not found|invalid)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val RE_TABELA = Regex("^\\s*(PORT|STATE|SERVICE|VERSION|NAME|TYPE|DOMAIN|CODE)\\b")
    private val RE_AVISO = Regex("\\b(warning|warn|deprecated|not shown|filtered)\\b", RegexOption.IGNORE_CASE)
    private val RE_RESUMO = Regex("^Nmap (done|scan report)|scanned in|\\d+ hosts? up", RegexOption.IGNORE_CASE)
    private val RE_SECAO = Regex("^\\s*[=\\-_*]{3,}\\s*$")
    private val RE_RUIDO = Regex("^\\s*(Starting Nmap|Nmap done|Service detection|WARNING:|Warning:)")
    private val RE_SEP = Regex("^[=\\-_*]{3,}$")
    private val RE_META = Regex("^([A-Z]{2,12})\\s*:\\s*(.+)$")

    data class Dados(val meta: Map<String, String>, val corpo: String)

    // ------------------------------------------------------------- analise

    fun analisar(texto: String): Dados {
        val linhas = texto.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val meta = LinkedHashMap<String, String>()
        var ini = 0
        while (ini < linhas.size) {
            val t = linhas[ini].trim()
            val m = RE_META.find(t)
            if (m != null) {
                meta[m.groupValues[1]] = m.groupValues[2].trim()
                ini++
                continue
            }
            if (t.isEmpty() || RE_SEP.matches(t) || t.contains("SPOOK")) { ini++; continue }
            if (t.startsWith("$")) {
                meta.putIfAbsent("COMMAND", t.substring(1).trim())
                ini++
                continue
            }
            break
        }
        var fim = linhas.size
        while (fim > ini) {
            val t = linhas[fim - 1].trim()
            if (t.isEmpty() || RE_SEP.matches(t) || t.contains("SPOOK") || t.contains("END OF REPORT")) { fim--; continue }
            break
        }
        val corpo = linhas.subList(ini, fim).joinToString("\n").trim('\n')
        return Dados(meta, corpo)
    }

    /** Porta de estilizarLinha(): devolve [(corKey, trecho), ...] da linha. */
    fun estilizar(linha: String): List<Pair<String, String>> {
        val t = linha.trim()
        if (t.startsWith("$")) return listOf("prompt" to linha)
        if (RE_SECAO.matches(linha)) return listOf("apagado" to linha)
        if (RE_TABELA.containsMatchIn(linha)) return listOf("titulo" to linha)
        if (RE_ERRO.containsMatchIn(linha)) return listOf("erro" to linha)
        if (linha.contains("Host is up")) return listOf("aberto" to linha)
        val m = RE_PORTA.find(linha)
        if (m != null && m.range.first == 0) {
            val estado = m.groupValues[3]
            val cor = when {
                estado.startsWith("open") -> "aberto"
                estado.startsWith("closed") -> "fechado"
                estado.startsWith("filtered") -> "aviso"
                else -> "texto"
            }
            val blocos = linha.split(Regex("\\s{2,}"))
            if (blocos.size >= 4 && blocos[3].contains(" ")) {
                val i = linha.indexOf(blocos[3])
                if (i >= 0) return listOf(cor to linha.substring(0, i), "versao" to linha.substring(i))
            }
            return listOf(cor to linha)
        }
        if (RE_RESUMO.containsMatchIn(linha)) return listOf("aberto" to linha)
        if (RE_AVISO.containsMatchIn(linha)) return listOf("aviso" to linha)
        if (t.isEmpty()) return listOf("texto" to "")
        if (RE_RUIDO.find(linha)?.range?.first == 0) return listOf("apagado" to linha)
        return listOf("texto" to linha)
    }

    // ----------------------------------------------------------------- OOXML

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun run(txt: String, cor: String, negrito: Boolean = false, meio: Int = 17): String {
        // no relatorio_doc.py a cor "prompt" cai no fallback ("texto"): manter.
        val c = D[cor] ?: D["texto"]!!
        return "<w:r><w:rPr><w:rFonts w:ascii=\"$FONTE\" w:hAnsi=\"$FONTE\" w:cs=\"$FONTE\"/>" +
            (if (negrito) "<w:b/>" else "") +
            "<w:color w:val=\"$c\"/><w:sz w:val=\"$meio\"/><w:szCs w:val=\"$meio\"/></w:rPr>" +
            "<w:t xml:space=\"preserve\">${xml(txt)}</w:t></w:r>"
    }

    private fun par(runs: List<String>, fundo: String? = null): String {
        val sombra = if (fundo != null) {
            "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$fundo\"/>" +
                "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"200\" w:lineRule=\"exact\"/></w:pPr>"
        } else ""
        return "<w:p>$sombra${runs.joinToString("")}</w:p>"
    }

    private fun documentXml(dados: Dados): String {
        val meta = dados.meta
        val perfil = meta["PROFILE"] ?: "SCAN"
        val alvo = meta["TARGET"] ?: ""
        val quando = meta["DATE"] ?: ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z"))
        val codigo = meta["EXIT"] ?: meta["CODE"] ?: ""
        val duracao = meta["DURATION"] ?: meta["DURACAO"] ?: ""
        val titulo = if (perfil.uppercase() in listOf("", "SCAN")) "SPOOK // SCAN REPORT" else perfil.uppercase()

        val corpo = mutableListOf<String>()
        corpo.add(par(listOf(run(titulo, "titulo", negrito = true, meio = 26))))
        corpo.add(par(listOf(run("alvo  ${alvo.ifEmpty { "-" }}", "aberto", meio = 20))))
        val esquerda = quando + (if (duracao.isNotEmpty()) "   duracao ${duracao}s" else "")
        val exitTxt = "   exit ${codigo.ifEmpty { "0" }}"
        corpo.add(par(listOf(run(esquerda, "apagado", meio = 18), run(exitTxt, if (codigo.isNotEmpty() && codigo != "0") "erro" else "aberto", meio = 18))))
        corpo.add(par(listOf(run("spook://scan-output", "apagado", meio = 16)), fundo = "F4F5F6"))

        dados.corpo.split("\n").forEachIndexed { i, ln ->
            val partes = mutableListOf(run("%4d ".format(i + 1), "numero", meio = 16))
            for ((cor, txt) in estilizar(ln)) partes.add(run(txt, cor))
            corpo.add(par(partes))
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>${corpo.joinToString("")}" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"" +
            " w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
    }

    private fun aplicaFonte(estilos: String): String {
        val padrao = "<w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"$FONTE\" w:hAnsi=\"$FONTE\" w:cs=\"$FONTE\"/>" +
            "<w:sz w:val=\"17\"/><w:szCs w:val=\"17\"/>" +
            "</w:rPr></w:rPrDefault>"
        val re = Regex("<w:rPrDefault>.*?</w:rPrDefault>", RegexOption.DOT_MATCHES_ALL)
        return if (re.containsMatchIn(estilos)) {
            re.replaceFirst(estilos, Regex.escapeReplacement(padrao))
        } else if (estilos.contains("<w:docDefaults>")) {
            estilos.replaceFirst("<w:docDefaults>", "<w:docDefaults>$padrao")
        } else estilos
    }

    private fun base(): LinkedHashMap<String, ByteArray> {
        val bruto = javaClass.getResourceAsStream("/spook/base.docx")?.readBytes()
            ?: error("pacote base spook/base.docx ausente no classpath")
        val partes = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bruto)).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                partes[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return partes
    }

    /**
     * Gera o .docx do texto de um relatorio. Devolve os bytes ou null se algo
     * falhar (o .txt cru continua valendo).
     */
    fun gerarDocx(texto: String): ByteArray? = try {
        val doc = documentXml(analisar(texto)).toByteArray(Charsets.UTF_8)
        val partes = base()
        partes["word/document.xml"] = doc
        partes["word/styles.xml"]?.let {
            partes["word/styles.xml"] = aplicaFonte(String(it, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((nome, dados) in partes) {
                z.putNextEntry(ZipEntry(nome))
                z.write(dados)
                z.closeEntry()
            }
        }
        out.toByteArray()
    } catch (e: Exception) {
        null
    }
}
