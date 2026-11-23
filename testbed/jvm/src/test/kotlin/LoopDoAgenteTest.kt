import android.content.Context
import com.xzero.agent.Agent
import com.xzero.agent.HealthMonitor
import com.xzero.agent.Prefs
import com.xzero.agent.VoiceSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import support.FakeLlm
import support.RecordingUi
import kotlin.test.assertTrue

/**
 * Loop de decisão de ponta a ponta, fora do celular: o Agent real, o prompt real e o
 * parser real, com o LLM substituído por roteiro. É o que antes exigia aparelho
 * conectado, token de API e ~75 s por cenário.
 *
 * Limite conhecido: sem o serviço de acessibilidade, o agente vê
 * "(serviço de acessibilidade desativado)" como tela. Cenários que dependem de tocar
 * em elementos `[id]` da tela precisam do seam de injeção descrito no testbed/README.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LoopDoAgenteTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var ui: RecordingUi
    private lateinit var agent: Agent

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        FakeLlm.install()
        FakeLlm.reset()
        VoiceSemantics.init(ctx)
        Prefs(ctx).apply {
            geminiKey = "chave-de-testbed"
            primary = "gemini"
            speak = false
            preExecVoiceConfirm = false
        }
        // Desvio de um bug aberto do app: com o contador em 0, o backoff da auto-cura
        // estoura IllegalArgumentException a cada passo (ver BugsConhecidosTest).
        // Só o contador não basta: clearOldCounters() o zera de novo se a última
        // desativação registrada for antiga, então marcamos "agora" também.
        Prefs(ctx).lastAccessibilityDisableAtMs = System.currentTimeMillis()
        HealthMonitor.accessibilityDisableCountInWindow = 1
        ui = RecordingUi()
        agent = Agent(ctx, ui)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun run(task: String) = runBlocking { agent.run(task) }

    @Test fun pergunta_conversacional_termina_no_primeiro_passo() {
        FakeLlm.enqueueDecisions(FakeLlm.done("Sou a ADA, seu assistente no celular."))
        val answer = run("quem é você")
        assertTrue(answer.contains("ADA"), "resposta inesperada: '$answer'")
        assertTrue(FakeLlm.requests.size == 1, "deveria ter chamado o LLM uma única vez (chamou ${FakeLlm.requests.size})")
        assertTrue(ui.completion?.first == true, "tarefa conversacional deveria terminar verificada")
    }

    @Test fun atalho_local_nem_chama_o_llm() {
        val answer = run("tá me ouvindo")
        assertTrue(answer.isNotBlank())
        assertTrue(FakeLlm.requests.isEmpty(), "atalho local não pode gastar chamada de LLM")
    }

    @Test fun json_invalido_tres_vezes_para_sem_entrar_em_loop() {
        FakeLlm.enqueueRawText("não sou JSON", "ainda não sou JSON", "segue sem JSON")
        val answer = run("abre o youtube")
        assertTrue(answer.contains("fora do formato"), "esperava abortar por formato: '$answer'")
        assertTrue(FakeLlm.requests.size == 3, "deveria tentar 3x e parar (tentou ${FakeLlm.requests.size})")
    }

    @Test fun json_invalido_uma_vez_se_recupera_no_passo_seguinte() {
        FakeLlm.enqueueRawText("resposta torta sem json")
        // Dois 'done' de propósito: hoje o primeiro é barrado pelo GoalVerifier, porque a
        // reclamação de formato fica em `lastResult` começando com "erro:" e o verificador
        // a lê como "a última ação falhou" — mesmo em tarefa conversacional, que não tem
        // ação nenhuma. Custa uma chamada extra (e no modelo forte). Se isso for corrigido,
        // o teste continua verde: o que ele exige é a recuperação, em no máximo 3 chamadas.
        FakeLlm.enqueueDecisions(
            FakeLlm.done("Pronto, era só isso."),
            FakeLlm.done("Pronto, era só isso."),
        )
        val answer = run("quem é você")
        assertTrue(answer.contains("Pronto"), "deveria se recuperar: '$answer'")
        assertTrue(ui.logsContaining("JSON inválido").isNotEmpty(), "deveria logar a correção pedida ao modelo")
        assertTrue(FakeLlm.requests.size <= 3, "recuperação custou ${FakeLlm.requests.size} chamadas de LLM")
    }

    @Test fun say_em_conversa_e_convertido_em_done() {
        FakeLlm.enqueueDecisions(
            FakeLlm.action("say", JSONObject().put("text", "Tudo certo por aqui.")),
        )
        val answer = run("tudo bem com você")
        assertTrue(answer.contains("Tudo certo"), "o 'say' deveria fechar a tarefa: '$answer'")
        assertTrue(ui.logsContaining("Auto-finalização").isNotEmpty(), "deveria registrar a conversão say->done")
    }

    @Test fun done_sem_evidencia_visual_e_bloqueado_pelo_verificador() {
        FakeLlm.enqueueDecisions(
            FakeLlm.done("É uma foto de um robô azul."),
            FakeLlm.done("Não consegui confirmar o que há na imagem."),
        )
        run("o que tem na imagem?")
        assertTrue(
            ui.logsContaining("GoalVerifier bloqueou done prematuro").isNotEmpty(),
            "o verificador tinha de barrar o primeiro done sem pixels. Logs: ${ui.logs.takeLast(6)}",
        )
    }

    @Test fun falha_do_provedor_cai_para_o_proximo_e_a_tarefa_continua() {
        FakeLlm.enqueueFailure(500, "quota estourada")
        FakeLlm.enqueueDecisions(FakeLlm.done("Respondi na segunda tentativa."))
        val answer = run("quem é você")
        assertTrue(answer.contains("segunda tentativa"), "deveria sobreviver à falha do provedor: '$answer'")
    }

    @Test fun prompt_enviado_carrega_o_contrato_e_o_pedido() {
        FakeLlm.enqueueDecisions(FakeLlm.done("ok"))
        run("quem é você")
        val body = FakeLlm.requests.first().body
        assertTrue(body.contains("quem é você"), "o pedido do usuário deveria estar no prompt")
        assertTrue(body.contains("Modo de intenção local"), "o classificador local deveria entrar no prompt")
        assertTrue(body.contains("systemInstruction"), "deveria usar systemInstruction do Gemini")
    }
}
