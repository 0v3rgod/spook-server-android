package com.taylor.spook.core

import com.taylor.spook.core.server.RelatorioDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * O .docx montado na mao precisa abrir num leitor real. Aqui garantimos que o
 * pacote-base do pandoc sobrevive, que o corpo colorido entra no
 * word/document.xml e que a fonte e aplicada no styles.xml. Em desenvolvimento
 * o arquivo tambem e gravado em /tmp/opencode pra validar com pandoc/unzip.
 */
class RelatorioDocTest {

    private val amostra = """
        PROFILE: LIVE HOST
        TARGET: 192.168.0.1
        DATE: 2026-10-10 20:00:00 -0300
        EXIT: 0
        DURATION: 16.8
        ========================================
        ${'$'} nmap -sV 192.168.0.1
        Starting Nmap 7.95 ( https://nmap.org )
        Host is up (0.0021s latency).
        PORT     STATE  SERVICE  VERSION
        22/tcp   open   ssh      OpenSSH 9.6
        80/tcp   closed http
        443/tcp  filtered https
        Nmap done: 1 IP address (1 host up) scanned in 16.80 seconds
        ========================================
    """.trimIndent()

    private fun partes(docx: ByteArray): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(docx)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                m[e.name] = String(z.readBytes(), Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        return m
    }

    @Test
    fun `gera docx com pacote base e corpo colorido`() {
        val docx = RelatorioDoc.gerarDocx(amostra)
        assertNotNull("gerarDocx devolveu null", docx)
        docx!!
        assertTrue("nao e um zip", docx[0] == 'P'.code.toByte() && docx[1] == 'K'.code.toByte())

        val p = partes(docx)
        for (nome in listOf("[Content_Types].xml", "word/document.xml", "word/styles.xml", "docProps/core.xml", "word/theme/theme1.xml")) {
            assertTrue("faltou $nome", p.containsKey(nome))
        }
        val doc = p["word/document.xml"]!!
        assertTrue("titulo ausente", doc.contains("LIVE HOST"))
        assertTrue("alvo ausente", doc.contains("192.168.0.1"))
        assertTrue("porta open nao ficou verde", doc.contains("w:val=\"0B8A3C\""))
        assertTrue("porta closed nao ficou azul", doc.contains("w:val=\"1F6FB2\""))
        assertTrue("filtro/aviso ausente", doc.contains("w:val=\"B07400\""))
        assertTrue("fonte JetBrains ausente", doc.contains("JetBrains Mono"))
        assertTrue("styles nao recebeu fonte", p["word/styles.xml"]!!.contains("JetBrains Mono"))

        File("/tmp/opencode").mkdirs()
        File("/tmp/opencode/relatorio_test.docx").writeBytes(docx)
    }

    @Test
    fun `analisa cabecalho e corpo separados`() {
        val d = RelatorioDoc.analisar(amostra)
        assertEquals("LIVE HOST", d.meta["PROFILE"])
        assertEquals("192.168.0.1", d.meta["TARGET"])
        assertEquals("16.8", d.meta["DURATION"])
        assertTrue(d.corpo.contains("22/tcp"))
        assertTrue("cabecalho vazou pro corpo", !d.corpo.contains("TARGET:"))
    }

    @Test
    fun `estiliza reconhece estados e versao`() {
        assertEquals("aberto", RelatorioDoc.estilizar("22/tcp   open   ssh      OpenSSH 9.6").first().first)
        assertEquals("fechado", RelatorioDoc.estilizar("80/tcp   closed http").first().first)
        assertEquals("aviso", RelatorioDoc.estilizar("443/tcp  filtered https").first().first)
        val linha = RelatorioDoc.estilizar("22/tcp   open   ssh      OpenSSH 9.6")
        assertTrue("coluna VERSION deveria ser 'versao'", linha.any { it.first == "versao" })
        assertEquals("aberto", RelatorioDoc.estilizar("Host is up (0.0021s latency).").first().first)
        assertEquals("erro", RelatorioDoc.estilizar("connect: Connection refused").first().first)
        assertEquals("titulo", RelatorioDoc.estilizar("PORT     STATE  SERVICE  VERSION").first().first)
    }
}
