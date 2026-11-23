import android.content.Context
import com.xzero.agent.AmbientContextEngine
import com.xzero.agent.AmbientContextEngine.BtwDirectiveType
import com.xzero.agent.KnowledgeGraphMemory
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Motor de contexto ambiente: wake word, `/btw` em paralelo, pontuação de intenção e
 * hesitação. É o que decide se o X0 acorda, e acordar errado custa token e paciência.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContextoAmbienteTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var ambient: AmbientContextEngine

    @Before fun setUp() {
        ambient = AmbientContextEngine(KnowledgeGraphMemory(ctx))
    }

    // ---------- wake word ----------

    @Test fun nome_do_agente_e_separado_do_comando() {
        val (wake, cmd) = assertNotNull(ambient.detectWakeName("x zero, abre o youtube"))
        assertTrue(wake.isNotBlank())
        assertEquals("abre o youtube", cmd)
    }

    @Test fun contexto_antes_do_nome_e_preservado() {
        val (_, cmd) = assertNotNull(ambient.detectWakeName("preciso de ajuda, xzero manda mensagem"))
        assertTrue(cmd.startsWith("preciso de ajuda"), "o que veio antes do nome não pode ser perdido: '$cmd'")
        assertTrue(cmd.contains("manda mensagem"))
    }

    @Test fun nome_dentro_de_outra_palavra_nao_acorda() {
        // a trava de fronteira de palavra existe para não acordar em "proxzeroide"
        assertNull(ambient.detectWakeName("isso é um texto sem chamado nenhum aqui"))
    }

    // ---------- /btw em paralelo ----------

    @Test fun btw_explicito_vira_contexto_paralelo() {
        val d = assertNotNull(ambient.detectBtwTrigger("/btw inclui o Danilo também", isAgentCurrentlyWorking = false))
        assertEquals(BtwDirectiveType.APPEND_PARALLEL_CONTEXT, d.type)
        assertEquals("inclui o Danilo também", d.cleanText)
    }

    @Test fun cancelar_durante_execucao_cancela_a_tarefa() {
        val d = assertNotNull(ambient.detectBtwTrigger("cancela", isAgentCurrentlyWorking = true))
        assertEquals(BtwDirectiveType.CANCEL_CURRENT_TASK, d.type)
    }

    @Test fun pedir_espera_durante_execucao_pausa_em_vez_de_cancelar() {
        val d = assertNotNull(ambient.detectBtwTrigger("espera um pouco", isAgentCurrentlyWorking = true))
        assertEquals(BtwDirectiveType.PAUSE_AND_WAIT, d.type)
    }

    @Test fun mudar_de_ideia_recalcula_a_rota() {
        val d = assertNotNull(ambient.detectBtwTrigger("em vez disso usa o Telegram", isAgentCurrentlyWorking = true))
        assertEquals(BtwDirectiveType.RECALCULATE_ROUTE, d.type)
    }

    @Test fun trocar_de_tarefa_substitui_o_objetivo() {
        val d = assertNotNull(ambient.detectBtwTrigger("esquece isso, abre o spotify", isAgentCurrentlyWorking = true))
        assertEquals(BtwDirectiveType.SWITCH_MAIN_TASK, d.type)
    }

    @Test fun fala_solta_com_agente_parado_nao_e_btw() {
        assertNull(ambient.detectBtwTrigger("hoje o dia está bonito", isAgentCurrentlyWorking = false))
    }

    @Test fun so_o_nome_durante_execucao_arma_a_escuta_btw() {
        assertNull(ambient.detectBtwTrigger("x0", isAgentCurrentlyWorking = true))
        assertTrue(ambient.isBtwArmed(), "falar só o nome deveria armar a escuta /btw")
    }

    // ---------- pontuação de intenção ----------

    @Test fun check_in_conversacional_pontua_positivo() {
        val (score, reason) = ambient.scoreChunkIntent("tá me ouvindo")
        assertTrue(score > 0, "check-in deveria pontuar (veio $score / $reason)")
    }

    @Test fun conversa_com_terceiro_zera_e_fecha_a_janela_live() {
        ambient.openFollowUpWindow(30_000L)
        val (score, reason) = ambient.scoreChunkIntent("tava conversando com a minha esposa")
        assertTrue(score < 0, "conversa externa tem de pontuar negativo (veio $score)")
        assertEquals("conversa_externa_ou_fim_live", reason)
        assertFalse(ambient.isInFollowUpWindow(), "a janela Live deveria ter fechado")
    }

    @Test fun frase_curta_demais_nao_pontua() {
        assertEquals(0.0, ambient.scoreChunkIntent("ah").first, 0.0001)
    }

    // ---------- hesitação ----------

    @Test fun frase_terminada_em_conectivo_e_tratada_como_incompleta() {
        assertTrue(ambient.isIncompleteOrHesitationPhrase("manda uma mensagem pro"))
        assertTrue(ambient.isIncompleteOrHesitationPhrase("deixa eu pensar"))
        assertFalse(ambient.isIncompleteOrHesitationPhrase("manda uma mensagem pro ColegaEquipe"))
    }

    @Test fun melhor_candidato_do_stt_prefere_hipotese_com_wake_word() {
        val escolhido = ambient.selectBestTranscriptCandidate(
            candidates = listOf("eis que era", "x zero abre o youtube"),
            isAgentWorking = false,
        )
        assertEquals("x zero abre o youtube", escolhido)
    }
}
