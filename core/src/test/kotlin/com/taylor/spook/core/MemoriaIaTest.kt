package com.taylor.spook.core

import com.taylor.spook.core.server.MemoriaIa
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A memoria da IA precisa sobreviver a reinicio (arquivos) e nao duplicar
 * fatos entre dispositivos. E o equivalente celular do usuario-*.json do
 * relay do Oracle.
 */
class MemoriaIaTest {

    private fun dirNovo(): File =
        java.nio.file.Files.createTempDirectory("memoria-ia").toFile()

    @Test
    fun `historico vazio e roundtrip por sessao`() {
        val dir = dirNovo()
        val mem = MemoriaIa(dir)
        assertTrue(mem.historico("s1").isEmpty())

        val hist = mutableListOf(
            buildJsonObject { put("role", "user"); put("content", "oi") },
            buildJsonObject { put("role", "assistant"); put("content", "ola") },
        )
        mem.salvarHistorico("s1", hist)

        val lido = MemoriaIa(dir).historico("s1")
        assertEquals(2, lido.size)
        assertEquals("oi", lido[0]["content"]?.toString()?.trim('"'))
    }

    @Test
    fun `perfil funde sem duplicar e conta novos`() {
        val mem = MemoriaIa(dirNovo())
        assertEquals(2, mem.fundirPerfil("taylor", listOf("gosta de cafe", "mora em SP")))
        // repetido (mesmo texto e substring case-insensitive) nao conta
        assertEquals(1, mem.fundirPerfil("taylor", listOf("Gosta de cafe", "usa Arch Linux")))
        assertEquals(3, mem.usuario("taylor").perfil.size)
    }

    @Test
    fun `perfil respeita o cap de 60 fatos`() {
        val mem = MemoriaIa(dirNovo())
        // fatos com largura fixa para nenhum ser substring de outro
        mem.fundirPerfil("taylor", (1..80).map { "fato ${it.toString().padStart(3, '0')}" })
        val perfil = mem.usuario("taylor").perfil
        assertEquals(MemoriaIa.MAX_PERFIL, perfil.size)
        // mantem os ultimos: o primeiro fato nao deve estar mais la
        assertTrue(perfil.first().contains("021"))
        assertTrue(perfil.last().contains("080"))
    }

    @Test
    fun `instrucoes permanentes persistem`() {
        val dir = dirNovo()
        val mem = MemoriaIa(dir)
        val u = mem.usuario("taylor")
        u.instrucoes = "fale curto"
        mem.salvarUsuario("taylor", u)
        assertEquals("fale curto", MemoriaIa(dir).usuario("taylor").instrucoes)
    }
}
