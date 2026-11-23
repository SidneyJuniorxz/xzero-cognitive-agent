import android.content.Context
import com.xzero.agent.HealthMonitor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regressões de defeitos REAIS encontrados pelo banco de testes, ainda abertos no app.
 *
 * Estes testes falham de propósito: cada falha aqui é um bug do app esperando correção,
 * não um problema do testbed. Quando o app for corrigido, o teste fica verde e vira
 * proteção contra a volta do defeito. Detalhes em testbed/README.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BugsConhecidosTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /**
     * BUG ABERTO — HealthMonitor.kt:47 (calculateBackoffMs) + :107 (recordHealAttempt).
     *
     * `recordHealAttempt` chama `calculateBackoffMs(accessibilityDisableCountInWindow - 1)`.
     * Quando o contador está em 0 — processo recém-iniciado, ou depois de `clearOldCounters`
     * zerar a janela de 5 min — o argumento vira -1, e `1 shl minOf(-1, 7)` desloca 31 bits
     * (o shift é mascarado em 5 bits na JVM), resultando em Int.MIN_VALUE:
     *
     *     exponentialBaseMs = -2.147.483.648.000
     *     cappedMs          = -2.147.483.648.000   (minOf com 120_000 escolhe o negativo)
     *     random.nextLong(0, cappedMs / 2)         -> IllegalArgumentException
     *
     * E não é um canto raro: `clearOldCounters()` roda no início de todo `checkAndHeal`
     * e zera o contador sempre que a última desativação registrada for mais velha que a
     * janela de 5 min (inclusive quando nunca houve uma). Ou seja, na prática QUALQUER
     * tentativa de auto-cura que não confirme na hora cai no argumento -1.
     *
     * Impacto no aparelho: `checkAndHeal(heal = true)` roda a cada passo do agente
     * (Agent.kt:891). Com a acessibilidade desligada e a tentativa de reativar não
     * confirmando na hora — o caso comum, porque o serviço não reconecta em poucos ms —
     * a exceção sobe por checkAndHeal e mata a tarefa inteira, em vez de o agente avisar
     * que precisa reativar a acessibilidade. Ou seja: o caminho de auto-cura é justamente
     * o que derruba a execução.
     */
    @Test fun backoff_de_autocura_nao_deve_estourar_quando_a_janela_esta_zerada() {
        com.xzero.agent.Prefs(ctx).lastAccessibilityDisableAtMs = 0L
        HealthMonitor.accessibilityDisableCountInWindow = 0
        // heal = true com a acessibilidade ausente (instance == null no testbed)
        HealthMonitor.checkAndHeal(ctx, heal = true)
    }
}
