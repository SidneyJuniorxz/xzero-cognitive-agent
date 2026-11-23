import android.content.Context
import com.xzero.agent.Agent
import com.xzero.agent.VoiceSemantics
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import support.AgentProbe
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Classificadores locais que decidem o turno ANTES de qualquer chamada de LLM.
 * É o caminho mais barato do agente e o que mais quebra em silêncio: um atalho que
 * sequestra um comando real, ou um pedido visual que não exige screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DecisoesLocaisTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var probe: AgentProbe

    @Before fun setUp() {
        VoiceSemantics.init(ctx)
        probe = AgentProbe(ctx)
    }

    // ---------- turnos especiais (cancelamento / RLHF) ----------

    @Test fun fala_para_terceiro_encerra_o_turno_sem_loop() {
        assertEquals(Agent.SpecialTurnType.ABORT_IGNORE, probe.specialTurn("não tô falando contigo, calma"))
        assertEquals(Agent.SpecialTurnType.ABORT_IGNORE, probe.specialTurn("tava conversando com a minha mãe"))
        assertEquals(Agent.SpecialTurnType.ABORT_IGNORE, probe.specialTurn("cancela"))
    }

    @Test fun elogio_vira_reforco_positivo_e_critica_vira_correcao() {
        assertEquals(Agent.SpecialTurnType.POSITIVE_REINFORCEMENT, probe.specialTurn("ótima resposta, valeu"))
        assertEquals(Agent.SpecialTurnType.CORRECTIVE_FEEDBACK, probe.specialTurn("resposta errada, deveria ter feito outra coisa"))
        assertEquals(Agent.SpecialTurnType.NONE, probe.specialTurn("abre o youtube"))
    }

    // ---------- atalho local sem LLM ----------

    @Test fun pergunta_de_presenca_responde_sem_llm() {
        val ans = probe.instantAnswer("tá me ouvindo")
        assertNotNull(ans, "'tá me ouvindo' deveria ter resposta instantânea local")
        assertTrue(ans!!.split(" ").size <= 14, "resposta instantânea deve ser curta: '$ans'")
    }

    @Test fun atalho_nao_sequestra_turno_que_tem_comando_real() {
        // Esta é a trava que impede "me escuta e abre o YouTube" de virar só "tô te ouvindo".
        assertNull(probe.instantAnswer("tá me ouvindo? abre o youtube"))
        assertNull(probe.instantAnswer("na escuta, manda mensagem pro ColegaEquipe"))
    }

    @Test fun frase_longa_nao_entra_no_atalho() {
        assertNull(probe.instantAnswer("tá me ouvindo bem agora que eu estou aqui no meio da rua com barulho"))
    }

    // ---------- evidência visual ----------

    @Test fun pedido_sobre_imagem_exige_pixels() {
        assertTrue(probe.requiresVisualEvidence("o que tem na imagem?"))
        assertTrue(probe.requiresVisualEvidence("qual a cor do carro nessa foto"))
        assertTrue(probe.requiresVisualEvidence("descreve a imagem pra mim"))
    }

    @Test fun pedido_para_gerar_imagem_nao_exige_pixels() {
        assertFalse(probe.requiresVisualEvidence("gere uma imagem de um robô"))
        assertFalse(probe.requiresVisualEvidence("desenhe um gato"))
    }

    @Test fun metadado_de_contexto_nao_confunde_o_classificador() {
        val comMetadado = "o que tem na imagem? [CONTEXTO DE VOZ: ruído de fundo]"
        assertEquals("o que tem na imagem?", probe.stripTaskMetadata(comMetadado))
        assertTrue(probe.requiresVisualEvidence(comMetadado))
    }

    // ---------- intenção local (orçamento de contexto enviado ao LLM) ----------

    @Test fun conversa_pura_nao_gasta_dump_de_tela() {
        assertEquals("CONVERSA", probe.localIntent("quem é você"))
    }

    @Test fun telemetria_vai_para_modo_sistema() {
        assertEquals("SISTEMA_OU_MCP", probe.localIntent("quanto de bateria e temperatura"))
        assertEquals("SISTEMA_OU_MCP", probe.localIntent("lista os servidores mcp"))
    }

    @Test fun pedido_de_ui_vai_para_modo_tela() {
        assertEquals("ACAO_NA_TELA", probe.localIntent("abre o youtube"))
        assertEquals("ACAO_NA_TELA", probe.localIntent("clica no botão de enviar"))
    }

    @Test fun apos_tocar_na_tela_o_modo_continua_de_tela() {
        // Mesmo um pedido conversacional mantém o dump quando a sessão já mexeu na UI.
        assertEquals("ACAO_NA_TELA", probe.localIntent("e agora?", listOf("tap" to "ok")))
    }

    // ---------- detecção de falha (penaliza a rota no grafo) ----------

    @Test fun erro_de_mcp_conta_como_falha() {
        // Regressão registrada no próprio código: "erro MCP [...]" não casava com startsWith("erro:")
        // e o grafo aprendia que a ação ruim era boa.
        assertTrue(probe.agent.isFailureResult("erro MCP [brain/search]: unknown tool"))
        assertTrue(probe.agent.isFailureResult("falhou: timeout"))
        assertTrue(probe.agent.isFailureResult("HTTP 500 interno"))
        assertFalse(probe.agent.isFailureResult("conversa do WhatsApp aberta e despacho: ok"))
    }
}
