import android.content.Context
import com.xzero.agent.Agent.ConfirmClass
import com.xzero.agent.VoiceSemantics
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import support.AgentProbe
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Portão de confirmação falada — o ponto mais sensível do sistema: é ele que decide
 * se uma ação irreversível (mandar WhatsApp, ligar, apagar) vai acontecer.
 * Um "não" lido como "sim" aqui é o pior defeito possível do agente.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ConfirmacaoFaladaTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var probe: AgentProbe

    @Before fun setUp() {
        VoiceSemantics.init(ctx)
        probe = AgentProbe(ctx)
    }

    private fun classOf(reply: String) = probe.classifyConfirmation(reply).first

    @Test fun aceites_claros_sao_afirmativos() {
        listOf("sim", "pode mandar", "confirmo", "beleza", "manda ver", "isso mesmo", "ok").forEach {
            assertEquals(ConfirmClass.AFFIRMATIVE, classOf(it), "'$it' deveria ser aceite")
        }
    }

    @Test fun negacoes_curtas_cancelam() {
        listOf("não", "nao", "cancela", "esquece", "negativo").forEach {
            assertEquals(ConfirmClass.NEGATIVE, classOf(it), "'$it' deveria cancelar")
        }
    }

    @Test fun negacao_nunca_vira_aceite_mesmo_com_palavra_positiva_na_frase() {
        // "não, pode deixar" tem "pode" dentro — não pode ser lido como autorização.
        assertTrue(classOf("não, pode deixar") != ConfirmClass.AFFIRMATIVE)
        assertTrue(classOf("não manda isso não") != ConfirmClass.AFFIRMATIVE)
        assertTrue(classOf("melhor não") != ConfirmClass.AFFIRMATIVE)
    }

    @Test fun negacao_com_complemento_e_ajuste_nao_cancelamento() {
        // "não, manda pro ColegaEquipe" é correção de argumento: o fluxo deve recalcular, não abortar.
        val c = classOf("não, manda pro ColegaEquipe em vez do Danilo")
        assertTrue(c == ConfirmClass.ADJUST || c == ConfirmClass.QUESTION, "esperava ajuste, veio $c")
    }

    @Test fun pergunta_sobre_o_plano_nao_e_autorizacao() {
        assertEquals(ConfirmClass.QUESTION, classOf("o que você vai mandar exatamente?"))
    }

    @Test fun silencio_e_vazio_nunca_autorizam() {
        assertEquals(ConfirmClass.EMPTY, classOf(""))
        assertEquals(ConfirmClass.EMPTY, classOf("(sem resposta)"))
    }

    // ---------- quais ações exigem confirmação ----------

    @Test fun acao_irreversivel_pede_confirmacao_e_consulta_passiva_nao() {
        assertTrue(
            probe.requiresPreExecConfirmation("send_whatsapp", JSONObject().put("text", "oi")),
            "enviar WhatsApp deve pedir confirmação falada",
        )
        assertFalse(
            probe.requiresPreExecConfirmation("device_status", JSONObject()),
            "ler telemetria não deve pedir confirmação",
        )
    }
}
