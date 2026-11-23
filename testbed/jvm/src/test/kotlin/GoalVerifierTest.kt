import android.content.Context
import com.xzero.agent.VoiceSemantics
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import support.AgentProbe
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GoalVerifier: decide se o agente pode dizer "pronto". É o antídoto contra o agente
 * declarar sucesso sem ter feito nada — por isso cada caso aqui vale como contrato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GoalVerifierTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var probe: AgentProbe

    @Before fun setUp() {
        VoiceSemantics.init(ctx)
        probe = AgentProbe(ctx)
    }

    @Test fun whatsapp_sem_envio_nao_e_verificado() {
        val v = probe.verifyGoal(
            task = "manda uma mensagem no whatsapp pro ColegaEquipe",
            executedActions = listOf("open_app" to "WhatsApp aberto"),
            currentApp = "com.android.launcher",
        )
        assertFalse(v.verified, "declarar pronto sem enviar deveria ser bloqueado — veio: ${v.reason}")
    }

    @Test fun whatsapp_com_envio_e_verificado() {
        val v = probe.verifyGoal(
            task = "manda uma mensagem no whatsapp pro ColegaEquipe",
            executedActions = listOf("send_whatsapp" to "conversa do WhatsApp aberta e despacho: ok"),
            currentApp = "com.whatsapp",
        )
        assertTrue(v.verified, "envio real deveria verificar — veio: ${v.reason}")
    }

    @Test fun pergunta_sobre_imagem_exige_captura_de_pixels() {
        val semEvidencia = probe.verifyGoal(
            task = "o que tem na imagem?",
            executedActions = listOf("say" to "parece um gráfico"),
        )
        assertFalse(semEvidencia.verified, "sem screenshot isso é alucinação — veio: ${semEvidencia.reason}")

        val comEvidencia = probe.verifyGoal(
            task = "o que tem na imagem?",
            executedActions = listOf("inspect_screen_visual" to "screenshot real capturado"),
            hasVisualEvidence = true,
        )
        assertTrue(comEvidencia.verified, "com pixels deveria verificar — veio: ${comEvidencia.reason}")
    }

    @Test fun criar_regra_sem_chamar_add_rule_nao_e_verificado() {
        val v = probe.verifyGoal(
            task = "cria uma regra pra nunca apagar nada no whatsapp",
            executedActions = listOf("say" to "ok, vou lembrar"),
        )
        assertFalse(v.verified, "regra só existe depois de add_rule — veio: ${v.reason}")
    }

    @Test fun alarme_via_intent_e_verificado() {
        val v = probe.verifyGoal(
            task = "coloca um alarme pras 7:30",
            executedActions = listOf("set_alarm" to "alarme criado"),
            currentApp = "com.google.android.deskclock",
        )
        assertTrue(v.verified, "set_alarm deveria verificar — veio: ${v.reason}")
    }

    @Test fun ultima_acao_com_erro_e_nenhum_progresso_bloqueia_o_done() {
        val v = probe.verifyGoal(
            task = "abre o spotify e toca a playlist",
            executedActions = listOf("open_app" to "erro: app não encontrado"),
            lastResult = "erro: app não encontrado",
        )
        assertFalse(v.verified, "falha sem progresso não pode virar sucesso — veio: ${v.reason}")
    }
}
