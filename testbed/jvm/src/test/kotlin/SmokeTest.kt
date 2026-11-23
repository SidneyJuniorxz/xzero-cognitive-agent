import android.content.Context
import com.xzero.agent.KnowledgeGraphMemory
import com.xzero.agent.Prefs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prova que as classes reais do app rodam fora do celular, com Context do Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SmokeTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun prefs_persiste_em_sharedpreferences_simulado() {
        val p = Prefs(ctx)
        p.autoIntentThreshold = 5.5
        assertEquals(5.5, Prefs(ctx).autoIntentThreshold, 0.0001)
    }

    @Test
    fun grafo_de_memoria_grava_e_recupera_fato() {
        val g = KnowledgeGraphMemory(ctx)
        g.upsertFact("Usuário", "trabalha_com", "Android", boost = 2.0)
        assertTrue(g.dumpSummary().contains("Android"), "fato deveria aparecer no resumo do grafo")
    }
}
