import android.content.Context
import com.xzero.agent.RuleEngine
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gatekeeper do RuleEngine: o que o usuário proíbe tem de ser impossível de executar,
 * e o que ele mandou confirmar não pode passar sem confirmação.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MotorDeRegrasTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var rules: RuleEngine

    @Before fun setUp() {
        // cada teste começa com o disco limpo para não herdar regras de outro caso
        File(ctx.filesDir, "user_rules.json").delete()
        rules = RuleEngine(ctx)
    }

    @Test fun regra_de_bloqueio_impede_o_toque() {
        rules.addOrUpdateRule(
            title = "Nunca apagar no WhatsApp",
            instruction = "Nunca apague mensagens ou conversas no WhatsApp",
            modeStr = "BLOCK_ALWAYS",
            targetPkg = "com.whatsapp",
            keywordsCsv = "apagar,excluir,limpar conversa",
        )
        val r = rules.evaluateUiActionBeforeExecution(
            action = "tap",
            targetElementDescription = "Apagar conversa",
            currentPackage = "com.whatsapp",
            taskText = "limpa essa conversa",
        )
        assertFalse(r.allowed, "toque proibido deveria ser bloqueado: ${r.message}")
        assertFalse(r.requiresConfirmation, "bloqueio é definitivo, não pede confirmação")
    }

    @Test fun regra_de_confirmacao_exige_aprovacao_e_a_aprovacao_vale_pela_sessao() {
        rules.addOrUpdateRule(
            title = "Confirmar exclusões",
            instruction = "Sempre confirmar antes de excluir",
            modeStr = "CONFIRM_ALWAYS",
            targetPkg = "*",
            keywordsCsv = "excluir,lixeira",
        )
        val primeiro = rules.evaluateUiActionBeforeExecution("tap", "Excluir arquivo", "com.google.android.documentsui", "apaga esse arquivo")
        assertTrue(primeiro.requiresConfirmation, "deveria pedir confirmação: ${primeiro.message}")
        assertFalse(primeiro.allowed)

        rules.grantSessionApproval("${primeiro.matchedRule!!.id}:com.google.android.documentsui:excluir")
        val segundo = rules.evaluateUiActionBeforeExecution("tap", "Excluir arquivo", "com.google.android.documentsui", "apaga esse arquivo")
        assertTrue(segundo.allowed, "após aprovar na sessão deveria liberar: ${segundo.message}")

        rules.clearSessionApprovals()
        val depoisDeLimpar = rules.evaluateUiActionBeforeExecution("tap", "Excluir arquivo", "com.google.android.documentsui", "apaga esse arquivo")
        assertTrue(depoisDeLimpar.requiresConfirmation, "nova tarefa deve pedir confirmação de novo")
    }

    @Test fun backspace_do_teclado_de_horas_nao_e_exclusao_destrutiva() {
        rules.addOrUpdateRule(
            title = "Confirmar exclusões",
            instruction = "Sempre confirmar antes de excluir",
            modeStr = "CONFIRM_ALWAYS",
            targetPkg = "*",
            keywordsCsv = "excluir,apagar",
        )
        val r = rules.evaluateUiActionBeforeExecution("tap", "Excluir", "com.google.android.deskclock", "coloca alarme 7:30")
        assertTrue(r.allowed, "apagar dígito no relógio não é exclusão de dado: ${r.message}")
    }

    @Test fun acoes_que_nao_tocam_na_tela_passam_direto() {
        rules.addOrUpdateRule("Bloqueio total", "nada", "BLOCK_ALWAYS", "*", "apagar")
        val r = rules.evaluateUiActionBeforeExecution("device_status", "apagar", "com.whatsapp", "quanto de bateria")
        assertTrue(r.allowed, "o gatekeeper só intercepta tap/long_press")
    }
}
